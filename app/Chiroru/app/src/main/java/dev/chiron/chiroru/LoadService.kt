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
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.util.Log
import dev.chiron.chiroru.firmware.FirmwareEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** User-defined boot delay; native calls retain their main-thread affinity. */
class LoadService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var loading = false
    private var stopObservingDelay: (() -> Unit)? = null
    private val work = Runnable { load() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        stopObservingDelay = AppPreferences(this).observeBootDelay {
            if (scheduled && !loading) scheduleLoad()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        val preferences = AppPreferences(this)
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val bootId = BootSession.currentId()
        val alreadyAttempted = preferences.hasAttemptedBoot(boot, bootId)
        // BOOT_COMPLETED can arrive while LOCKED_BOOT_COMPLETED's run is still
        // in flight; by then recordBootAttempt() has marked this boot as
        // attempted. Stopping here would cancel the coroutine that is about to
        // persist the report and would abort an otherwise successful load.
        if (loading) return START_NOT_STICKY
        if (alreadyAttempted && preferences.lastBootId == null && bootId != null) {
            // Upgrade an attempt made by an older Chiroru in this same Android boot.
            preferences.recordBootAttempt(boot, bootId)
        }
        if (!preferences.bootEnabled || alreadyAttempted ||
            !FirmwareEnvironment.inspect(this).support.canLoadKernelSu) {
            if (alreadyAttempted) Log.i(Loader.TAG, "Boot load skipped: kernel boot already attempted ($bootId)")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!scheduled) {
            scheduled = true
            scheduleLoad()
        }
        return START_NOT_STICKY
    }

    private fun scheduleLoad() {
        handler.removeCallbacks(work)
        val seconds = AppPreferences(this).bootDelaySeconds
        val delay = BootDelay.remainingMillis(seconds, SystemClock.elapsedRealtime())
        Log.i(Loader.TAG, "Boot load target=${seconds}s, remaining=${delay}ms")
        getSystemService(NotificationManager::class.java).notify(1, notification())
        handler.postDelayed(work, delay)
    }

    private fun notification(text: String = getString(R.string.notification_waiting,
        AppPreferences(this).bootDelaySeconds)): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            "boot", getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW,
        ))
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, "boot")
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        // Bootstrap patches shared credentials, including pre-created workers.
        // Android only accepts a package notification from its original UID.
        if (Os.getuid() != applicationInfo.uid) return
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    private fun load() {
        if (loading) return
        val preferences = AppPreferences(this)
        if (!preferences.bootEnabled || Loader.kernelTouched) {
            stopSelf()
            return
        }
        loading = true
        updateNotification(getString(R.string.running))
        // Persist before entering native code; the second boot broadcast must not repeat it.
        preferences.recordBootAttempt(
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1), BootSession.currentId())
        scope.launch {
            val report = StringBuilder()
            val started = SystemClock.elapsedRealtime()
            try {
                val (kernelSu, adbRoot) = withContext(Dispatchers.IO) {
                    RuntimeDetector.kernelSu() to RuntimeDetector.adbRoot()
                }
                Loader.runtime = RuntimeStatus(RuntimeDetector.processRoot(), kernelSu, adbRoot)
                AppIo.prepare(this@LoadService)
                var lastReport = ""
                for (attempt in 1..3) {
                    lastReport = Loader.run(this@LoadService, Operation.MODULE)
                    report.append("===== run $attempt =====\n$lastReport\n")
                    if (!ReportResult.parse(Operation.MODULE, lastReport).canRetry(lastReport)) break
                    if (attempt < 3) delay(3_000)
                }
                // Persist the native report before any network operation.
                AppIo.writeBootReport(report.toString())
                if (ManagerInstaller.shouldRun(Operation.MODULE, lastReport)) {
                    val manager = ManagerInstaller.ensureInstalled(this@LoadService) { step ->
                        updateNotification(getString(step.messageResource))
                    }
                    report.append(manager.report)
                }
            } catch (error: Exception) {
                Log.e(Loader.TAG, "Boot loading failed", error)
                report.append("Boot loading failed: $error\n")
            } finally {
                report.append("started ${started / 1000.0}s, finished ${SystemClock.elapsedRealtime() / 1000.0}s uptime\n")
                // stopSelf()/onDestroy may cancel this scope at any moment;
                // the final report is the only record of a boot attempt.
                runCatching { withContext(NonCancellable) { AppIo.writeBootReport(report.toString()) } }
                    .onFailure { Log.e(Loader.TAG, "Cannot save boot report", it) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        stopObservingDelay?.invoke()
        handler.removeCallbacks(work)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
