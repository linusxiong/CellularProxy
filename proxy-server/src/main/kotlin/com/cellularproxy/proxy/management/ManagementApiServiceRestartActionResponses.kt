package com.cellularproxy.proxy.management

data class ManagementApiServiceRestartResult(
    val accepted: Boolean,
    val packageName: String?,
    val failureReason: ManagementApiServiceRestartFailureReason?,
    val afterResponseSent: () -> Unit = {},
) {
    init {
        if (accepted) {
            require(!packageName.isNullOrBlank()) { "Accepted service restart requires a package name" }
            require(failureReason == null) { "Accepted service restart must not have a failure reason" }
        } else {
            require(failureReason != null) { "Rejected service restart requires a failure reason" }
        }
    }

    companion object {
        fun accepted(
            packageName: String,
            afterResponseSent: () -> Unit = {},
        ): ManagementApiServiceRestartResult = ManagementApiServiceRestartResult(
            accepted = true,
            packageName = packageName,
            failureReason = null,
            afterResponseSent = afterResponseSent,
        )

        fun rejected(
            failureReason: ManagementApiServiceRestartFailureReason,
            packageName: String? = null,
        ): ManagementApiServiceRestartResult = ManagementApiServiceRestartResult(
            accepted = false,
            packageName = packageName,
            failureReason = failureReason,
        )
    }
}

enum class ManagementApiServiceRestartFailureReason {
    RootOperationsDisabled,
    ExecutionUnavailable,
}

object ManagementApiServiceRestartActionResponses {
    fun transition(result: ManagementApiServiceRestartResult): ManagementApiResponse = ManagementApiResponse.json(
        statusCode = if (result.accepted) 202 else 409,
        body =
            buildString {
                append('{')
                append(""""accepted":""")
                append(result.accepted)
                append(',')
                append(""""restart":""")
                append(result.managementApiJson())
                append('}')
            },
        afterResponseSent = {
            if (result.accepted) {
                result.afterResponseSent()
            }
        },
    )
}

private fun ManagementApiServiceRestartResult.managementApiJson(): String = buildString {
    append('{')
    append(""""packageName":""")
    append(packageName.jsonNullableString())
    append(',')
    append(""""failureReason":""")
    append(failureReason?.apiValue().jsonNullableString())
    append('}')
}

private fun ManagementApiServiceRestartFailureReason.apiValue(): String = when (this) {
    ManagementApiServiceRestartFailureReason.RootOperationsDisabled -> "root_operations_disabled"
    ManagementApiServiceRestartFailureReason.ExecutionUnavailable -> "execution_unavailable"
}
