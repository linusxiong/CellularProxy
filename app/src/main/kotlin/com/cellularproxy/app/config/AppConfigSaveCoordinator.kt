package com.cellularproxy.app.config

import com.cellularproxy.shared.config.AppConfig
import com.cellularproxy.shared.config.ConfigValidationError

sealed interface AppConfigSaveResult {
    data class Saved(
        val plainConfig: AppConfig,
        val sensitiveConfig: SensitiveConfig,
    ) : AppConfigSaveResult

    data class InvalidPlainConfig(
        val errors: List<ConfigValidationError>,
    ) : AppConfigSaveResult
}

class AppConfigSaveCoordinator(
    private val plainRepository: PlainConfigDataStoreRepository,
    private val sensitiveRepository: SensitiveConfigRepository,
) {
    suspend fun save(
        plainConfig: AppConfig,
        sensitiveConfig: SensitiveConfig,
    ): AppConfigSaveResult {
        val reconciledPlainConfig = plainConfig.copy(
            cloudflare = plainConfig.cloudflare.copy(
                tunnelTokenPresent = sensitiveConfig.cloudflareTunnelToken != null,
            ),
        )
        val validationResult = reconciledPlainConfig.validate()
        if (!validationResult.isValid) {
            return AppConfigSaveResult.InvalidPlainConfig(validationResult.errors)
        }

        sensitiveRepository.save(sensitiveConfig)
        plainRepository.save(reconciledPlainConfig)

        return AppConfigSaveResult.Saved(
            plainConfig = reconciledPlainConfig,
            sensitiveConfig = sensitiveConfig,
        )
    }
}
