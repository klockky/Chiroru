package dev.chiron.chiroru

/** JNI transport only; backend IDs are resolved by cpp/backend_registry.c. */
internal object NativeBridge {
    init { System.loadLibrary("chiroru") }

    @JvmStatic external fun supports(backendId: String): Boolean
    /** 1: GET_INFO succeeded; -1: seccomp blocked; 0: unavailable, not absence. */
    @JvmStatic external fun kernelSuQuery(): Int
    @JvmStatic external fun adbRootPresent(): Boolean
    @JvmStatic external fun consoleRootPresent(): Boolean
    @JvmStatic external fun prepareProcessConsole(path: String): Boolean
    @JvmStatic external fun processConsolePresent(): Boolean
    @JvmStatic external fun startProcessConsole(command: ByteArray): IntArray?
    @JvmStatic external fun probe(backendId: String): String
    @JvmStatic external fun grantBootAdbPermission(): String
    @JvmStatic external fun exploit(backendId: String, koPath: String?, ksudPath: String?, runHooks: Boolean): String
}
