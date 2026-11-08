package dev.chiron.chiroru

import kotlinx.coroutines.delay

internal object BootHealth {
    const val STABLE_MILLIS = 60_000L
    const val TIMEOUT_MILLIS = 300_000L

    /** Observe completion continuously after the root operation, with a bounded wait. */
    suspend fun await(
        completed: () -> Boolean,
        elapsed: () -> Long,
        pause: suspend (Long) -> Unit = { delay(it) },
    ): Boolean {
        val started = elapsed()
        var stableSince: Long? = null
        while (true) {
            val now = elapsed()
            if (now - started >= TIMEOUT_MILLIS) return false
            if (completed()) {
                val since = stableSince ?: now.also { stableSince = it }
                if (now - since >= STABLE_MILLIS) return true
            } else stableSince = null
            pause(1_000)
        }
    }
}
