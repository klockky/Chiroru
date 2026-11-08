package dev.chiron.chiroru

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** A retained descriptor survives bootstrap's credential change. Torn records fail closed. */
internal class BootGuardStore(
    file: File,
    private val syncFile: (RandomAccessFile) -> Unit = { it.fd.sync() },
    syncDirectory: () -> Unit,
) : Closeable {
    private val handle: RandomAccessFile
    private var failed = false

    data class Record(val pending: Boolean, val boot: String)

    init {
        val created = file.createNewFile()
        handle = RandomAccessFile(file, "rw")
        try {
            if (created) write(Record(false, "-"))
            // Also sync on reopen: creation might previously have failed before directory sync.
            syncDirectory()
        } catch (error: Exception) {
            handle.close()
            throw error
        }
    }

    @Synchronized fun read(): Record? = runCatching {
        check(!failed)
        check(handle.length() in 1..256)
        handle.seek(0)
        val bytes = ByteArray(handle.length().toInt())
        handle.readFully(bytes)
        val parts = bytes.toString(Charsets.UTF_8).split('\n')
        check(parts.size == 5 && parts[0] == "chiroru-boot-1" && parts[4].isEmpty())
        check(parts[1] == "pending" || parts[1] == "ready")
        check(validBoot(parts[2]) || (parts[1] == "ready" && parts[2] == "-"))
        val body = parts.take(3).joinToString("\n", postfix = "\n")
        check(parts[3] == checksum(body))
        Record(parts[1] == "pending", parts[2])
    }.getOrNull()

    @Synchronized fun canStart(boot: String?): Boolean {
        val record = read() ?: return false
        return boot != null && validBoot(boot) && !record.pending && record.boot != boot
    }

    @Synchronized fun begin(boot: String): Boolean {
        if (!canStart(boot)) return false
        return persist(Record(true, boot))
    }

    @Synchronized fun confirm(boot: String): Boolean {
        if (read() != Record(true, boot)) return false
        return persist(Record(false, boot))
    }

    /** Explicit recovery also consumes this boot; only the next startup may autoload. */
    @Synchronized fun resume(boot: String): Boolean {
        if (!validBoot(boot)) return false
        return persist(Record(false, boot))
    }

    private fun persist(record: Record): Boolean = try {
        write(record)
        failed = false
        true
    } catch (_: Exception) {
        failed = true
        false
    }

    private fun write(record: Record) {
        val body = "chiroru-boot-1\n${if (record.pending) "pending" else "ready"}\n${record.boot}\n"
        val bytes = "$body${checksum(body)}\n".toByteArray(Charsets.UTF_8)
        handle.seek(0)
        handle.write(bytes)
        handle.setLength(bytes.size.toLong())
        syncFile(handle)
    }

    @Synchronized override fun close() = handle.close()

    companion object {
        private fun validBoot(value: String) = value.matches(
            Regex("(?:id:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|count:[0-9]{1,10})"))
        private fun checksum(body: String) = MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
