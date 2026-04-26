package com.cellularproxy.root

import com.cellularproxy.shared.logging.LogRedactionSecrets
import com.cellularproxy.shared.root.RootAvailabilityStatus
import com.cellularproxy.shared.root.RootCommandAuditRecord
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandResult
import com.cellularproxy.shared.rotation.RotationEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RotationRootAvailabilityControllerTest {
    @Test
    fun `available root check maps to root-available rotation event`() {
        val available = rootAvailableCheckResult(rawStdout = "0\n")
        val calls = mutableListOf<RotationRootAvailabilityCall>()
        val controller = RotationRootAvailabilityController(
            probe = recordingProbe(calls, available),
        )
        val secrets = LogRedactionSecrets(managementApiToken = "token")

        val decision = controller.checkRoot(
            timeoutMillis = 2_000,
            secrets = secrets,
        )

        assertEquals(RotationRootAvailabilityDecision.Available(available), decision)
        assertSame(available, decision.result)
        assertEquals(RotationEvent.RootAvailable, decision.event)
        assertEquals(listOf(RotationRootAvailabilityCall(2_000, secrets)), calls)
    }

    @Test
    fun `unavailable root check maps to root-unavailable rotation event`() {
        val unavailable = RootAvailabilityCheckResult(
            status = RootAvailabilityStatus.Unavailable,
            execution = rootCommandFailureExecution(),
            failureReason = RootAvailabilityCheckFailure.CommandFailed,
        )
        val controller = RotationRootAvailabilityController(
            probe = recordingProbe(mutableListOf(), unavailable),
        )

        val decision = controller.checkRoot(timeoutMillis = 2_000)

        assertEquals(RotationRootAvailabilityDecision.Unavailable(unavailable), decision)
        assertSame(unavailable, decision.result)
        assertEquals(RootAvailabilityCheckFailure.CommandFailed, decision.failureReason)
        assertEquals(RotationEvent.RootUnavailable, decision.event)
    }

    @Test
    fun `unknown root check fails closed as root-unavailable rotation event`() {
        val unknown = RootAvailabilityCheckResult(status = RootAvailabilityStatus.Unknown)
        val controller = RotationRootAvailabilityController(
            probe = recordingProbe(mutableListOf(), unknown),
        )

        val decision = controller.checkRoot(timeoutMillis = 2_000)

        assertEquals(RotationRootAvailabilityDecision.Unavailable(unknown), decision)
        assertEquals(null, decision.failureReason)
        assertEquals(RotationEvent.RootUnavailable, decision.event)
    }

    @Test
    fun `root availability checker can be used as rotation root availability probe`() {
        val calls = mutableListOf<RootAvailabilityCommandCall>()
        val checker: RotationRootAvailabilityProbe = RootAvailabilityChecker(
            executor = recordingRootAvailabilityExecutor(
                calls = calls,
                result = {
                    RootCommandProcessResult.Completed(
                        exitCode = 0,
                        stdout = "0\n",
                        stderr = "",
                    )
                },
            ),
        )

        val result = checker.check(timeoutMillis = 2_000, secrets = LogRedactionSecrets())

        assertEquals(RootAvailabilityStatus.Available, result.status)
        assertEquals(listOf(RootAvailabilityCommandCall(RootShellCommands.rootAvailabilityCheck(), 2_000)), calls)
    }

    @Test
    fun `decision invariants reject contradictory results`() {
        val available = rootAvailableCheckResult(rawStdout = "0")
        val unknown = RootAvailabilityCheckResult(status = RootAvailabilityStatus.Unknown)

        assertFailsWith<IllegalArgumentException> {
            RotationRootAvailabilityDecision.Available(unknown)
        }
        assertFailsWith<IllegalArgumentException> {
            RotationRootAvailabilityDecision.Unavailable(available)
        }
    }
}

private data class RotationRootAvailabilityCall(
    val timeoutMillis: Long,
    val secrets: LogRedactionSecrets,
)

private fun recordingProbe(
    calls: MutableList<RotationRootAvailabilityCall>,
    result: RootAvailabilityCheckResult,
): RotationRootAvailabilityProbe =
    RotationRootAvailabilityProbe { timeoutMillis, secrets ->
        calls += RotationRootAvailabilityCall(timeoutMillis, secrets)
        result
    }

private data class RootAvailabilityCommandCall(
    val command: RootShellCommand,
    val timeoutMillis: Long,
)

private fun recordingRootAvailabilityExecutor(
    calls: MutableList<RootAvailabilityCommandCall>,
    result: (RootAvailabilityCommandCall) -> RootCommandProcessResult,
): RootCommandExecutor =
    RootCommandExecutor(
        processExecutor = RootCommandProcessExecutor { command, timeoutMillis ->
            val call = RootAvailabilityCommandCall(command, timeoutMillis)
            calls += call
            result(call)
        },
    )

private fun rootAvailableCheckResult(rawStdout: String): RootAvailabilityCheckResult =
    RootAvailabilityCheckResult(
        status = RootAvailabilityStatus.Available,
        execution = rootAvailabilityExecution(
            result = RootCommandResult.completed(
                category = RootCommandCategory.RootAvailabilityCheck,
                exitCode = 0,
                stdout = rawStdout,
                stderr = "",
            ),
            rawStdout = rawStdout,
        ),
    )

private fun rootCommandFailureExecution(): RootCommandExecution =
    rootAvailabilityExecution(
        result = RootCommandResult.completed(
            category = RootCommandCategory.RootAvailabilityCheck,
            exitCode = 1,
            stdout = "",
            stderr = "permission denied",
        ),
        rawStdout = "",
    )

private fun rootAvailabilityExecution(
    result: RootCommandResult,
    rawStdout: String,
): RootCommandExecution =
    RootCommandExecution.completed(
        result = result,
        started = RootCommandAuditRecord.started(RootCommandCategory.RootAvailabilityCheck),
        completed = RootCommandAuditRecord.completed(result),
        rawStdout = rawStdout,
    )
