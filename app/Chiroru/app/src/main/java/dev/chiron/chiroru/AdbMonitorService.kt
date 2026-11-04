package dev.chiron.chiroru

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Runs under the app UID in its own process, independently of native bootstrap.
 * The main process owns preferences and starts/stops this service explicitly;
 * do not read its cached SharedPreferences from this process. */
class AdbMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var monitoring = false
    private val checkAdb = object : Runnable {
        override fun run() {
            if (!monitoring) return
            if (!BootAdb.hasPermission(this@AdbMonitorService)) {
                monitoring = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            BootAdb.ensureEnabled(this@AdbMonitorService)
            handler.postDelayed(this, INTERVAL_MILLIS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        if (!monitoring) {
            monitoring = true
            checkAdb.run()
        }
        // Android restarts an interrupted monitor. Explicit stopService on
        // opt-out cancels sticky restarts, without changing adb_enabled.
        return if (monitoring) START_STICKY else START_NOT_STICKY
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.adb_monitor_channel), NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.adb_monitor_notification))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        monitoring = false
        handler.removeCallbacks(checkAdb)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val INTERVAL_MILLIS = 30_000L
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL = "adb_monitor"
    }
}
