package com.cellularproxy.app.e2e

class CloudflareE2eValidationEvidence private constructor(
    val status: CloudflareE2eValidationEvidenceStatus,
    val durationMillis: Long,
    val edgeSessionCategory: CloudflareE2eEdgeSessionCategory?,
    val httpStatusCode: Int?,
    val errorClass: String?,
) {
    init {
        require(durationMillis >= 0) { "durationMillis must be non-negative" }
        require(httpStatusCode == null || httpStatusCode in 100..599) {
            "httpStatusCode must be a valid HTTP status code"
        }
        require(status == CloudflareE2eValidationEvidenceStatus.Failure || errorClass == null) {
            "successful evidence cannot include errorClass"
        }
    }

    val safeSummary: String
        get() {
            val prefix =
                when (status) {
                    CloudflareE2eValidationEvidenceStatus.Success -> "Cloudflare e2e validation succeeded"
                    CloudflareE2eValidationEvidenceStatus.Failure -> "Cloudflare e2e validation failed"
                }
            val base =
                "$prefix: " +
                    "durationMs=$durationMillis, " +
                    "edgeSession=${edgeSessionCategory?.summaryLabel ?: "not recorded"}, " +
                    "httpStatus=${httpStatusCode?.toString() ?: "not recorded"}"
            return if (status == CloudflareE2eValidationEvidenceStatus.Failure) {
                "$base, errorClass=${errorClass ?: "unknown"}"
            } else {
                base
            }
        }

    override fun toString(): String = "CloudflareE2eValidationEvidence(" +
        "status=$status, " +
        "durationMillis=$durationMillis, " +
        "edgeSessionCategory=${edgeSessionCategory?.summaryLabel}, " +
        "httpStatusCode=$httpStatusCode, " +
        "errorClass=$errorClass)"

    companion object {
        fun success(
            durationMillis: Long,
            edgeSessionCategory: CloudflareE2eEdgeSessionCategory?,
            httpStatusCode: Int?,
        ): CloudflareE2eValidationEvidence = CloudflareE2eValidationEvidence(
            status = CloudflareE2eValidationEvidenceStatus.Success,
            durationMillis = durationMillis,
            edgeSessionCategory = edgeSessionCategory,
            httpStatusCode = httpStatusCode,
            errorClass = null,
        )

        fun failure(
            durationMillis: Long,
            edgeSessionCategory: CloudflareE2eEdgeSessionCategory?,
            httpStatusCode: Int?,
            errorClass: CloudflareE2eErrorClass,
        ): CloudflareE2eValidationEvidence = CloudflareE2eValidationEvidence(
            status = CloudflareE2eValidationEvidenceStatus.Failure,
            durationMillis = durationMillis,
            edgeSessionCategory = edgeSessionCategory,
            httpStatusCode = httpStatusCode,
            errorClass = errorClass.summaryLabel,
        )

        fun failure(
            durationMillis: Long,
            edgeSessionCategory: CloudflareE2eEdgeSessionCategory?,
            httpStatusCode: Int?,
            throwable: Throwable,
        ): CloudflareE2eValidationEvidence = CloudflareE2eValidationEvidence(
            status = CloudflareE2eValidationEvidenceStatus.Failure,
            durationMillis = durationMillis,
            edgeSessionCategory = edgeSessionCategory,
            httpStatusCode = httpStatusCode,
            errorClass = throwable.javaClass.simpleName.ifBlank { throwable.javaClass.name },
        )
    }
}

enum class CloudflareE2eValidationEvidenceStatus {
    Success,
    Failure,
}

enum class CloudflareE2eEdgeSessionCategory(
    val summaryLabel: String,
) {
    Connected("connected"),
    Degraded("degraded"),
    ManagementApiRoundTrip("management_api_round_trip"),
    EdgeUnavailable("edge_unavailable"),
}

enum class CloudflareE2eErrorClass(
    val summaryLabel: String,
) {
    Network("network"),
    Unauthorized("unauthorized"),
    Timeout("timeout"),
    Unavailable("unavailable"),
    InvalidConfiguration("invalid_configuration"),
    Unknown("unknown"),
}
