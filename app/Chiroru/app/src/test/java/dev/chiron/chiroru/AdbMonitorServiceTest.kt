package dev.chiron.chiroru

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Looper
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class AdbMonitorServiceTest {
    private val application get() = RuntimeEnvironment.getApplication()

    private fun permission(granted: Boolean) {
        val info = shadowOf(application.packageManager).getInternalMutablePackageInfo(application.packageName)
        val requested = checkNotNull(info.requestedPermissions)
        val index = requested.indexOf(Manifest.permission.WRITE_SECURE_SETTINGS)
        check(index >= 0)
        info.requestedPermissionsFlags = (info.requestedPermissionsFlags ?: IntArray(requested.size)).also {
            it[index] = if (granted) PackageInfo.REQUESTED_PERMISSION_GRANTED else 0
        }
    }

    private fun adb(value: Int) = Settings.Global.putInt(application.contentResolver, Settings.Global.ADB_ENABLED, value)
    private fun adb() = Settings.Global.getInt(application.contentResolver, Settings.Global.ADB_ENABLED)
    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Test fun startsImmediatelyAndRepairsAdbEveryThirtySecondsWithoutKernelSu() {
        permission(true)
        adb(0)
        Settings.Global.putInt(application.contentResolver, Settings.Global.DEVICE_PROVISIONED, 0)
        val controller = Robolectric.buildService(AdbMonitorService::class.java).create()
        try {
            assertFalse(AppPreferences(application).bootEnabled)
            assertEquals(Service.START_STICKY, controller.get().onStartCommand(Intent(), 0, 1))
            assertEquals(1, adb())
            repeat(2) {
                adb(0)
                advance(29)
                assertEquals(0, adb())
                advance(1)
                assertEquals(1, adb())
            }
            assertEquals(0, Settings.Global.getInt(application.contentResolver, Settings.Global.DEVICE_PROVISIONED))
        } finally { controller.destroy() }
    }

    @Test fun repeatedStartsDoNotDuplicateOrPostponeChecks() {
        permission(true)
        adb(0)
        val controller = Robolectric.buildService(AdbMonitorService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            advance(10)
            controller.get().onStartCommand(Intent(), 0, 2)
            adb(0)
            advance(20)
            assertEquals(1, adb())
            adb(0)
            advance(10)
            assertEquals(0, adb())
            advance(20)
            assertEquals(1, adb())
        } finally { controller.destroy() }
    }

    @Test fun destructionCancelsChecksWithoutDisablingAdb() {
        permission(true)
        adb(0)
        val controller = Robolectric.buildService(AdbMonitorService::class.java).create()
        controller.get().onStartCommand(Intent(), 0, 1)
        controller.destroy()
        assertEquals(1, adb())
        adb(0)
        advance(60)
        assertEquals(0, adb())
    }

    @Test fun missingOrRevokedPermissionStopsTheService() {
        permission(false)
        adb(0)
        val missing = Robolectric.buildService(AdbMonitorService::class.java).create()
        assertEquals(Service.START_NOT_STICKY, missing.get().onStartCommand(Intent(), 0, 1))
        assertTrue(shadowOf(missing.get()).isStoppedBySelf)
        assertEquals(0, adb())
        missing.destroy()

        permission(true)
        val controller = Robolectric.buildService(AdbMonitorService::class.java).create()
        try {
            // A null intent is Android restarting a previously active sticky service.
            assertEquals(Service.START_STICKY, controller.get().onStartCommand(null, 0, 1))
            assertEquals(1, adb())
            permission(false)
            adb(0)
            advance(30)
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            assertEquals(0, adb())
            permission(true)
            advance(60)
            assertEquals(0, adb())
        } finally { controller.destroy() }
    }
}
