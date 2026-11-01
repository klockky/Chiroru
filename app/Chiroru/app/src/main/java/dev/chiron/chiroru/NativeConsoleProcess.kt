package dev.chiron.chiroru

import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

/** Existing app-owned pipes, usable even when bootstrap credentials forbid exec. */
internal class NativeConsoleProcess(command: String) : Process(), java.io.Closeable {
    private val descriptors = checkNotNull(NativeBridge.startProcessConsole(command.toByteArray(Charsets.UTF_8))) {
        "Process root console is unavailable"
    }
    private val output = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(descriptors[0]))
    private val control = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(descriptors[1]))
    private val completion = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(descriptors[2])))
    @Volatile private var result: Int? = null

    override fun getInputStream(): InputStream = output
    override fun getOutputStream(): OutputStream = control
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    @Synchronized override fun waitFor(): Int = result ?: completion.use { it.readInt() }.also { result = it }
    override fun exitValue(): Int = result ?: throw IllegalThreadStateException("Command is running")
    override fun destroy() { control.close() }
    override fun close() {
        runCatching { control.close() }
        runCatching { output.close() }
        runCatching { completion.close() }
    }
}
