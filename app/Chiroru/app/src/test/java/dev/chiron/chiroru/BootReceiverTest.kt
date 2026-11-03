package dev.chiron.chiroru

import android.app.Application
import android.content.Intent
import android.Manifest
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BootReceiverTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()

    private fun adbPermission(granted: Boolean) {
        val info = shadowOf(application.packageManager).getInternalMutablePackageInfo(application.packageName)
        val requested = checkNotNull(info.requestedPermissions)
        val index = requested.indexOf(Manifest.permission.WRITE_SECURE_SETTINGS)
        assertTrue(index >= 0)
        info.requestedPermissionsFlags = (info.requestedPermissionsFlags
            ?: IntArray(requested.size)).also {
            it[index] = if (granted) android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED else 0
        }
    }

    @Test fun bootLoadingIsDisabledByDefault() {
        assertFalse(AppPreferences(application).bootEnabled)
        BootReceiver().onReceive(application, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test fun enabledBootLoadingStartsThePrivateService() {
        AppPreferences(application).bootEnabled = true
        BootReceiver().onReceive(application, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(LoadService::class.java.name, shadowOf(application).nextStartedService.component?.className)
    }

    @Test fun unrelatedBroadcastDoesNotStartTheLoader() {
        AppPreferences(application).bootEnabled = true
        BootReceiver().onReceive(application, Intent(Intent.ACTION_USER_PRESENT))
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test fun adbIsOptInEvenWithPermission() {
        adbPermission(true)
        Settings.Global.putInt(application.contentResolver, Settings.Global.ADB_ENABLED, 0)
        assertFalse(AppPreferences(application).adbOnBoot)
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(0, Settings.Global.getInt(application.contentResolver, Settings.Global.ADB_ENABLED))
    }

    @Test fun adbStartsWithoutKernelSuOnBothBootBroadcasts() {
        adbPermission(true)
        AppPreferences(application).adbOnBoot = true
        Settings.Global.putInt(application.contentResolver, Settings.Global.DEVICE_PROVISIONED, 0)
        for (action in listOf(Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED)) {
            Settings.Global.putInt(application.contentResolver, Settings.Global.ADB_ENABLED, 0)
            BootReceiver().onReceive(application, Intent(action))
            assertEquals(1, Settings.Global.getInt(application.contentResolver, Settings.Global.ADB_ENABLED))
            assertNull(shadowOf(application).nextStartedService)
            assertFalse(AppPreferences(application).bootEnabled)
        }
        assertEquals(0, Settings.Global.getInt(application.contentResolver, Settings.Global.DEVICE_PROVISIONED))
    }

    @Test fun missingAdbPermissionDoesNotBlockKernelSuAndCanBeRetried() {
        val preferences = AppPreferences(application)
        preferences.adbOnBoot = true
        preferences.bootEnabled = true
        adbPermission(false)
        Settings.Global.putInt(application.contentResolver, Settings.Global.ADB_ENABLED, 0)
        BootReceiver().onReceive(application, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(0, Settings.Global.getInt(application.contentResolver, Settings.Global.ADB_ENABLED))
        assertEquals(LoadService::class.java.name, shadowOf(application).nextStartedService.component?.className)
        assertTrue(preferences.adbOnBoot)
        adbPermission(true)
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, Settings.Global.getInt(application.contentResolver, Settings.Global.ADB_ENABLED))
    }

    @Test fun unrelatedBroadcastCannotEnableAdb() {
        adbPermission(true)
        AppPreferences(application).adbOnBoot = true
        Settings.Global.putInt(application.contentResolver, Settings.Global.ADB_ENABLED, 0)
        BootReceiver().onReceive(application, Intent(Intent.ACTION_USER_PRESENT))
        assertEquals(0, Settings.Global.getInt(application.contentResolver, Settings.Global.ADB_ENABLED))
    }

    @Test fun grantIsSkippedForProbeFailureAndAnExistingPackagePermission() {
        adbPermission(false)
        assertEquals("", BootAdb.grantAfterRoot(application, Outcome.ERROR))
        assertEquals("", BootAdb.grantAfterRoot(application, Outcome.FINISHED))
        adbPermission(true)
        // A native call here would fail to load the Android library on the host.
        assertEquals("", BootAdb.grantAfterRoot(application, Outcome.ROOT_READY))
    }
}
