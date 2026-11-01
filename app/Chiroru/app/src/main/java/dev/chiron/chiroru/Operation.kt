package dev.chiron.chiroru

internal enum class Operation { PROBE, ROOT, MODULE, MODULE_SAFE, ADB_ROOT }
internal enum class Outcome { FINISHED, ROOT_READY, MODULE_LOADED, MODULE_HOOKS_FAILED, ADB_ROOT_READY, ERROR }

internal data class ReportResult(val outcome: Outcome, val kernelTouched: Boolean, val moduleLoaded: Boolean) {
    fun canRetry(report: String): Boolean = !kernelTouched &&
        !Regex("^preflight: blocked", RegexOption.MULTILINE).containsMatchIn(report)

    companion object {
        fun parse(operation: Operation, report: String): ReportResult {
            val touched = report.contains("root_stage_done") ||
                report.contains("cred patched") || report.contains("seccomp disarmed")
            val rootReady = Regex("root_stage_done uid=0 euid=0(?:\\s|$)").containsMatchIn(report)
            val moduleLoaded = (operation == Operation.MODULE || operation == Operation.MODULE_SAFE) && rootReady && (
                Regex("ksud rc=0(?:\\s|$)").containsMatchIn(report) ||
                    report.contains("module: kernelsu already loaded")
                )
            val hooksFailed = Regex("^lifecycle: status=failed$", RegexOption.MULTILINE).containsMatchIn(report)
            val outcome = when (operation) {
                Operation.PROBE -> if (Regex("open\\(/dev/mali0\\) = \\d+").containsMatchIn(report) &&
                    report.contains("VERSION_CHECK rc=0")) Outcome.FINISHED else Outcome.ERROR
                Operation.ROOT -> if (rootReady && Regex("^root_console: ready$", RegexOption.MULTILINE)
                    .containsMatchIn(report)) Outcome.ROOT_READY else Outcome.ERROR
                Operation.ADB_ROOT -> if (rootReady && Regex("^adb_root: ready$", RegexOption.MULTILINE)
                    .containsMatchIn(report)) Outcome.ADB_ROOT_READY else Outcome.ERROR
                Operation.MODULE, Operation.MODULE_SAFE -> when {
                    !moduleLoaded -> Outcome.ERROR
                    hooksFailed -> Outcome.MODULE_HOOKS_FAILED
                    else -> Outcome.MODULE_LOADED
                }
            }
            return ReportResult(outcome, touched, moduleLoaded)
        }
    }
}
