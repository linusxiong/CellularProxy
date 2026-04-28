package com.cellularproxy.app.e2e

class CloudflareE2eValidationController(
    private val elapsedRealtimeMillis: () -> Long,
    private val validator: (CloudflareE2eValidationConfig.Ready) -> CloudflareE2eValidationAttemptResult,
) {
    fun run(config: CloudflareE2eValidationConfig): CloudflareE2eValidationEvidence {
        if (config !is CloudflareE2eValidationConfig.Ready) {
            return CloudflareE2eValidationEvidence.failure(
                durationMillis = 0,
                edgeSessionCategory = null,
                httpStatusCode = null,
                errorClass = CloudflareE2eErrorClass.InvalidConfiguration,
            )
        }

        val startedAtMillis = elapsedRealtimeMillis()
        val result = runCatching { validator(config) }
        val durationMillis = (elapsedRealtimeMillis() - startedAtMillis).coerceAtLeast(0)

        return result.fold(
            onSuccess = { attemptResult -> attemptResult.toEvidence(durationMillis) },
            onFailure = { throwable ->
                CloudflareE2eValidationEvidence.failure(
                    durationMillis = durationMillis,
                    edgeSessionCategory = null,
                    httpStatusCode = null,
                    throwable = throwable,
                )
            },
        )
    }
}

sealed interface CloudflareE2eValidationAttemptResult {
    val edgeSessionCategory: CloudflareE2eEdgeSessionCategory?
    val httpStatusCode: Int?

    data class Success(
        override val edgeSessionCategory: CloudflareE2eEdgeSessionCategory?,
        override val httpStatusCode: Int?,
    ) : CloudflareE2eValidationAttemptResult

    data class Failure(
        override val edgeSessionCategory: CloudflareE2eEdgeSessionCategory?,
        override val httpStatusCode: Int?,
        val errorClass: CloudflareE2eErrorClass,
    ) : CloudflareE2eValidationAttemptResult
}

private fun CloudflareE2eValidationAttemptResult.toEvidence(durationMillis: Long): CloudflareE2eValidationEvidence = when (this) {
    is CloudflareE2eValidationAttemptResult.Success ->
        CloudflareE2eValidationEvidence.success(
            durationMillis = durationMillis,
            edgeSessionCategory = edgeSessionCategory,
            httpStatusCode = httpStatusCode,
        )
    is CloudflareE2eValidationAttemptResult.Failure ->
        CloudflareE2eValidationEvidence.failure(
            durationMillis = durationMillis,
            edgeSessionCategory = edgeSessionCategory,
            httpStatusCode = httpStatusCode,
            errorClass = errorClass,
        )
}
