package dev.chiron.chiroru

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import dev.chiron.chiroru.firmware.FirmwareEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ManagerPackagesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val certificate = Signature(byteArrayOf(1, 2, 3, 4))
    private fun requirement(factory: Boolean = false) = FirmwareEnvironment.parseCatalog(
        context.assets.open("firmware/profiles.json").bufferedReader().use { it.readText() }
    ).profiles.single { it.id == if (factory) "chiron-1982735225" else "chiron-5000001388" }.kernelSu!!.manager.copy(
        signerSha256 = MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
            .joinToString("") { "%02x".format(it) })

    private fun install(version: Long, signer: Signature = certificate) {
        val info = PackageInfo().apply {
            packageName = "com.rifsxd.ksunext"
            setLongVersionCode(version)
            signingInfo = Shadow.newInstanceOf(SigningInfo::class.java).also {
                shadowOf(it).setSignatures(arrayOf(signer))
            }
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    @Test fun wrongVersionAndWrongSignerOfferOnlyTargetedSystemUninstall() {
        val requirement = requirement()
        for ((version, signer) in listOf(requirement.versionCode + 1 to certificate,
            requirement.versionCode to Signature(byteArrayOf(5, 6)))) {
            install(version, signer)
            assertEquals(BundledManagerState.INCOMPATIBLE, ManagerPackages.state(context, requirement))
            val intent = ManagerPackages.uninstallIntent(context, requirement)!!
            assertEquals(Intent.ACTION_UNINSTALL_PACKAGE, intent.action)
            assertEquals("package:com.rifsxd.ksunext", intent.dataString)
            // Offering/cancelling the system dialog does not remove or approve the package.
            assertFalse(ManagerPackages.readyForLoad(context, requirement))
            assertFalse(ManagerConflict(Operation.MODULE, requirement,
                ManagerPackages.state(context, requirement)).canContinue)
        }
    }

    @Test fun successfulRemovalAllowsOfficialFlowButFactoryStillNeedsItsBundledManager() {
        for (factory in listOf(false, true)) {
            val requirement = requirement(factory)
            install(requirement.versionCode + 1)
            shadowOf(context.packageManager).removePackage(requirement.packageName)
            assertNull(ManagerPackages.uninstallIntent(context, requirement))
            assertEquals(!factory, ManagerPackages.readyForLoad(context, requirement))
            assertEquals(!factory, ManagerConflict(Operation.MODULE_SAFE, requirement,
                ManagerPackages.state(context, requirement)).canContinue)
            install(requirement.versionCode)
            assertTrue(ManagerPackages.readyForLoad(context, requirement))
        }
    }

    @Test fun compatibleReplacementIsRecheckedBeforeUninstallIntent() {
        val requirement = requirement()
        install(requirement.versionCode + 1)
        assertNotNull(ManagerPackages.uninstallIntent(context, requirement))
        install(requirement.versionCode)
        assertThrows(IllegalStateException::class.java) { ManagerPackages.uninstallIntent(context, requirement) }
        assertTrue(ManagerPackages.readyForLoad(context, requirement))
    }
}
