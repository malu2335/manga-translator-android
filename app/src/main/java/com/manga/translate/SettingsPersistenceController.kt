package com.manga.translate

internal data class SettingsMainForm(
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val apiFormat: ApiFormat,
    val apiTimeoutSeconds: Int,
    val maxConcurrency: Int
)

internal data class SettingsPersistenceResult(
    val apiTimeoutSeconds: Int,
    val maxConcurrency: Int
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
                apiFormat = form.apiFormat
            )
        )
        settingsStore.saveApiTimeoutSeconds(form.apiTimeoutSeconds)
        settingsStore.saveMaxConcurrency(form.maxConcurrency)
        return SettingsPersistenceResult(
            apiTimeoutSeconds = settingsStore.loadApiTimeoutSeconds(),
            maxConcurrency = settingsStore.loadMaxConcurrency()
        )
    }
}
