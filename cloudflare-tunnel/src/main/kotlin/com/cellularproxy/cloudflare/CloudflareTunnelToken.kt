package com.cellularproxy.cloudflare

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class CloudflareTunnelCredentials internal constructor(
    val accountTag: String,
    val tunnelId: UUID,
    tunnelSecret: ByteArray,
    val endpoint: String?,
) {
    val tunnelSecret: ByteArray = tunnelSecret.copyOf()
        get() = field.copyOf()

    override fun toString(): String =
        "CloudflareTunnelCredentials(redacted=true)"
}

class CloudflareTunnelToken private constructor(
    val credentials: CloudflareTunnelCredentials,
) {
    override fun toString(): String =
        "CloudflareTunnelToken(redacted=true)"

    companion object {
        fun parse(rawToken: String): CloudflareTunnelTokenParseResult {
            val token = rawToken.trim()
            if (token.isEmpty()) {
                return CloudflareTunnelTokenParseResult.Invalid(CloudflareTunnelTokenInvalidReason.Blank)
            }

            val decodedBytes = try {
                Base64.getDecoder().decode(token)
            } catch (_: IllegalArgumentException) {
                return CloudflareTunnelTokenParseResult.Invalid(CloudflareTunnelTokenInvalidReason.NotBase64)
            }
            val decoded = decodedBytes.decodeStrictUtf8()
                ?: return CloudflareTunnelTokenParseResult.Invalid(CloudflareTunnelTokenInvalidReason.NotJsonObject)

            val jsonObject = try {
                Json.parseToJsonElement(decoded) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return CloudflareTunnelTokenParseResult.Invalid(CloudflareTunnelTokenInvalidReason.NotJsonObject)

            val accountTag = jsonObject.stringField("a")
                ?: return CloudflareTunnelTokenParseResult.Invalid(CloudflareTunnelTokenInvalidReason.MissingAccountTag)
            if (accountTag.isBlank()) {
                return CloudflareTunnelTokenParseResult.Invalid(CloudflareTunnelTokenInvalidReason.MissingAccountTag)
            }

            val tunnelSecretText = jsonObject.stringField("s")
                ?: return CloudflareTunnelTokenParseResult.Invalid(CloudflareTunnelTokenInvalidReason.MissingTunnelSecret)
            if (tunnelSecretText.isBlank()) {
                return CloudflareTunnelTokenParseResult.Invalid(CloudflareTunnelTokenInvalidReason.MissingTunnelSecret)
            }
            val tunnelSecret = try {
                Base64.getDecoder().decode(tunnelSecretText)
            } catch (_: IllegalArgumentException) {
                return CloudflareTunnelTokenParseResult.Invalid(CloudflareTunnelTokenInvalidReason.InvalidTunnelSecret)
            }
            if (tunnelSecret.size < MIN_TUNNEL_SECRET_BYTES) {
                return CloudflareTunnelTokenParseResult.Invalid(CloudflareTunnelTokenInvalidReason.InvalidTunnelSecret)
            }

            val tunnelIdText = jsonObject.stringField("t")
                ?: return CloudflareTunnelTokenParseResult.Invalid(CloudflareTunnelTokenInvalidReason.MissingTunnelId)
            val tunnelId = try {
                UUID.fromString(tunnelIdText)
            } catch (_: IllegalArgumentException) {
                return CloudflareTunnelTokenParseResult.Invalid(CloudflareTunnelTokenInvalidReason.InvalidTunnelId)
            }

            val credentials = CloudflareTunnelCredentials(
                accountTag = accountTag,
                tunnelId = tunnelId,
                tunnelSecret = tunnelSecret,
                endpoint = jsonObject.stringField("e"),
            )

            return CloudflareTunnelTokenParseResult.Valid(
                CloudflareTunnelToken(credentials = credentials),
            )
        }
    }
}

sealed interface CloudflareTunnelTokenParseResult {
    class Valid(
        val token: CloudflareTunnelToken,
    ) : CloudflareTunnelTokenParseResult {
        override fun toString(): String =
            "CloudflareTunnelTokenParseResult.Valid(token=<redacted>)"
    }

    class Invalid(
        val reason: CloudflareTunnelTokenInvalidReason,
    ) : CloudflareTunnelTokenParseResult {
        override fun toString(): String =
            "CloudflareTunnelTokenParseResult.Invalid(reason=$reason)"
    }
}

enum class CloudflareTunnelTokenInvalidReason {
    Blank,
    NotBase64,
    NotJsonObject,
    MissingAccountTag,
    MissingTunnelSecret,
    InvalidTunnelSecret,
    MissingTunnelId,
    InvalidTunnelId,
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.jsonPrimitive
        ?.content

private fun ByteArray.decodeStrictUtf8(): String? =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

private const val MIN_TUNNEL_SECRET_BYTES = 32
