package dev.chiron.chiroru

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

internal class AppPreferences(context: Context) {
    private val preferences = Loader.storage(context).getSharedPreferences("chiroru", Context.MODE_PRIVATE)

    // The separate application can coexist with the legacy loader. Enable explicitly.
    var bootEnabled: Boolean
        get() = preferences.getBoolean("boot_enabled", false)
        set(value) { preferences.edit { putBoolean("boot_enabled", value) } }

    var adbOnBoot: Boolean
        get() = preferences.getBoolean("adb_on_boot", false)
        set(value) { preferences.edit { putBoolean("adb_on_boot", value) } }

    var bootDelaySeconds: Int
        get() = preferences.getInt("boot_delay_seconds", BootDelay.DEFAULT_SECONDS)
            .takeIf { it in 0..BootDelay.MAX_SECONDS } ?: BootDelay.DEFAULT_SECONDS
        set(value) {
            require(value in 0..BootDelay.MAX_SECONDS)
            preferences.edit { putInt("boot_delay_seconds", value) }
        }

    /** Only an already waiting service observes changes; editing never starts a service. */
    fun observeBootDelay(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "boot_delay_seconds") onChange()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var theme: String
        get() = preferences.getString("theme", "system") ?: "system"
        set(value) { preferences.edit { putString("theme", value) } }

    var rootMethod: RootMethod
        get() = RootMethod.fromId(preferences.getString("root_method", null))
        set(value) { preferences.edit { putString("root_method", value.id) } }

    var lastBoot: Int
        get() = preferences.getInt("last_boot", -1)
        // Flush before JNI: a process termination must not replay this boot's load.
        set(value) { preferences.edit(commit = true) { putInt("last_boot", value) } }

    val lastBootId: String? get() = preferences.getString("last_boot_id", null)

    fun hasAttemptedBoot(boot: Int, bootId: String?): Boolean {
        val previousId = lastBootId
        return if (bootId != null && previousId != null) bootId == previousId
            else boot >= 0 && lastBoot == boot
    }

    fun recordBootAttempt(boot: Int, bootId: String?) {
        // One synchronous transaction before JNI: never replay after a process
        // death or a framework-only restart with a new Android BOOT_COUNT.
        preferences.edit(commit = true) {
            putInt("last_boot", boot)
            putString("last_boot_id", bootId)
        }
    }
}
