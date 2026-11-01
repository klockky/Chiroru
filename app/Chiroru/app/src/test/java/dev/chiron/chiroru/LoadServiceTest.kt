package dev.chiron.chiroru

import android.app.Notification
import android.app.NotificationManager
import android.os.Process
import android.system.Os
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [LoadServiceTest.IdentityOs::class])
class LoadServiceTest {
    // Robolectric's default Linux shadow does not implement Os.getuid().
    @Implements(Os::class)
    class IdentityOs {
        companion object {
            @Implementation @JvmStatic fun getuid(): Int = Process.myUid()
        }
    }

    @Test fun progressNotificationIsNotPostedAfterBootstrapChangesUid() {
        val application = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(LoadService::class.java).create()
        val service = controller.get()
        val update = LoadService::class.java.getDeclaredMethod("updateNotification", String::class.java)
            .apply { isAccessible = true }
        val manager = shadowOf(application.getSystemService(NotificationManager::class.java))
        ShadowProcess.setUid(application.applicationInfo.uid)
        try {
            update.invoke(service, "Running")
            assertEquals("Running", manager.getNotification(1).extras.getString(Notification.EXTRA_TEXT))
            ShadowProcess.setUid(0)
            update.invoke(service, "Checking manager")
            assertEquals("Running", manager.getNotification(1).extras.getString(Notification.EXTRA_TEXT))
        } finally {
            ShadowProcess.setUid(application.applicationInfo.uid)
            controller.destroy()
        }
    }
}
