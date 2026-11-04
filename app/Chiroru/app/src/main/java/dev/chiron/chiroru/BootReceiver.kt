package dev.chiron.chiroru

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED)) return
        BootAdb.syncMonitor(context)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (AppPreferences(context).bootEnabled) {
            context.startForegroundService(Intent(context, LoadService::class.java))
        }
    }
}
