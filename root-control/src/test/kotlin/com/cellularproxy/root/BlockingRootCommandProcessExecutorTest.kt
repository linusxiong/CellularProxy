package com.cellularproxy.root

import com.cellularproxy.shared.root.RootCommandCategory
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BlockingRootCommandProcessExecutorTest {
    @Test
    fun `completed process captures exit code stdout and stderr`() {
        val executor = BlockingRootCommandProcessExecutor()

        val result =
            executor.execute(
                command = shellCommand("printf stdout; printf stderr >&2; exit 7"),
                timeoutMillis = 2_000,
            )

        val completed = result as RootCommandProcessResult.Completed
        assertEquals(7, completed.exitCode)
        assertEquals("stdout", completed.stdout)
        assertEquals("stderr", completed.stderr)
    }

    @Test
    fun `process stdin is closed immediately because executor has no input API`() {
        val executor = BlockingRootCommandProcessExecutor()

        val result =
            executor.execute(
                command = shellCommand("cat >/dev/null; printf after-eof"),
                timeoutMillis = 2_000,
            )

        val completed = assertIs<RootCommandProcessResult.Completed>(result)
        assertEquals(0, completed.exitCode)
        assertEquals("after-eof", completed.stdout)
    }

    @Test
    fun `timed out process is destroyed and returns partial output`() {
        val executor = BlockingRootCommandProcessExecutor()

        val result =
            executor.execute(
                command = shellCommand("printf before; printf err-before >&2; sleep 2; printf after"),
                timeoutMillis = 100,
            )

        val timedOut = result as RootCommandProcessResult.TimedOut
        assertEquals("before", timedOut.stdout)
        assertEquals("err-before", timedOut.stderr)
    }

    @Test
    fun `timed out process is not blocked by child process inheriting output pipes`() {
        val executor = BlockingRootCommandProcessExecutor()
        var result: RootCommandProcessResult? = null

        val elapsedMillis =
            measureTimeMillis {
                result =
                    executor.execute(
                        command = shellCommand("printf before; (sleep 5; printf child)& sleep 5"),
                        timeoutMillis = 250,
                    )
            }

        assertIs<RootCommandProcessResult.TimedOut>(result)
        assertTrue(elapsedMillis < 1_500, "Expected bounded timeout cleanup, took ${elapsedMillis}ms")
        assertEquals("before", (result as RootCommandProcessResult.TimedOut).stdout)
    }

    @Test
    fun `timed out process destroys descendant process before returning`() {
        val executor = BlockingRootCommandProcessExecutor()

        val result =
            executor.execute(
                command = shellCommand("sleep 5 & echo child-pid:$!; sleep 5"),
                timeoutMillis = 250,
            )

        val timedOut = assertIs<RootCommandProcessResult.TimedOut>(result)
        val childPid =
            requireNotNull(
                Regex("child-pid:(\\d+)")
                    .find(timedOut.stdout)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong(),
            )
        assertProcessStops(childPid)
    }

    @Test
    fun `completed process is not blocked by child process inheriting output pipes`() {
        val executor = BlockingRootCommandProcessExecutor()
        var result: RootCommandProcessResult? = null

        val elapsedMillis =
            measureTimeMillis {
                result =
                    executor.execute(
                        command = shellCommand("printf parent; (sleep 5; printf child)& exit 0"),
                        timeoutMillis = 2_000,
                    )
            }

        val completed = assertIs<RootCommandProcessResult.Completed>(result)
        assertEquals(0, completed.exitCode)
        assertEquals("parent", completed.stdout)
        assertTrue(elapsedMillis < 1_500, "Expected bounded capture cleanup, took ${elapsedMillis}ms")
    }

    @Test
    fun `interrupted process wait destroys descendant process before propagating interruption`() {
        val executor = BlockingRootCommandProcessExecutor()
        val pidFile = Files.createTempFile("cellularproxy-root-child", ".pid")
        val interruptionCaught = AtomicBoolean(false)
        val interruptStatusRestored = AtomicBoolean(false)
        val thread =
            Thread {
                try {
                    executor.execute(
                        command = shellCommand("sleep 5 & echo $! > ${pidFile.toAbsolutePath()}; sleep 5"),
                        timeoutMillis = 10_000,
                    )
                } catch (_: InterruptedException) {
                    interruptionCaught.set(true)
                    interruptStatusRestored.set(Thread.currentThread().isInterrupted)
                }
            }

        thread.start()
        val childPid = awaitPidFile(pidFile)
        thread.interrupt()
        thread.join(1_500)

        assertTrue(!thread.isAlive, "Expected interrupted executor call to return")
        assertTrue(interruptionCaught.get(), "Expected interruption to propagate")
        assertTrue(interruptStatusRestored.get(), "Expected interrupt status to be restored before propagation")
        assertProcessStops(childPid)
    }

    @Test
    fun `interrupted stream capture closes inherited pipe before propagating interruption`() {
        val executor = BlockingRootCommandProcessExecutor(streamCleanupTimeoutMillis = 10_000)
        val interruptionCaught = AtomicBoolean(false)
        val interruptStatusRestored = AtomicBoolean(false)
        val thread =
            Thread {
                try {
                    executor.execute(
                        command = shellCommand("printf parent; (sleep 5)& exit 0"),
                        timeoutMillis = 2_000,
                    )
                } catch (_: InterruptedException) {
                    interruptionCaught.set(true)
                    interruptStatusRestored.set(Thread.currentThread().isInterrupted)
                }
            }

        thread.start()
        Thread.sleep(250)
        thread.interrupt()
        thread.join(1_500)

        assertTrue(!thread.isAlive, "Expected interrupted capture wait to return")
        assertTrue(interruptionCaught.get(), "Expected interruption to propagate")
        assertTrue(interruptStatusRestored.get(), "Expected interrupt status to be restored before propagation")
    }

    @Test
    fun `invalid timeout is rejected before process start`() {
        val executor = BlockingRootCommandProcessExecutor()

        val failure =
            assertFailsWith<IllegalArgumentException> {
                executor.execute(
                    command = shellCommand("exit 0"),
                    timeoutMillis = 0,
                )
            }

        assertEquals("Root process timeout must be positive", failure.message)
    }

    private fun shellCommand(script: String): RootShellCommand =
        RootShellCommand.trusted(
            category = RootCommandCategory.RootAvailabilityCheck,
            argv = listOf("sh", "-c", script),
        )

    private fun awaitPidFile(pidFile: java.nio.file.Path): Long {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < deadline) {
            val content = Files.readString(pidFile).trim()
            if (content.isNotBlank()) {
                return content.toLong()
            }
            Thread.sleep(25)
        }
        val content = Files.readString(pidFile).trim()
        assertTrue(content.isNotBlank(), "Expected child process pid in $pidFile")
        return content.toLong()
    }

    private fun assertProcessStops(pid: Long) {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < deadline) {
            val process = ProcessHandle.of(pid)
            if (process.isEmpty || !process.get().isAlive) {
                return
            }
            Thread.sleep(25)
        }
        val process = ProcessHandle.of(pid)
        assertTrue(process.isEmpty || !process.get().isAlive, "Expected descendant process $pid to be stopped")
    }
}
