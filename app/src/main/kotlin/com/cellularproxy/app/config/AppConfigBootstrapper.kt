package com.cellularproxy.app.config

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.proxy.ProxyCredential
import java.security.SecureRandom
import java.util.Base64

sealed interface AppConfigBootstrapResult {
    data class Ready(
        val plainConfig: AppConfig,
        val sensitiveConfig: SensitiveConfig,
        val createdDefaultSecrets: Boolean,
        val reconciledPlainConfig: Boolean,
    ) : AppConfigBootstrapResult

    data class InvalidSensitiveConfig(
        val reason: SensitiveConfigInvalidReason,
    ) : AppConfigBootstrapResult
}

interface SensitiveConfigGenerator {
    fun generateDefaultSensitiveConfig(): SensitiveConfig
}

class SecureRandomSensitiveConfigGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : SensitiveConfigGenerator {
    override fun generateDefaultSensitiveConfig(): SensitiveConfig = SensitiveConfig(
        proxyCredential =
            ProxyCredential(
                username = "proxy-" + generateToken(byteCount = 9),
                password = generateToken(byteCount = 24),
            ),
        managementApiToken = generateToken(byteCount = 32),
    )

    private fun generateToken(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

class AppConfigBootstrapper(
    private val plainRepository: PlainConfigDataStoreRepository,
    private val sensitiveRepository: SensitiveConfigRepository,
    private val generator: SensitiveConfigGenerator = SecureRandomSensitiveConfigGenerator(),
) {
    suspend fun loadOrCreate(): AppConfigBootstrapResult {
        val plainConfig = plainRepository.load()

        val (sensitiveConfig, createdDefaultSecrets) =
            when (val sensitiveLoadResult = sensitiveRepository.load()) {
                SensitiveConfigLoadResult.MissingRequiredSecrets -> {
                    generator
                        .generateDefaultSensitiveConfig()
                        .also(sensitiveRepository::save) to true
                }

                is SensitiveConfigLoadResult.Invalid -> {
                    return AppConfigBootstrapResult.InvalidSensitiveConfig(sensitiveLoadResult.reason)
                }

                is SensitiveConfigLoadResult.Loaded -> sensitiveLoadResult.config to false
            }

        val (proxyReconciledConfig, proxyReconciled) =
            reconcileGeneratedDefaultProxyAuthentication(
                plainConfig = plainConfig,
                sensitiveConfig = sensitiveConfig,
                generatedDefaultSecrets = createdDefaultSecrets,
            )
        val (reconciledPlainConfig, cloudflareReconciled) =
            reconcileCloudflareTokenPresence(
                plainConfig = proxyReconciledConfig,
                sensitiveConfig = sensitiveConfig,
            )

        return AppConfigBootstrapResult.Ready(
            plainConfig = reconciledPlainConfig,
            sensitiveConfig = sensitiveConfig,
            createdDefaultSecrets = createdDefaultSecrets,
            reconciledPlainConfig = proxyReconciled || cloudflareReconciled,
        )
    }

    private suspend fun reconcileGeneratedDefaultProxyAuthentication(
        plainConfig: AppConfig,
        sensitiveConfig: SensitiveConfig,
        generatedDefaultSecrets: Boolean,
    ): Pair<AppConfig, Boolean> {
        if (!plainConfig.isDefaultProxyAuthenticationConfiguration()) {
            return plainConfig to false
        }
        if (!generatedDefaultSecrets && !sensitiveConfig.proxyCredential.looksGenerated()) {
            return plainConfig to false
        }

        val reconciledConfig = plainConfig.copy(proxy = plainConfig.proxy.copy(authEnabled = false))
        plainRepository.save(reconciledConfig)
        return reconciledConfig to true
    }

    private suspend fun reconcileCloudflareTokenPresence(
        plainConfig: AppConfig,
        sensitiveConfig: SensitiveConfig,
    ): Pair<AppConfig, Boolean> {
        val cloudflareTokenPresent = sensitiveConfig.cloudflareTunnelToken != null
        if (plainConfig.cloudflare.tunnelTokenPresent == cloudflareTokenPresent) {
            return plainConfig to false
        }

        val reconciledConfig =
            plainConfig.copy(
                cloudflare =
                    plainConfig.cloudflare.copy(
                        tunnelTokenPresent = cloudflareTokenPresent,
                    ),
            )
        plainRepository.save(reconciledConfig)
        return reconciledConfig to true
    }
}

private fun AppConfig.isDefaultProxyAuthenticationConfiguration(): Boolean {
    val defaultProxy = AppConfig.default().proxy
    return proxy.authEnabled &&
        proxy.listenHost == defaultProxy.listenHost &&
        proxy.listenPort == defaultProxy.listenPort &&
        proxy.maxConcurrentConnections == defaultProxy.maxConcurrentConnections
}

private fun ProxyCredential.looksGenerated(): Boolean = username.startsWith(GENERATED_PROXY_USERNAME_PREFIX)

private const val GENERATED_PROXY_USERNAME_PREFIX = "proxy-"
