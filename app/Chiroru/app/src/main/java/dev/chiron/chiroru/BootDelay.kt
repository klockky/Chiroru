package dev.chiron.chiroru

internal object BootDelay {
    const val DEFAULT_SECONDS = 180
    const val MAX_SECONDS = 86_400

    fun parse(text: String): Int? = text.toIntOrNull()?.takeIf { it in 0..MAX_SECONDS }

    /** Target is measured from boot, not from receipt of a possibly late broadcast. */
    fun remainingMillis(seconds: Int, elapsedMillis: Long): Long {
        require(seconds in 0..MAX_SECONDS)
        return (seconds * 1_000L - elapsedMillis.coerceAtLeast(0)).coerceAtLeast(0)
    }
}
