package dev.chiron.chiroru

import android.app.Application
import android.os.Looper
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
class BootDelayTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()

    @Test fun frameworkRestartDoesNotReplayBootstrapButNewKernelBootDoes() {
        val first = "00000000-0000-4000-8000-000000000001"
        val next = "00000000-0000-4000-8000-000000000002"
        AppPreferences(application).recordBootAttempt(324, first)
        val reopened = AppPreferences(application)
        assertTrue(reopened.hasAttemptedBoot(324, first))
        assertTrue(reopened.hasAttemptedBoot(325, first))
        assertTrue(reopened.hasAttemptedBoot(-1, first))
        assertFalse(reopened.hasAttemptedBoot(324, next))
        assertFalse(reopened.hasAttemptedBoot(325, next))
        assertEquals(first, reopened.lastBootId)
    }

    @Test fun legacyPreferenceAndUnavailableBootIdUseAndroidBootCount() {
        val preferences = AppPreferences(application)
        preferences.lastBoot = 324
        assertTrue(preferences.hasAttemptedBoot(324, "current"))
        assertTrue(preferences.hasAttemptedBoot(324, null))
        assertFalse(preferences.hasAttemptedBoot(325, null))
        assertFalse(preferences.hasAttemptedBoot(-1, null))
        preferences.recordBootAttempt(324, "current")
        assertTrue(AppPreferences(application).hasAttemptedBoot(325, "current"))
    }

    @Test fun validatesBoundariesAndRejectsMalformedOrOverflowingInput() {
        assertEquals(0, BootDelay.parse("0"))
        assertEquals(86400, BootDelay.parse("86400"))
        listOf("", "-1", "86401", "1.5", "abc", "999999999999999").forEach {
            assertNull(it, BootDelay.parse(it))
        }
    }

    @Test fun lateBroadcastWaitsOnlyUntilBootRelativeTarget() {
        assertEquals(150_000L, BootDelay.remainingMillis(180, 30_000))
        assertEquals(0L, BootDelay.remainingMillis(180, 200_000))
        assertEquals(0L, BootDelay.remainingMillis(0, 0))
        assertEquals(86_400_000L, BootDelay.remainingMillis(86400, 0))
    }

    @Test fun delayPersistsInDirectBootStorageWithoutEnablingOrStartingAutoload() {
        val preferences = AppPreferences(application)
        assertEquals(180, preferences.bootDelaySeconds)
        preferences.bootDelaySeconds = 420
        assertEquals(420, AppPreferences(application.createDeviceProtectedStorageContext()).bootDelaySeconds)
        assertFalse(AppPreferences(application).bootEnabled)
        assertNull(shadowOf(application).nextStartedService)
        try {
            preferences.bootDelaySeconds = -1
            fail("Invalid delay accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(420, preferences.bootDelaySeconds)
    }

    @Test fun pendingServiceReplacesOldDeadlineWhenDelayChanges() {
        val preferences = AppPreferences(application)
        preferences.bootDelaySeconds = 60
        val controller = Robolectric.buildService(LoadService::class.java).create()
        val service = controller.get()
        // Exercise waiting/rescheduling only. Autoload stays disabled so reaching
        // the deadline stops the service before any firmware or native operation.
        LoadService::class.java.getDeclaredField("scheduled").apply { isAccessible = true }.set(service, true)
        LoadService::class.java.getDeclaredMethod("scheduleLoad").apply { isAccessible = true }.invoke(service)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofSeconds(10))
        preferences.bootDelaySeconds = 120
        looper.idle()
        looper.idleFor(Duration.ofSeconds(55))
        assertFalse(shadowOf(service).isStoppedBySelf)
        looper.idleFor(Duration.ofSeconds(55))
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
        preferences.bootDelaySeconds = 240
        looper.idle()
        assertNull(shadowOf(application).nextStartedService)
    }
}
