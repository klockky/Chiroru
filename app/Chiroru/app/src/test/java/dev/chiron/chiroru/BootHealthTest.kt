package dev.chiron.chiroru

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BootHealthTest {
    @Test fun waitsForAndroidCompletionAndThenAFullStabilityWindow() = runBlocking {
        var time = 0L
        assertTrue(BootHealth.await({ time >= 20_000 }, { time }, { time += it }))
        assertEquals(80_000L, time)
    }

    @Test fun alreadyCompletedBootStillNeedsSixtySecondsAfterRoot() = runBlocking {
        var time = 500_000L
        assertTrue(BootHealth.await({ true }, { time }, { time += it }))
        assertEquals(560_000L, time)
    }

    @Test fun frameworkCompletionDroppingResetsTheWindow() = runBlocking {
        var time = 0L
        assertTrue(BootHealth.await({ time !in 30_000..39_999 }, { time }, { time += it }))
        assertEquals(100_000L, time)
    }

    @Test fun missingOrTooLateCompletionTimesOutWithoutConfirmation() = runBlocking {
        for (completion in listOf(Long.MAX_VALUE, 280_000L)) {
            var time = 0L
            assertFalse(BootHealth.await({ time >= completion }, { time }, { time += it }))
            assertEquals(BootHealth.TIMEOUT_MILLIS, time)
        }
    }

    @Test fun cancellationDoesNotProduceAHealthyResult() = runBlocking {
        try {
            BootHealth.await({ true }, { 0L }, { throw CancellationException("service stopped") })
            fail("Cancelled observation must not confirm startup")
        } catch (_: CancellationException) { }
    }
}
