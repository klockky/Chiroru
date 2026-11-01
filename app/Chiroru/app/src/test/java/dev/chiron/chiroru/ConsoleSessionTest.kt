package dev.chiron.chiroru

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConsoleSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun processRootFailureDoesNotFallBackToAnotherExecutor() {
        val session = ConsoleSession("/does/not/exist", temporary.root) { error("Private root session lost") }
        assertTrue(session.reserve("id", true))
        session.execute("id", true)
        assertEquals("Private root session lost", session.state.value.error)
        assertNull(session.state.value.exitCode)
        assertFalse(session.state.value.running)
    }

    private fun session(): ConsoleSession {
        val binary = File(temporary.root, "console")
        val build = ProcessBuilder("cc", "-O2", "-DCONSOLE_SHELL=\"/bin/sh\"",
            "src/main/cpp/console_runner.c", "-o", binary.path).inheritIO().start()
        assertEquals(0, build.waitFor())
        return ConsoleSession(binary.path, temporary.root)
    }

    @Test fun largeUnicodeOutputIsBoundedAndExitCodeIsReal() {
        val session = session()
        val command = "head -c 100000 /dev/zero | tr '\\0' x; printf '🌸done'; exit 9"
        assertTrue(session.reserve(command))
        session.execute(command, false)
        val state = session.state.value
        assertFalse(state.running)
        assertEquals(9, state.exitCode)
        assertTrue(state.output.length <= ConsoleSession.OUTPUT_LIMIT)
        assertTrue(state.output.endsWith("🌸done"))
        assertFalse(state.output[0].isLowSurrogate())
    }

    @Test fun stopBeforeSpawnAndClosedSessionNeverStartCommands() {
        val session = session()
        val command = "touch should-not-exist"
        assertTrue(session.reserve(command))
        assertFalse(session.reserve("id"))
        session.stop()
        session.execute(command, false)
        assertTrue(session.state.value.stopped)
        assertFalse(session.state.value.running)
        assertFalse(File(temporary.root, "should-not-exist").exists())
        session.close()
        assertFalse(session.reserve(command))
    }

    @Test fun closeWhileRunningCancelsWithoutWaitingForSleep() {
        val session = session()
        val command = "printf started; sleep 60 | cat"
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(session.reserve(command))
            val task = executor.submit { session.execute(command, false) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!session.state.value.output.endsWith("started") && System.nanoTime() < deadline) Thread.yield()
            assertTrue(session.state.value.output.endsWith("started"))
            session.close()
            task.get(3, TimeUnit.SECONDS)
            assertTrue(session.state.value.stopped)
            assertFalse(session.state.value.running)
            assertEquals(130, session.state.value.exitCode)
        } finally { session.close(); executor.shutdownNow() }
    }
}
