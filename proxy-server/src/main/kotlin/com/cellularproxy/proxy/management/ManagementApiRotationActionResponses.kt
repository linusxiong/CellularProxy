package com.cellularproxy.proxy.management

import com.cellularproxy.shared.rotation.RotationFailureReason
import com.cellularproxy.shared.rotation.RotationOperation
import com.cellularproxy.shared.rotation.RotationState
import com.cellularproxy.shared.rotation.RotationStatus
import com.cellularproxy.shared.rotation.RotationTransitionDisposition
import com.cellularproxy.shared.rotation.RotationTransitionResult

object ManagementApiRotationActionResponses {
    fun transition(result: RotationTransitionResult): ManagementApiResponse =
        ManagementApiResponse.json(
            statusCode = if (result.accepted) 202 else 409,
            body = buildString {
                append('{')
                append(""""accepted":""")
                append(result.accepted)
                append(',')
                append(""""disposition":""")
                append(result.disposition.apiValue().jsonString())
                append(',')
                append(""""rotation":""")
                append(result.status.managementApiJson())
                append('}')
            },
        )
}

private fun RotationStatus.managementApiJson(): String =
    buildString {
        append('{')
        append(""""state":""")
        append(state.apiValue().jsonString())
        append(',')
        append(""""operation":""")
        append(operation?.apiValue().jsonNullableString())
        append(',')
        append(""""oldPublicIp":""")
        append(oldPublicIp.jsonNullableString())
        append(',')
        append(""""newPublicIp":""")
        append(newPublicIp.jsonNullableString())
        append(',')
        append(""""publicIpChanged":""")
        append(publicIpChanged?.toString() ?: "null")
        append(',')
        append(""""failureReason":""")
        append(failureReason?.apiValue().jsonNullableString())
        append('}')
    }

private fun RotationTransitionDisposition.apiValue(): String =
    when (this) {
        RotationTransitionDisposition.Accepted -> "accepted"
        RotationTransitionDisposition.Duplicate -> "duplicate"
        RotationTransitionDisposition.Ignored -> "ignored"
    }

private fun RotationState.apiValue(): String =
    when (this) {
        RotationState.Idle -> "idle"
        RotationState.CheckingCooldown -> "checking_cooldown"
        RotationState.CheckingRoot -> "checking_root"
        RotationState.ProbingOldPublicIp -> "probing_old_public_ip"
        RotationState.PausingNewRequests -> "pausing_new_requests"
        RotationState.DrainingConnections -> "draining_connections"
        RotationState.RunningDisableCommand -> "running_disable_command"
        RotationState.WaitingForToggleDelay -> "waiting_for_toggle_delay"
        RotationState.RunningEnableCommand -> "running_enable_command"
        RotationState.WaitingForNetworkReturn -> "waiting_for_network_return"
        RotationState.ProbingNewPublicIp -> "probing_new_public_ip"
        RotationState.ResumingProxyRequests -> "resuming_proxy_requests"
        RotationState.Completed -> "completed"
        RotationState.Failed -> "failed"
    }

private fun RotationOperation.apiValue(): String =
    when (this) {
        RotationOperation.MobileData -> "mobile_data"
        RotationOperation.AirplaneMode -> "airplane_mode"
    }

private fun RotationFailureReason.apiValue(): String =
    when (this) {
        RotationFailureReason.CooldownActive -> "cooldown_active"
        RotationFailureReason.RootUnavailable -> "root_unavailable"
        RotationFailureReason.OldPublicIpProbeFailed -> "old_public_ip_probe_failed"
        RotationFailureReason.RootCommandFailed -> "root_command_failed"
        RotationFailureReason.RootCommandTimedOut -> "root_command_timed_out"
        RotationFailureReason.NetworkReturnTimedOut -> "network_return_timed_out"
        RotationFailureReason.NewPublicIpProbeFailed -> "new_public_ip_probe_failed"
        RotationFailureReason.StrictIpChangeRequired -> "strict_ip_change_required"
    }
