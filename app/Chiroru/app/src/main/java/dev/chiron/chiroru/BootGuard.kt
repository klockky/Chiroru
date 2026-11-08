package dev.chiron.chiroru

import android.content.Context
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

internal enum class BootGuardStatus { READY, CHECKING, PAUSED, UNAVAILABLE }

/** Accessed in the main app process; the independent ADB process does not use this store. */
internal object BootGuard {
    private var store: BootGuardStore? = null
    private var active = false
    private var identityAvailable = true
    private val mutableStatus = MutableStateFlow(BootGuardStatus.READY)
    val status = mutableStatus.asStateFlow()

    fun bootToken(context: Context): String? = BootSession.currentId()?.let { "id:$it" }
        ?: runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrNull()
            ?.takeIf { it >= 0 }?.let { "count:$it" }

    @Synchronized fun prepare(context: Context) {
        identityAvailable = bootToken(context) != null
        if (store == null) store = runCatching {
            val directory = Loader.storage(context).filesDir
            BootGuardStore(File(directory, "boot-guard")) {
                val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
                try { Os.fsync(fd) } finally { Os.close(fd) }
            }
        }.getOrNull()
        refresh()
    }

    @Synchronized fun canStart(boot: String?) = !active && store?.canStart(boot) == true

    @Synchronized fun begin(boot: String): Boolean {
        if (active) return false
        active = store?.begin(boot) == true
        refresh()
        return active
    }

    @Synchronized fun confirm(boot: String): Boolean {
        val saved = active && store?.confirm(boot) == true
        if (saved) active = false
        refresh()
        return saved
    }

    @Synchronized fun finish() {
        active = false
        refresh()
    }

    @Synchronized fun resume(context: Context): Boolean {
        if (active) return false
        prepare(context)
        val boot = bootToken(context) ?: return false
        val saved = store?.resume(boot) == true
        refresh()
        return saved
    }

    private fun refresh() {
        val record = store?.read()
        mutableStatus.value = when {
            record == null || !identityAvailable -> BootGuardStatus.UNAVAILABLE
            active -> BootGuardStatus.CHECKING
            record.pending -> BootGuardStatus.PAUSED
            else -> BootGuardStatus.READY
        }
    }
}
