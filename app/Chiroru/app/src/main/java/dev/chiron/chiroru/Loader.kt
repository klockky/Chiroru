package dev.chiron.chiroru

import android.content.Context
import android.os.Looper
import android.util.Log
import dev.chiron.chiroru.firmware.BundledFirmwareBackend
import dev.chiron.chiroru.firmware.FirmwareAssets
import dev.chiron.chiroru.firmware.FirmwareEnvironment
import dev.chiron.chiroru.firmware.FirmwareRunner
import java.io.File

/** Android adapter for the existing native implementation. Calls stay on the group leader. */
internal object Loader {
    const val TAG = "Chiroru"
    var kernelTouched = false
        private set
    var moduleOutcome: Outcome? = null
        private set
    val moduleLoaded: Boolean get() = moduleOutcome != null
    var runtime = RuntimeStatus()

    fun storage(context: Context): Context = context.createDeviceProtectedStorageContext()
    fun reportFile(context: Context) = File(storage(context).filesDir, "boot-report.txt")

    fun run(context: Context, operation: Operation): String {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Native calls require the main thread" }
        if (operation != Operation.PROBE && kernelTouched) return "Already executed in this process; restart required"
        if (operation != Operation.PROBE && runtime.blocksRootOperation)
            return "preflight: blocked (root credentials or loaded KernelSU detected; no operation executed)\n"
        return try {
            val selection = FirmwareEnvironment.inspect(context)
            if (!selection.identity.isDuoMax) return selection.report() + "\npreflight: blocked (Yandex Duo Max only)\n"
            if (selection.profile == null) return selection.report() + "\npreflight: blocked (unsupported firmware)\n"
            if (operation == Operation.ROOT && selection.profile != null &&
                NativeBridge.supports(selection.profile.root.backend)) {
                check(NativeBridge.prepareProcessConsole(
                    "${context.applicationInfo.nativeLibraryDir}/libchiroru_root_console.so")) {
                    "Cannot prepare process root console"
                }
            }
            if (operation == Operation.MODULE || operation == Operation.MODULE_SAFE) {
                AppIo.prepare(context)
            }
            val report = FirmwareRunner(
                FirmwareAssets(storage(context).filesDir) { context.assets.open(it) },
                BundledFirmwareBackend,
                { ManagerPackages.readyForLoad(context, it) },
            ).run(selection, operation)
            val result = ReportResult.parse(operation, report)
            kernelTouched = kernelTouched || result.kernelTouched
            if (result.moduleLoaded) moduleOutcome = result.outcome
            val completeReport = report + BootAdb.grantAfterRoot(context, result.outcome)
            Log.i(TAG, completeReport)
            completeReport
        } catch (error: Exception) {
            Log.e(TAG, "Native operation failed", error)
            "native call failed: $error"
        } catch (error: LinkageError) {
            Log.e(TAG, "Cannot load native library", error)
            "native call failed: $error"
        }
    }
}
