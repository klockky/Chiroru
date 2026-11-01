package dev.chiron.chiroru.firmware

import dev.chiron.chiroru.NativeBridge
import dev.chiron.chiroru.Operation

internal interface FirmwareBackend {
    fun supports(id: String): Boolean
    fun probe(id: String): String
    fun root(id: String, module: String?, ksud: String?, runHooks: Boolean): String
}

internal object BundledFirmwareBackend : FirmwareBackend {
    override fun supports(id: String) = NativeBridge.supports(id)
    override fun probe(id: String) = NativeBridge.probe(id)
    override fun root(id: String, module: String?, ksud: String?, runHooks: Boolean) =
        NativeBridge.exploit(id, module, ksud, runHooks)
}

/** Shared by manual/intent/boot entry points; UI does not select addresses or payloads. */
internal class FirmwareRunner(
    private val assets: FirmwareAssets,
    private val backend: FirmwareBackend,
) {
    fun run(selection: FirmwareSelection, operation: Operation): String {
        val header = selection.report() + "\n"
        if (!selection.identity.isDuoMax) return header + "preflight: blocked (Yandex Duo Max only)\n"
        val profile = selection.profile ?: return header + "preflight: blocked (unsupported firmware)\n"
        if (profile.identity != selection.identity) return header + "preflight: blocked (firmware identity mismatch)\n"
        val needsModule = operation == Operation.MODULE || operation == Operation.MODULE_SAFE
        val backendId = if (operation == Operation.ADB_ROOT) {
            profile.adbRoot?.backend ?: return header + "preflight: blocked (ADB root is not supported)\n"
        } else if (needsModule) {
            profile.kernelSu?.backend ?: return header + "preflight: blocked (KernelSU is not supported; root-only profile)\n"
        } else profile.root.backend
        val payloads = try {
            check(backend.supports(backendId)) { "Native backend missing: $backendId" }
            when {
                operation == Operation.ADB_ROOT -> assets.prepareAdb(profile)
                needsModule -> assets.prepare(profile)
                else -> null
            }
        } catch (error: Exception) {
            return header + "preflight: blocked (${error.message})\n"
        }
        val report = when (operation) {
            Operation.PROBE -> backend.probe(backendId)
            Operation.ROOT -> backend.root(backendId, null, null, false)
            Operation.MODULE, Operation.MODULE_SAFE, Operation.ADB_ROOT -> backend.root(
                backendId, checkNotNull(payloads).first, payloads.second, operation == Operation.MODULE,
            )
        }
        return header + report
    }
}
