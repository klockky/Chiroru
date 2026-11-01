package dev.chiron.chiroru

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

internal data class ConsoleState(
    val running: Boolean = false,
    val output: String = "",
    val exitCode: Int? = null,
    val stopped: Boolean = false,
    val error: String? = null,
)

/** One-shot shells with streaming, bounded output; no command intent/exported IPC. */
internal class ConsoleSession(private val executable: String, private val directory: File,
    private val rootProcess: ((String) -> Process?)? = null) {
    private val mutableState = MutableStateFlow(ConsoleState())
    val state = mutableState.asStateFlow()
    private val lock = Any()
    private var process: Process? = null
    private var stopRequested = false
    private var closed = false

    fun reserve(command: String, root: Boolean = false): Boolean = synchronized(lock) {
        if (closed || mutableState.value.running || command.isBlank() || command.length > 16384 || '\u0000' in command) return false
        stopRequested = false
        mutableState.update { it.copy(running = true, exitCode = null, stopped = false, error = null,
            output = bounded(it.output + "\n${if (root) "#" else "$"} $command\n")) }
        true
    }

    /** Blocking: call on an IO worker, after reserve(). */
    fun execute(command: String, root: Boolean) {
        var child: Process? = null
        try {
            synchronized(lock) {
                if (stopRequested || closed) return
                child = (if (root) rootProcess?.invoke(command) else null) ?: ProcessBuilder(executable, if (root) "--root" else "--user", command)
                    .directory(directory).redirectErrorStream(true).apply {
                        environment()["HOME"] = directory.absolutePath
                        environment()["TERM"] = "dumb"
                    }.start()
                process = child
            }
            val active = checkNotNull(child)
            active.inputStream.reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    val text = String(buffer, 0, count)
                    mutableState.update { it.copy(output = bounded(it.output + text)) }
                }
            }
            val code = active.waitFor()
            mutableState.update { it.copy(exitCode = code) }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.message ?: error.javaClass.simpleName) }
        } finally {
            synchronized(lock) {
                // EOF asks the native supervisor (including a root supervisor) to
                // kill its own command group. App UID cannot signal root children.
                runCatching { child?.outputStream?.close() }
                runCatching { (child as? java.io.Closeable)?.close() }
                process = null
                mutableState.update { it.copy(running = false, stopped = stopRequested) }
            }
        }
    }

    fun stop() = synchronized(lock) {
        if (mutableState.value.running) {
            stopRequested = true
            runCatching { process?.outputStream?.close() }
        }
    }

    fun clear() { mutableState.update { it.copy(output = "", exitCode = null, error = null) } }
    fun close() = synchronized(lock) { closed = true; stop() }

    companion object {
        const val OUTPUT_LIMIT = 65536
        internal fun bounded(text: String): String = if (text.length <= OUTPUT_LIMIT) text else {
            var start = text.length - OUTPUT_LIMIT
            if (text[start].isLowSurrogate()) start++
            text.substring(start)
        }
    }
}
