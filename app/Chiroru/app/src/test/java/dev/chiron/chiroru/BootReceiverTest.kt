package dev.chiron.chiroru

import android.app.Application
import android.content.Intent
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
}
