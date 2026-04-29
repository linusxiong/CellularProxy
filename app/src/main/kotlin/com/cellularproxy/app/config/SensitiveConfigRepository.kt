package com.cellularproxy.app.config

import com.cellularproxy.shared.proxy.ProxyCredential

data class SensitiveConfig(
    val proxyCredential: ProxyCredential,
    val managementApiToken: String,
    val cloudflareTunnelToken: String? = null,
) {
    init {
        require(managementApiToken.isNotBlank()) { "Management API token must not be blank" }
        require(cloudflareTunnelToken == null || cloudflareTunnelToken.isNotBlank()) {
            "Cloudflare tunnel token must not be blank when present"
        }
    }

    override fun toString(): String = "SensitiveConfig(proxyCredential=[REDACTED], managementApiToken=[REDACTED], " +
        "cloudflareTunnelToken=${if (cloudflareTunnelToken == null) "absent" else "[REDACTED]"})"
}

object SensitiveConfigSecretKeys {
    const val proxyAuthCredential: String = "proxy.authCredential"
    const val managementApiToken: String = "management.apiToken"
    const val cloudflareTunnelToken: String = "cloudflare.tunnelToken"
}

interface SensitiveKeyValueStore {
    fun read(key: String): String?

    fun replace(
        encryptedValues: Map<String, String>,
        keysToRemove: Set<String>,
    )

    fun clear(keys: Set<String>)
}

interface SensitiveValueCipher {
    fun encrypt(plainText: String): String

    fun decrypt(encryptedValue: String): String?
}

sealed interface SensitiveConfigLoadResult {
    data class Loaded(
        val config: SensitiveConfig,
    ) : SensitiveConfigLoadResult

    data object MissingRequiredSecrets : SensitiveConfigLoadResult

    data class Invalid(
        val reason: SensitiveConfigInvalidReason,
    ) : SensitiveConfigLoadResult
}

enum class SensitiveConfigInvalidReason {
    InvalidProxyCredential,
    InvalidManagementApiToken,
    InvalidCloudflareTunnelToken,
    UndecryptableSecret,
    PartiallyMissingRequiredSecrets,
}

class SensitiveConfigRepository(
    private val store: SensitiveKeyValueStore,
    private val cipher: SensitiveValueCipher,
) {
    fun load(): SensitiveConfigLoadResult {
        val encryptedProxyCredential = store.read(SensitiveConfigSecretKeys.proxyAuthCredential)
        val encryptedManagementApiToken = store.read(SensitiveConfigSecretKeys.managementApiToken)
        val encryptedCloudflareTunnelToken = store.read(SensitiveConfigSecretKeys.cloudflareTunnelToken)

        if (encryptedProxyCredential == null || encryptedManagementApiToken == null) {
            return if (
                encryptedProxyCredential == null &&
                encryptedManagementApiToken == null &&
                encryptedCloudflareTunnelToken == null
            ) {
                SensitiveConfigLoadResult.MissingRequiredSecrets
            } else {
                SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.PartiallyMissingRequiredSecrets)
            }
        }

        val proxyCredentialText =
            cipher.decrypt(encryptedProxyCredential)
                ?: return SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.UndecryptableSecret)
        val managementApiToken =
            cipher.decrypt(encryptedManagementApiToken)
                ?: return SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.UndecryptableSecret)
        val cloudflareTunnelToken =
            encryptedCloudflareTunnelToken
                ?.let { encryptedToken ->
                    cipher.decrypt(encryptedToken)
                        ?: return SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.UndecryptableSecret)
                }

        return sensitiveConfigFromPlainText(
            proxyCredentialText = proxyCredentialText,
            managementApiToken = managementApiToken,
            cloudflareTunnelToken = cloudflareTunnelToken,
        )
    }

    fun save(config: SensitiveConfig) {
        val encryptedValues =
            buildMap {
                put(
                    SensitiveConfigSecretKeys.proxyAuthCredential,
                    cipher.encrypt(config.proxyCredential.canonicalBasicPayload()),
                )
                put(
                    SensitiveConfigSecretKeys.managementApiToken,
                    cipher.encrypt(config.managementApiToken),
                )
                config.cloudflareTunnelToken?.let { token ->
                    put(SensitiveConfigSecretKeys.cloudflareTunnelToken, cipher.encrypt(token))
                }
            }
        val keysToRemove =
            if (config.cloudflareTunnelToken == null) {
                setOf(SensitiveConfigSecretKeys.cloudflareTunnelToken)
            } else {
                emptySet()
            }

        store.replace(
            encryptedValues = encryptedValues,
            keysToRemove = keysToRemove,
        )
    }

    fun clear() {
        store.clear(REPOSITORY_SECRET_KEYS)
    }

    private fun sensitiveConfigFromPlainText(
        proxyCredentialText: String,
        managementApiToken: String,
        cloudflareTunnelToken: String?,
    ): SensitiveConfigLoadResult {
        val proxyCredential =
            proxyCredentialText.toProxyCredential()
                ?: return SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.InvalidProxyCredential)
        if (managementApiToken.isBlank()) {
            return SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.InvalidManagementApiToken)
        }
        if (cloudflareTunnelToken != null && cloudflareTunnelToken.isBlank()) {
            return SensitiveConfigLoadResult.Invalid(SensitiveConfigInvalidReason.InvalidCloudflareTunnelToken)
        }

        return SensitiveConfigLoadResult.Loaded(
            SensitiveConfig(
                proxyCredential = proxyCredential,
                managementApiToken = managementApiToken,
                cloudflareTunnelToken = cloudflareTunnelToken,
            ),
        )
    }
}

private val REPOSITORY_SECRET_KEYS: Set<String> =
    setOf(
        SensitiveConfigSecretKeys.proxyAuthCredential,
        SensitiveConfigSecretKeys.managementApiToken,
        SensitiveConfigSecretKeys.cloudflareTunnelToken,
    )

private fun String.toProxyCredential(): ProxyCredential? {
    val separatorIndex = indexOf(':')
    if (separatorIndex <= 0 || separatorIndex == lastIndex) {
        return null
    }

    return runCatching {
        ProxyCredential(
            username = substring(0, separatorIndex),
            password = substring(separatorIndex + 1),
        )
    }.getOrNull()
}
