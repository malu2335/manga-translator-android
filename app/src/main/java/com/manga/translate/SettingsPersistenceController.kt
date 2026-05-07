package com.manga.translate

internal data class SettingsMainForm(
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val apiFormat: ApiFormat,
    val apiTimeoutSeconds: Int,
    val apiRetryCount: Int,
    val maxConcurrency: Int
)

internal data class SettingsPersistenceResult(
    val apiTimeoutSeconds: Int,
    val apiRetryCount: Int,
    val maxConcurrency: Int,
    val concurrencySaved: Boolean
)

internal class SettingsPersistenceController(
    private val settingsStore: SettingsStore
) {
    fun persistMainForm(form: SettingsMainForm): SettingsPersistenceResult {
        settingsStore.save(
            ApiSettings(
                apiUrl = form.apiUrl,
                apiKey = form.apiKey,
                modelName = form.modelName,
                apiFormat = form.apiFormat,
                providerId = PRIMARY_PROVIDER_ID
            )
        )
        settingsStore.saveApiTimeoutSeconds(form.apiTimeoutSeconds)
        settingsStore.saveApiRetryCount(form.apiRetryCount)
        val minimumConcurrency = settingsStore.loadMainTranslationProviderPool().size.coerceAtLeast(1)
        val concurrencySaved = form.maxConcurrency >= minimumConcurrency
        if (concurrencySaved) {
            settingsStore.saveMaxConcurrency(form.maxConcurrency)
        }
        return SettingsPersistenceResult(
            apiTimeoutSeconds = settingsStore.loadApiTimeoutSeconds(),
            apiRetryCount = settingsStore.loadApiRetryCount(),
            maxConcurrency = settingsStore.loadMaxConcurrency(),
            concurrencySaved = concurrencySaved
        )
    }
}
