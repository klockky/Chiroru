package dev.chiron.chiroru

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/** Keep Android's ADB transport enabled using the package's settings permission. */
internal object BootAdb {
    const val GRANT_COMMAND = "adb shell pm grant dev.chiron.chiroru android.permission.WRITE_SECURE_SETTINGS"

    fun hasPermission(context: Context): Boolean {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val index = info.requestedPermissions?.indexOf(Manifest.permission.WRITE_SECURE_SETTINGS) ?: -1
        val flags = info.requestedPermissionsFlags?.getOrNull(index) ?: 0
        return flags and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
    }

    fun grantAfterRoot(context: Context, outcome: Outcome): String {
        if (outcome !in setOf(Outcome.ROOT_READY, Outcome.ADB_ROOT_READY, Outcome.MODULE_LOADED,
                Outcome.MODULE_HOOKS_FAILED)) return ""
        return try {
            // Check the package grant, not this thread's temporary UID 0 privileges.
            if (hasPermission(context)) return ""
            val report = NativeBridge.grantBootAdbPermission()
            report + if (hasPermission(context)) "boot_adb: permission granted\n"
                else "boot_adb: permission unavailable; grant WRITE_SECURE_SETTINGS manually\n"
        } catch (error: Exception) {
            "boot_adb: permission grant failed: $error\n"
        } finally {
            syncMonitor(context)
        }
    }

    fun syncMonitor(context: Context) {
        try {
            val intent = Intent(context, AdbMonitorService::class.java)
            if (AppPreferences(context).adbOnBoot && hasPermission(context)) {
                context.startForegroundService(intent)
            } else {
                context.stopService(intent)
            }
        } catch (error: Exception) {
            // A settings/provider failure must not prevent KernelSU boot loading.
            Log.e(Loader.TAG, "ADB monitor: cannot update service", error)
        }
    }

    fun ensureEnabled(context: Context): Boolean {
        return try {
            check(hasPermission(context)) { "WRITE_SECURE_SETTINGS permission is missing" }
            val resolver = context.contentResolver
            if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) != 1) {
                check(Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)) { "ADB setting write failed" }
                Log.i(Loader.TAG, "ADB monitor: adb_enabled=1")
            }
            check(Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1) { "ADB setting was not enabled" }
            true
        } catch (error: Exception) {
            Log.e(Loader.TAG, "ADB monitor: cannot enable ADB", error)
            false
        }
    }
}
