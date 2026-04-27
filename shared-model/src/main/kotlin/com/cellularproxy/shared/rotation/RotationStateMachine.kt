package com.cellularproxy.shared.rotation

import com.cellularproxy.shared.root.RootCommandOutcome
import com.cellularproxy.shared.root.RootCommandCategory
import com.cellularproxy.shared.root.RootCommandResult

data class RotationStatus(
    val state: RotationState,
    val operation: RotationOperation? = null,
    val oldPublicIp: String? = null,
    val newPublicIp: String? = null,
    val publicIpChanged: Boolean? = null,
    val failureReason: RotationFailureReason? = null,
) {
    init {
        when (state) {
            RotationState.Idle -> {
                require(operation == null)
                require(oldPublicIp == null)
                require(newPublicIp == null)
                require(publicIpChanged == null)
                require(failureReason == null)
            }
            RotationState.CheckingCooldown,
            RotationState.CheckingRoot,
            RotationState.ProbingOldPublicIp,
            -> requireActiveMetadata(
                oldPublicIpAllowed = false,
            )
            RotationState.PausingNewRequests,
            RotationState.DrainingConnections,
            RotationState.RunningDisableCommand,
            RotationState.WaitingForToggleDelay,
            RotationState.RunningEnableCommand,
            RotationState.WaitingForNetworkReturn,
            RotationState.ProbingNewPublicIp,
            -> requireActiveMetadata(
                oldPublicIpAllowed = true,
                oldPublicIpRequired = true,
            )
            RotationState.Completed -> {
                require(operation != null)
                require(oldPublicIp != null)
                require(newPublicIp != null)
                require(publicIpChanged == (oldPublicIp != newPublicIp))
                require(failureReason == null)
            }
            RotationState.Failed -> {
                require(operation != null)
                require(failureReason != null)
                require(publicIpChanged == null || oldPublicIp != null && newPublicIp != null)
                require(
                    publicIpChanged == null ||
                    publicIpChanged == (oldPublicIp != newPublicIp),
                )
            }
            RotationState.ResumingProxyRequests -> {
                require(operation != null)
                require(oldPublicIp != null)
                require(newPublicIp != null || failureReason in RESUMABLE_FAILURE_REASONS_WITHOUT_NEW_IP)
                require(failureReason == null || failureReason in RESUMABLE_FAILURE_REASONS)
                require(failureReason != null || publicIpChanged != null)
                require(publicIpChanged == null || newPublicIp != null)
                require(
                    publicIpChanged == null ||
                        publicIpChanged == (oldPublicIp != newPublicIp),
                )
            }
        }
    }

    val isActive: Boolean
        get() = state !in TERMINAL_STATES

    companion object {
        fun idle(): RotationStatus = RotationStatus(state = RotationState.Idle)
    }
}

private fun RotationStatus.requireActiveMetadata(
    oldPublicIpAllowed: Boolean,
    oldPublicIpRequired: Boolean = false,
) {
    require(operation != null)
    require(oldPublicIpAllowed || oldPublicIp == null)
    require(!oldPublicIpRequired || oldPublicIp != null)
    require(newPublicIp == null)
    require(publicIpChanged == null)
    require(failureReason == null)
}

enum class RotationState {
    Idle,
    CheckingCooldown,
    CheckingRoot,
    ProbingOldPublicIp,
    PausingNewRequests,
    DrainingConnections,
    RunningDisableCommand,
    WaitingForToggleDelay,
    RunningEnableCommand,
    WaitingForNetworkReturn,
    ProbingNewPublicIp,
    ResumingProxyRequests,
    Completed,
    Failed,
}

enum class RotationOperation {
    MobileData,
    AirplaneMode,
}

enum class RotationFailureReason {
    CooldownActive,
    RootUnavailable,
    OldPublicIpProbeFailed,
    RootCommandFailed,
    RootCommandTimedOut,
    NetworkReturnTimedOut,
    NewPublicIpProbeFailed,
    StrictIpChangeRequired,
    RootOperationsDisabled,
}

sealed interface RotationEvent {
    data class StartRequested(val operation: RotationOperation) : RotationEvent
    data object CooldownPassed : RotationEvent
    data object CooldownRejected : RotationEvent
    data object RootAvailable : RotationEvent
    data object RootUnavailable : RotationEvent
    data class OldPublicIpProbeSucceeded(val publicIp: String) : RotationEvent
    data object OldPublicIpProbeFailed : RotationEvent
    data object NewRequestsPaused : RotationEvent
    data object ConnectionsDrained : RotationEvent
    data class RootCommandCompleted(val result: RootCommandResult) : RotationEvent
    data class RootCommandFailedToStart(val category: RootCommandCategory) : RotationEvent
    data object ToggleDelayElapsed : RotationEvent
    data object NetworkReturned : RotationEvent
    data object NetworkReturnTimedOut : RotationEvent
    data class NewPublicIpProbeSucceeded(
        val publicIp: String,
        val strictIpChangeRequired: Boolean,
    ) : RotationEvent
    data object NewPublicIpProbeFailed : RotationEvent
    data object ProxyRequestsResumed : RotationEvent
}

data class RotationTransitionResult(
    val disposition: RotationTransitionDisposition,
    val status: RotationStatus,
) {
    val accepted: Boolean
        get() = disposition == RotationTransitionDisposition.Accepted
}

enum class RotationTransitionDisposition {
    Accepted,
    Duplicate,
    Ignored,
    Rejected,
}

object RotationStateMachine {
    fun transition(
        status: RotationStatus,
        event: RotationEvent,
    ): RotationTransitionResult {
        if (event is RotationEvent.StartRequested) {
            return when {
                status.isActive -> duplicate(status)
                else -> accepted(
                    RotationStatus(
                        state = RotationState.CheckingCooldown,
                        operation = event.operation,
                    ),
                )
            }
        }

        val next = when (status.state) {
            RotationState.CheckingCooldown -> status.afterCooldown(event)
            RotationState.CheckingRoot -> status.afterRootCheck(event)
            RotationState.ProbingOldPublicIp -> status.afterOldIpProbe(event)
            RotationState.PausingNewRequests -> status.afterNewRequestsPaused(event)
            RotationState.DrainingConnections -> status.afterConnectionDrain(event)
            RotationState.RunningDisableCommand -> status.afterDisableCommand(event)
            RotationState.WaitingForToggleDelay -> status.afterToggleDelay(event)
            RotationState.RunningEnableCommand -> status.afterEnableCommand(event)
            RotationState.WaitingForNetworkReturn -> status.afterNetworkReturn(event)
            RotationState.ProbingNewPublicIp -> status.afterNewIpProbe(event)
            RotationState.ResumingProxyRequests -> status.afterProxyRequestsResumed(event)
            RotationState.Idle,
            RotationState.Completed,
            RotationState.Failed,
            -> null
        }

        return if (next == null) ignored(status) else accepted(next)
    }

    fun apply(
        status: RotationStatus,
        event: RotationEvent,
    ): RotationTransitionResult = transition(status, event)

    private fun RotationStatus.afterCooldown(event: RotationEvent): RotationStatus? =
        when (event) {
            RotationEvent.CooldownPassed -> copy(state = RotationState.CheckingRoot)
            RotationEvent.CooldownRejected -> failed(RotationFailureReason.CooldownActive)
            else -> null
        }

    private fun RotationStatus.afterRootCheck(event: RotationEvent): RotationStatus? =
        when (event) {
            RotationEvent.RootAvailable -> copy(state = RotationState.ProbingOldPublicIp)
            RotationEvent.RootUnavailable -> failed(RotationFailureReason.RootUnavailable)
            else -> null
        }

    private fun RotationStatus.afterOldIpProbe(event: RotationEvent): RotationStatus? =
        when (event) {
            is RotationEvent.OldPublicIpProbeSucceeded -> copy(
                state = RotationState.PausingNewRequests,
                oldPublicIp = event.publicIp,
            )
            RotationEvent.OldPublicIpProbeFailed ->
                failed(RotationFailureReason.OldPublicIpProbeFailed)
            else -> null
        }

    private fun RotationStatus.afterNewRequestsPaused(event: RotationEvent): RotationStatus? =
        when (event) {
            RotationEvent.NewRequestsPaused -> copy(state = RotationState.DrainingConnections)
            else -> null
        }

    private fun RotationStatus.afterConnectionDrain(event: RotationEvent): RotationStatus? =
        when (event) {
            RotationEvent.ConnectionsDrained -> copy(state = RotationState.RunningDisableCommand)
            else -> null
        }

    private fun RotationStatus.afterDisableCommand(event: RotationEvent): RotationStatus? =
        when (event) {
            is RotationEvent.RootCommandCompleted -> {
                if (event.result.category != disableCommandCategory()) {
                    null
                } else {
                    when (event.result.outcome) {
                        RootCommandOutcome.Success -> copy(state = RotationState.WaitingForToggleDelay)
                        RootCommandOutcome.Failure ->
                            resumingFailure(RotationFailureReason.RootCommandFailed)
                        RootCommandOutcome.Timeout ->
                            resumingFailure(RotationFailureReason.RootCommandTimedOut)
                    }
                }
            }
            is RotationEvent.RootCommandFailedToStart ->
                if (event.category == disableCommandCategory()) {
                    resumingFailure(RotationFailureReason.RootCommandFailed)
                } else {
                    null
                }
            else -> null
        }

    private fun RotationStatus.afterToggleDelay(event: RotationEvent): RotationStatus? =
        when (event) {
            RotationEvent.ToggleDelayElapsed -> copy(state = RotationState.RunningEnableCommand)
            else -> null
        }

    private fun RotationStatus.afterEnableCommand(event: RotationEvent): RotationStatus? =
        when (event) {
            is RotationEvent.RootCommandCompleted -> {
                if (event.result.category != enableCommandCategory()) {
                    null
                } else {
                    when (event.result.outcome) {
                        RootCommandOutcome.Success -> copy(state = RotationState.WaitingForNetworkReturn)
                        RootCommandOutcome.Failure ->
                            resumingFailure(RotationFailureReason.RootCommandFailed)
                        RootCommandOutcome.Timeout ->
                            resumingFailure(RotationFailureReason.RootCommandTimedOut)
                    }
                }
            }
            is RotationEvent.RootCommandFailedToStart ->
                if (event.category == enableCommandCategory()) {
                    resumingFailure(RotationFailureReason.RootCommandFailed)
                } else {
                    null
                }
            else -> null
        }

    private fun RotationStatus.afterNetworkReturn(event: RotationEvent): RotationStatus? =
        when (event) {
            RotationEvent.NetworkReturned -> copy(state = RotationState.ProbingNewPublicIp)
            RotationEvent.NetworkReturnTimedOut ->
                resumingFailure(RotationFailureReason.NetworkReturnTimedOut)
            else -> null
        }

    private fun RotationStatus.afterNewIpProbe(event: RotationEvent): RotationStatus? =
        when (event) {
            is RotationEvent.NewPublicIpProbeSucceeded -> completeWithNewIp(
                publicIp = event.publicIp,
                strictIpChangeRequired = event.strictIpChangeRequired,
            )
            RotationEvent.NewPublicIpProbeFailed ->
                resumingFailure(RotationFailureReason.NewPublicIpProbeFailed)
            else -> null
        }

    private fun RotationStatus.afterProxyRequestsResumed(event: RotationEvent): RotationStatus? =
        when (event) {
            RotationEvent.ProxyRequestsResumed -> if (failureReason == null) {
                copy(state = RotationState.Completed)
            } else {
                copy(state = RotationState.Failed)
            }
            else -> null
        }

    private fun RotationStatus.completeWithNewIp(
        publicIp: String,
        strictIpChangeRequired: Boolean,
    ): RotationStatus {
        val changed = oldPublicIp != publicIp
        return if (strictIpChangeRequired && !changed) {
            resumingFailure(
                reason = RotationFailureReason.StrictIpChangeRequired,
                newPublicIp = publicIp,
                publicIpChanged = false,
            )
        } else {
            copy(
                state = RotationState.ResumingProxyRequests,
                newPublicIp = publicIp,
                publicIpChanged = changed,
            )
        }
    }

    private fun RotationStatus.failed(
        reason: RotationFailureReason,
        newPublicIp: String? = null,
        publicIpChanged: Boolean? = null,
    ): RotationStatus = RotationStatus(
        state = RotationState.Failed,
        operation = requireNotNull(operation),
        oldPublicIp = oldPublicIp,
        newPublicIp = newPublicIp,
        publicIpChanged = publicIpChanged,
        failureReason = reason,
    )

    private fun RotationStatus.resumingFailure(
        reason: RotationFailureReason,
        newPublicIp: String? = null,
        publicIpChanged: Boolean? = null,
    ): RotationStatus = copy(
        state = RotationState.ResumingProxyRequests,
        newPublicIp = newPublicIp,
        publicIpChanged = publicIpChanged,
        failureReason = reason,
    )

    private fun RotationStatus.disableCommandCategory() =
        when (operation) {
            RotationOperation.MobileData -> RootCommandCategory.MobileDataDisable
            RotationOperation.AirplaneMode -> RootCommandCategory.AirplaneModeEnable
            null -> error("Rotation operation is required")
        }

    private fun RotationStatus.enableCommandCategory() =
        when (operation) {
            RotationOperation.MobileData -> RootCommandCategory.MobileDataEnable
            RotationOperation.AirplaneMode -> RootCommandCategory.AirplaneModeDisable
            null -> error("Rotation operation is required")
        }

    private fun accepted(status: RotationStatus): RotationTransitionResult =
        RotationTransitionResult(RotationTransitionDisposition.Accepted, status)

    private fun duplicate(status: RotationStatus): RotationTransitionResult =
        RotationTransitionResult(RotationTransitionDisposition.Duplicate, status)

    private fun ignored(status: RotationStatus): RotationTransitionResult =
        RotationTransitionResult(RotationTransitionDisposition.Ignored, status)
}

private val TERMINAL_STATES = setOf(
    RotationState.Idle,
    RotationState.Completed,
    RotationState.Failed,
)

private val RESUMABLE_FAILURE_REASONS = setOf(
    RotationFailureReason.RootCommandFailed,
    RotationFailureReason.RootCommandTimedOut,
    RotationFailureReason.NetworkReturnTimedOut,
    RotationFailureReason.NewPublicIpProbeFailed,
    RotationFailureReason.StrictIpChangeRequired,
)

private val RESUMABLE_FAILURE_REASONS_WITHOUT_NEW_IP = setOf(
    RotationFailureReason.RootCommandFailed,
    RotationFailureReason.RootCommandTimedOut,
    RotationFailureReason.NetworkReturnTimedOut,
    RotationFailureReason.NewPublicIpProbeFailed,
)
