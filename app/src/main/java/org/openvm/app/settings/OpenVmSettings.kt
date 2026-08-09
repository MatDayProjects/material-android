package org.openvm.app.settings

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class LanguageMode {
    ENGLISH,
    CANTONESE,
    BILINGUAL,
}

@Serializable
data class OpenVmSettings(
    val schemaVersion: Int = 1,
    val languageMode: LanguageMode = LanguageMode.ENGLISH,
    val showEmojis: Boolean = true,
    val englishFunnyLevel: Int = 2,
    val cantoneseFunnyLevel: Int = 3,
    val displayName: String = "OpenVM",
    val darkTheme: Boolean = false,
)

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val key = "settings_json"

    fun read(): OpenVmSettings = runCatching {
        preferences.getString(key, null)?.let { json.decodeFromString(OpenVmSettings.serializer(), it) }
    }.getOrNull() ?: OpenVmSettings()

    fun write(settings: OpenVmSettings) {
        preferences.edit().putString(key, json.encodeToString(OpenVmSettings.serializer(), settings)).apply()
    }

    companion object { private const val PREFERENCES_NAME = "openvm_settings" }
}

