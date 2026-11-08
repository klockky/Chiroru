package dev.chiron.chiroru

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class BootGuardStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val first = "id:00000000-0000-4000-8000-000000000001"
    private val next = "id:00000000-0000-4000-8000-000000000002"
    private val third = "id:00000000-0000-4000-8000-000000000003"
    private val file: File get() = File(temporary.root, "guard")
    private fun open() = BootGuardStore(file) {}

    @Test fun interruptedAttemptSurvivesProcessDeathAndBlocksEverySubsequentBoot() {
        open().use {
            assertTrue(it.begin(first))
            assertFalse(it.begin(first)) // Duplicate boot broadcast.
            assertFalse(it.confirm(next)) // Wrong session cannot acknowledge an attempt.
        }
        open().use {
            assertFalse(it.canStart(first)) // Process/framework restart.
            assertFalse(it.canStart(next))
            assertFalse(it.canStart(third))
        }
    }

    @Test fun confirmedBootAllowsOnlyTheNextKernelBoot() {
        open().use {
            assertTrue(it.begin(first))
            assertTrue(it.confirm(first))
        }
        open().use {
            assertFalse(it.begin(first))
            assertTrue(it.begin(next))
        }
    }

    @Test fun explicitRecoveryDoesNotLaunchAgainUntilAnotherBoot() {
        open().use { assertTrue(it.begin(first)) }
        open().use {
            assertTrue(it.resume(next))
            assertFalse(it.canStart(next))
            assertFalse(it.confirm(first))
        }
        open().use { assertTrue(it.begin(third)) }
    }

    @Test fun everyTruncationAndSingleByteCorruptionFailsClosed() {
        open().use { assertTrue(it.begin(first)) }
        val original = file.readBytes()
        for (size in 0 until original.size) {
            file.writeBytes(original.copyOf(size))
            open().use { assertFalse("Truncation at $size", it.canStart(next)) }
        }
        for (index in original.indices) {
            val bytes = original.copyOf()
            bytes[index] = (bytes[index].toInt() xor 1).toByte()
            file.writeBytes(bytes)
            open().use { assertNull("Corruption at $index", it.read()) }
        }
        open().use {
            assertTrue(it.resume(next))
            assertFalse(it.canStart(next))
            assertTrue(it.canStart(third))
        }
    }

    @Test fun failedSyncNeverAuthorizesRootAndCanBeRecoveredExplicitly() {
        var failSync = false
        BootGuardStore(file, syncFile = {
            if (failSync) throw IOException("disk failure")
            it.fd.sync()
        }) {}.use {
            failSync = true
            assertFalse(it.begin(first))
            assertFalse(it.canStart(next))
            assertFalse(it.resume(next))
            failSync = false
            assertTrue(it.resume(next))
            assertTrue(it.canStart(third))
        }
    }

    @Test fun failedDirectorySyncCannotOpenAUsableGuard() {
        try {
            BootGuardStore(file) { throw IOException("directory sync failure") }
            fail("An unsynced directory must not authorize root")
        } catch (_: IOException) { }
    }

    @Test fun unknownBootIdentityFailsClosedAndBootCountFallbackStillDeduplicates() {
        open().use {
            assertFalse(it.canStart(null))
            assertFalse(it.begin("unknown"))
            assertTrue(it.begin("count:42"))
            assertTrue(it.confirm("count:42"))
        }
        open().use {
            assertFalse(it.canStart("count:42"))
            assertTrue(it.canStart("count:43"))
        }
    }

    @Test fun retainedDescriptorCanConfirmWithoutWalkingOriginalPrivatePath() {
        open().use {
            assertTrue(it.begin(first))
            val moved = File(temporary.root, "moved")
            assertTrue(file.renameTo(moved))
            assertTrue(it.confirm(first))
            BootGuardStore(moved) {}.use { reopened ->
                assertFalse(reopened.canStart(first))
                assertTrue(reopened.canStart(next))
            }
        }
    }
}
