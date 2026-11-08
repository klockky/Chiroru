package dev.chiron.chiroru

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.chiron.chiroru.firmware.FirmwareAssets
import dev.chiron.chiroru.firmware.FirmwareEnvironment
import dev.chiron.chiroru.firmware.ManagerSource
import dev.chiron.chiroru.firmware.ManagerSupport
import java.io.File
import java.security.MessageDigest

/** Read-only checks also work before root and during direct boot. */
internal enum class BundledManagerState { CHECKING, MISSING, READY, INCOMPATIBLE, UNAVAILABLE }

/** A paused foreground KSU request; returning from Android never starts root. */
internal data class ManagerConflict(
    val operation: Operation,
    val requirement: ManagerSupport,
    val status: BundledManagerState,
) {
    val canContinue: Boolean get() = status == BundledManagerState.READY ||
        (status == BundledManagerState.MISSING && requirement.source == ManagerSource.OFFICIAL_RELEASE)
}

internal object ManagerPackages {
    fun state(context: Context, requirement: ManagerSupport): BundledManagerState {
        val info = installed(context, requirement) ?: return BundledManagerState.MISSING
        return if (accepts(info, requirement)) BundledManagerState.READY else BundledManagerState.INCOMPATIBLE
    }

    fun installed(context: Context, requirement: ManagerSupport): PackageInfo? = try {
        context.packageManager.getPackageInfo(requirement.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.MATCH_DIRECT_BOOT_AWARE or
                PackageManager.MATCH_DIRECT_BOOT_UNAWARE)
    } catch (_: PackageManager.NameNotFoundException) { null }

    fun accepts(info: PackageInfo, requirement: ManagerSupport): Boolean = requirement.accepts(
        info.packageName,
        info.signingInfo?.apkContentsSigners?.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.orEmpty(), info.longVersionCode,
    )

    fun isCompatible(context: Context, requirement: ManagerSupport): Boolean =
        installed(context, requirement)?.let { accepts(it, requirement) } == true

    fun readyForLoad(context: Context, requirement: ManagerSupport): Boolean =
        installed(context, requirement)?.let { accepts(it, requirement) }
            ?: (requirement.source == ManagerSource.OFFICIAL_RELEASE)

    /** Recheck immediately before offering Android's user-confirmed uninstall. */
    fun uninstallIntent(context: Context, requirement: ManagerSupport): Intent? {
        val installed = installed(context, requirement) ?: return null
        check(!accepts(installed, requirement)) { "The installed manager is already compatible" }
        return Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${requirement.packageName}"))
    }

    /** Android's package installer runs before the exploit, with normal app credentials. */
    fun installIntent(context: Context): Intent {
        val selection = FirmwareEnvironment.inspect(context)
        check(selection.support.canLoadKernelSu) { "No exact firmware profile" }
        val profile = checkNotNull(selection.profile)
        val requirement = checkNotNull(profile.kernelSu).manager
        check(requirement.source == ManagerSource.BUNDLED) { "No bundled manager for this profile" }
        val installed = installed(context, requirement)
        check(installed == null || accepts(installed, requirement)) {
            "A different manager is installed; resolve the package/signature conflict first"
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"))
        }
        val file = File(FirmwareAssets(context.cacheDir) { context.assets.open(it) }.prepareManager(profile))
        val info = checkNotNull(context.packageManager.getPackageArchiveInfo(file.path,
            PackageManager.GET_SIGNING_CERTIFICATES)) { "Unreadable bundled manager APK" }
        check(accepts(info, requirement)) { "Bundled manager identity mismatch" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.manager", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
