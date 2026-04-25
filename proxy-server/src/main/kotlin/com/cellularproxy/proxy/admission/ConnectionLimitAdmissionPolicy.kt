package com.cellularproxy.proxy.admission

data class ConnectionLimitAdmissionConfig(
    val maxConcurrentConnections: Int,
) {
    init {
        require(maxConcurrentConnections > 0) { "Maximum concurrent connections must be positive" }
    }
}

sealed interface ConnectionLimitAdmissionDecision {
    data class Accepted(
        val activeConnectionsAfterAdmission: Long,
    ) : ConnectionLimitAdmissionDecision

    data class Rejected(
        val reason: ConnectionLimitAdmissionRejectionReason,
    ) : ConnectionLimitAdmissionDecision
}

sealed interface ConnectionLimitAdmissionRejectionReason {
    data class MaximumConcurrentConnectionsReached(
        val activeConnections: Long,
        val maxConcurrentConnections: Int,
    ) : ConnectionLimitAdmissionRejectionReason
}

object ConnectionLimitAdmissionPolicy {
    fun evaluate(
        config: ConnectionLimitAdmissionConfig,
        activeConnections: Long,
    ): ConnectionLimitAdmissionDecision {
        require(activeConnections >= 0) { "Active connection count must not be negative" }

        return if (activeConnections < config.maxConcurrentConnections) {
            ConnectionLimitAdmissionDecision.Accepted(
                activeConnectionsAfterAdmission = activeConnections + 1,
            )
        } else {
            ConnectionLimitAdmissionDecision.Rejected(
                ConnectionLimitAdmissionRejectionReason.MaximumConcurrentConnectionsReached(
                    activeConnections = activeConnections,
                    maxConcurrentConnections = config.maxConcurrentConnections,
                ),
            )
        }
    }
}
