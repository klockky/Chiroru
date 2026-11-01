package dev.chiron.chiroru

import java.io.File
import java.util.UUID

internal object BootSession {
    /** Android BOOT_COUNT can change on a framework restart; this changes only with the kernel. */
    fun currentId(): String? = runCatching {
        UUID.fromString(File("/proc/sys/kernel/random/boot_id").readText().trim()).toString()
    }.getOrNull()
}
