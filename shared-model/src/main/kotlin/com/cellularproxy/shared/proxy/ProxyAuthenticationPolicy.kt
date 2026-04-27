package com.cellularproxy.shared.proxy

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

data class ProxyCredential(
    val username: String,
    val password: String,
) {
    init {
        require(username.isNotBlank()) { "Proxy username must not be blank" }
        require(password.isNotBlank()) { "Proxy password must not be blank" }
        require(':' !in username) { "Proxy username must not contain ':'" }
    }

    fun canonicalBasicPayload(): String = "$username:$password"

    override fun toString(): String = "ProxyCredential([REDACTED])"
}

data class ProxyAuthenticationConfig(
    val authEnabled: Boolean = true,
    val credential: ProxyCredential,
) {
    override fun toString(): String = "ProxyAuthenticationConfig(authEnabled=$authEnabled, credential=[REDACTED])"
}

data class ProxyAuthenticationDecision(
    val accepted: Boolean,
    val rejectionReason: ProxyAuthenticationRejectionReason?,
) {
    init {
        require(accepted == (rejectionReason == null)) {
            "Accepted decisions cannot carry a rejection reason, and rejected decisions must carry one"
        }
    }

    companion object {
        val Accepted = ProxyAuthenticationDecision(accepted = true, rejectionReason = null)

        fun rejected(reason: ProxyAuthenticationRejectionReason): ProxyAuthenticationDecision =
            ProxyAuthenticationDecision(accepted = false, rejectionReason = reason)
    }
}

enum class ProxyAuthenticationRejectionReason {
    MissingAuthorization,
    UnsupportedScheme,
    MalformedCredentials,
    CredentialMismatch,
}

object ProxyAuthenticationPolicy {
    fun evaluate(
        config: ProxyAuthenticationConfig,
        proxyAuthorization: String?,
    ): ProxyAuthenticationDecision {
        if (!config.authEnabled) {
            return ProxyAuthenticationDecision.Accepted
        }

        val header =
            proxyAuthorization?.trim()
                ?: return ProxyAuthenticationDecision.rejected(ProxyAuthenticationRejectionReason.MissingAuthorization)
        if (header.isEmpty()) {
            return ProxyAuthenticationDecision.rejected(ProxyAuthenticationRejectionReason.MissingAuthorization)
        }

        val firstWhitespace = header.indexOfFirst(Char::isWhitespace)
        if (firstWhitespace == -1) {
            return ProxyAuthenticationDecision.rejected(ProxyAuthenticationRejectionReason.UnsupportedScheme)
        }

        val scheme = header.substring(0, firstWhitespace)
        if (!scheme.equals(BASIC_SCHEME, ignoreCase = true)) {
            return ProxyAuthenticationDecision.rejected(ProxyAuthenticationRejectionReason.UnsupportedScheme)
        }

        val encodedCredentials = header.substring(firstWhitespace).trim()
        val suppliedCredential =
            encodedCredentials.decodeBasicCredential()
                ?: return ProxyAuthenticationDecision.rejected(ProxyAuthenticationRejectionReason.MalformedCredentials)

        return if (suppliedCredential == config.credential.canonicalBasicPayload()) {
            ProxyAuthenticationDecision.Accepted
        } else {
            ProxyAuthenticationDecision.rejected(ProxyAuthenticationRejectionReason.CredentialMismatch)
        }
    }

    private fun String.decodeBasicCredential(): String? {
        if (isBlank()) {
            return null
        }

        val decodedBytes =
            try {
                Base64.getDecoder().decode(this)
            } catch (_: IllegalArgumentException) {
                return null
            }

        val decoded =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decodedBytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                return null
            }

        return decoded.takeIf { ':' in it }
    }
}

private const val BASIC_SCHEME = "Basic"
