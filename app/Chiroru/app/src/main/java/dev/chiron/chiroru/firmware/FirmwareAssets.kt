package dev.chiron.chiroru.firmware

import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

/** Profile-scoped, always refreshed; an invalid new payload never replaces a valid cached one. */
internal class FirmwareAssets(
    private val filesDir: File,
    private val openAsset: (String) -> InputStream,
) {
    fun prepareManager(profile: FirmwareProfile): String {
        val artifact = checkNotNull(profile.kernelSu?.manager?.apk) { "No bundled manager for this profile" }
        val directory = File(filesDir, "firmware/${profile.id}")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create firmware directory" }
        return install(directory, "manager.apk", artifact, executable = false).absolutePath
    }

    fun prepare(profile: FirmwareProfile): Pair<String, String> {
        val support = checkNotNull(profile.kernelSu) { "KernelSU is not supported by this firmware profile" }
        val directory = File(filesDir, "firmware/${profile.id}")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create firmware directory" }
        val module = install(directory, "kernelsu.ko", support.module, executable = false)
        val ksud = install(directory, "ksud", support.ksud, executable = true)
        return module.absolutePath to ksud.absolutePath
    }

    fun prepareAdb(profile: FirmwareProfile): Pair<String, String> {
        val support = checkNotNull(profile.adbRoot) { "ADB root is not supported by this firmware profile" }
        val directory = File(filesDir, "firmware/${profile.id}")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create firmware directory" }
        val module = install(directory, "chiroru_adb.ko", support.module, executable = false)
        val loader = install(directory, "adb-loader", support.loader, executable = true)
        install(directory, "chiroru_adb.ko.client", support.client, executable = false)
        return module.absolutePath to loader.absolutePath
    }

    private fun install(directory: File, name: String, artifact: FirmwareArtifact, executable: Boolean): File {
        val file = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(openAsset(artifact.asset), digest).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == artifact.sha256) { "Firmware asset hash mismatch: ${artifact.asset}" }
            if (executable) check(temporary.setExecutable(true, true)) { "Cannot make $name executable" }
            try {
                Files.move(temporary.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return file
        } finally {
            temporary.delete()
        }
    }
}
