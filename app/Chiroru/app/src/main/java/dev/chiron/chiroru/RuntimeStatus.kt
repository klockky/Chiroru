package dev.chiron.chiroru

import android.os.Looper
import android.system.Os
import java.io.File

internal enum class KernelSuState { LOADED, NOT_FOUND, UNKNOWN }

/** Live evidence only. Neither boot reports nor saved preferences confer root. */
internal data class RuntimeStatus(
    val processRoot: Boolean = false,
    val kernelSu: KernelSuState = KernelSuState.UNKNOWN,
    val adbRoot: Boolean = false,
) {
    val blocksRootOperation: Boolean get() = processRoot || kernelSu == KernelSuState.LOADED || adbRoot

    companion object {
        fun fromEvidence(uid: Int, euid: Int, modules: String?, driverPresent: Boolean): RuntimeStatus {
            val entry = modules?.lineSequence()?.map { it.trim().split(Regex("\\s+")) }
                ?.firstOrNull { it.firstOrNull() == "kernelsu" }
            val liveModule = entry != null && entry.size >= 5 && entry[4] == "Live"
            return RuntimeStatus(uid == 0 && euid == 0, when {
                driverPresent || liveModule -> KernelSuState.LOADED
                entry != null -> KernelSuState.UNKNOWN
                modules != null -> KernelSuState.NOT_FOUND
                else -> KernelSuState.UNKNOWN
            })
        }
    }
}

internal object RuntimeDetector {
    fun adbRoot(): Boolean = try { NativeBridge.adbRootPresent() } catch (_: LinkageError) { false }
    /** Credentials must be sampled on the group leader, not an IO worker. */
    fun processRoot(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return Os.getuid() == 0 && Os.geteuid() == 0
    }

    fun kernelSu(): KernelSuState {
        val modules = runCatching { File("/proc/modules").readText() }.getOrNull()
        val listed = RuntimeStatus.fromEvidence(-1, -1, modules, false).kernelSu
        if (listed == KernelSuState.LOADED) return listed
        val driver = try { NativeBridge.kernelSuQuery() } catch (_: LinkageError) { 0 }
        android.util.Log.i(Loader.TAG, "Runtime evidence: modulesReadable=${modules != null} driverQuery=$driver")
        return RuntimeStatus.fromEvidence(-1, -1, modules, driver == 1).kernelSu
    }
}
