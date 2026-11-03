package dev.chiron.chiroru

/** Stable preference IDs; selecting a method never starts a native operation. */
internal enum class RootMethod(val id: String, val operation: Operation, val label: Int, val description: Int) {
    PROBE("probe", Operation.PROBE, R.string.method_probe, R.string.method_probe_description),
    IN_APP("in_app", Operation.ROOT, R.string.method_in_app, R.string.method_in_app_description),
    ADB("adb", Operation.ADB_ROOT, R.string.method_adb, R.string.method_adb_description),
    KERNEL_SU("kernelsu", Operation.MODULE, R.string.method_kernelsu, R.string.method_kernelsu_description);

    fun supported(state: AppState): Boolean = state.duoMax && state.firmwareSupport.canRoot && when (this) {
        KERNEL_SU -> state.firmwareSupport.canLoadKernelSu
        ADB -> state.adbRootSupported
        else -> true
    }

    fun canRun(state: AppState): Boolean = supported(state) && state.running == null &&
        !state.preparingManager && (this != KERNEL_SU || state.bundledManager == null || state.bundledManager == BundledManagerState.READY) &&
        !state.console.running && state.managerStep?.busy != true &&
        (this == PROBE || (!state.kernelTouched && !state.checkingRuntime && !state.runtime.blocksRootOperation))

    companion object {
        fun fromId(id: String?): RootMethod = entries.firstOrNull { it.id == id } ?: KERNEL_SU
    }
}
