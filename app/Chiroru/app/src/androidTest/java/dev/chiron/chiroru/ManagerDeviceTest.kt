package dev.chiron.chiroru

import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** These preflight checks never request root or install/delete another package. */
@RunWith(AndroidJUnit4::class)
class ManagerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before fun prepareNormalAppIo() {
        instrumentation.runOnMainSync { AppIo.prepare(context) }
    }

    @Test fun normalAppIoCanAccessPrivateFiles() = runBlocking {
        withContext(AppIo.dispatcher) {
            assertEquals(context.applicationInfo.uid, Os.getuid())
            val file = java.io.File.createTempFile("chiroru-io-", ".tmp", Loader.storage(context).cacheDir)
            try {
                file.writeText("private IO works")
                assertEquals("private IO works", file.readText())
            } finally { file.delete() }
        }
    }

    @Test fun installedManagerIsDetectedWithoutRoot() = runBlocking {
        val installed = try {
            context.packageManager.getPackageInfo(ManagerReleases.PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
        assumeTrue("This check needs an existing manager", installed)
        val states = mutableListOf<ManagerStep>()
        val result = ManagerInstaller.ensureInstalled(context) { states += it }
        assertEquals(ManagerStep.ALREADY_INSTALLED, result.step)
        assertEquals(listOf(ManagerStep.CHECKING, ManagerStep.ALREADY_INSTALLED), states)
    }

    @Test fun downloadedApkCanBeVerifiedAndStreamedWithoutDirectoryAccess() = runBlocking {
        val backend = ManagerInstaller.AndroidBackend(context, AppIo.dispatcher)
        val downloaded = backend.download()
        val directory = java.io.File(Loader.storage(context).cacheDir, "manager")
        val diskFile = java.io.File(directory, "KernelSU-Next.apk")
        val permissions = Os.stat(directory.absolutePath).st_mode and 0xFFF
        try {
            Os.chmod(directory.absolutePath, 0)
            // Reproduce the post-root EACCES without requesting root or touching
            // another package's files: the original private path cannot be used.
            assertThrows(java.io.IOException::class.java) { diskFile.inputStream().use { it.read() }; Unit }
            backend.verify(downloaded)
            val sink = object : java.io.OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(buffer: ByteArray, offset: Int, count: Int) = Unit
            }
            val bytes = downloaded.file.inputStream().use { it.copyTo(sink) }
            assertEquals(downloaded.release.size, bytes)
            assertTrue(Build.VERSION.SDK_INT >= 30)
        } finally {
            Os.chmod(directory.absolutePath, permissions)
            backend.remove(downloaded.file)
        }
    }
}
