package dev.chiron.chiroru

import android.content.Context
import android.os.Looper
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext

/** Keep private write handles open across JNI's credential change. Android 11
 * forbids app setuid calls; normal thread creation alone does not isolate creds. */
internal object AppIo {
    private var prepared: ExecutorCoroutineDispatcher? = null
    val dispatcher: ExecutorCoroutineDispatcher get() = checkNotNull(prepared) { "App IO was not prepared before root" }
    private var downloadHandle: RandomAccessFile? = null
    private var reportHandle: RandomAccessFile? = null
    private var apkReadDescriptor: ParcelFileDescriptor? = null
    val managerApk: File get() = File("/proc/self/fd/${checkNotNull(apkReadDescriptor).fd}")

    fun prepare(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (prepared == null) {
            val storage = Loader.storage(context)
            val directory = File(storage.cacheDir, "manager").apply {
                check(isDirectory || mkdirs()) { "Cannot create manager download directory" }
            }
            val apk = File(directory, "KernelSU-Next.apk")
            val download = RandomAccessFile(apk, "rw")
            val readDescriptor = try {
                // Read access on the file itself is enough for /proc/self/fd/N.
                // The original app-private directory is never walked again.
                check(apk.setReadable(true, false))
                ParcelFileDescriptor.dup(download.fd)
            } catch (error: Exception) {
                download.close()
                throw error
            }
            val report = try {
                download.setLength(0)
                RandomAccessFile(Loader.reportFile(context), "rw")
            } catch (error: Exception) {
                readDescriptor.close()
                download.close()
                throw error
            }
            val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue()) {
                Thread(it, "Chiroru-io").apply { isDaemon = true }
            }
            executor.prestartCoreThread()
            // PackageManager's archive parser takes a path. A path to an owned
            // descriptor works even when the original parent is not searchable.
            apkReadDescriptor = readDescriptor
            downloadHandle = download
            reportHandle = report
            prepared = executor.asCoroutineDispatcher()
        }
    }

    fun clearManagerDownload(): RandomAccessFile = checkNotNull(downloadHandle).apply {
        setLength(0)
        seek(0)
    }

    suspend fun writeBootReport(text: String) = withContext(dispatcher) {
        checkNotNull(reportHandle).apply {
            setLength(0)
            seek(0)
            write(text.toByteArray(Charsets.UTF_8))
        }
        Unit
    }

    suspend fun readBootReport(context: Context): String = withContext(prepared ?: Dispatchers.IO) {
        val handle = reportHandle
        if (handle != null) {
            handle.seek(0)
            check(handle.length() <= 1024 * 1024) { "Boot report exceeds size limit" }
            val bytes = ByteArray(handle.length().toInt())
            handle.readFully(bytes)
            bytes.toString(Charsets.UTF_8)
        } else {
            val file = Loader.reportFile(context)
            if (file.exists()) file.readText() else ""
        }
    }
}
