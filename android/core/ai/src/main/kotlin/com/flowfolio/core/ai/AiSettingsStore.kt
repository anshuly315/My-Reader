package com.flowfolio.core.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The AI choices, remembered across launches. The API key is sealed by the Keystore ([SecretBox]). */
@Singleton
class AiSettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    private fun read(): AiConfig {
        val presetId = prefs.getString(PRESET, AiPreset.ON_DEVICE_ID) ?: AiPreset.ON_DEVICE_ID
        val preset = AiPreset.byId(presetId)
        return AiConfig(
            presetId = preset.id,
            backend = preset.backend,
            baseUrl = prefs.getString(BASE_URL, null) ?: preset.baseUrl,
            model = prefs.getString(MODEL, null) ?: preset.defaultModel,
            apiKey = SecretBox.open(prefs.getString(KEY_SEALED, "").orEmpty()),
            language = prefs.getString(LANGUAGE, null) ?: java.util.Locale.getDefault().language
        )
    }

    /** Switches provider, filling in that provider's address and model; the key is kept per provider. */
    fun choosePreset(preset: AiPreset) {
        prefs.edit {
            putString(PRESET, preset.id)
            putString(BASE_URL, preset.baseUrl)
            putString(MODEL, preset.defaultModel)
            putString(KEY_SEALED, prefs.getString(KEY_PREFIX + preset.id, "").orEmpty())
        }
        _config.value = read()
    }

    fun setBaseUrl(url: String) = save { putString(BASE_URL, url.trim()) }

    fun setModel(model: String) = save { putString(MODEL, model.trim()) }

    fun setLanguage(code: String) = save { putString(LANGUAGE, code) }

    /** Seals and stores [key] for the current provider. A key the Keystore cannot seal is not stored at all. */
    fun setApiKey(key: String) {
        val sealed = SecretBox.seal(key.trim()) ?: ""
        val id = _config.value.presetId
        save {
            putString(KEY_SEALED, sealed)
            putString(KEY_PREFIX + id, sealed)
        }
    }

    private inline fun save(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit { block() }
        _config.value = read()
    }

    private companion object {
        const val PRESET = "preset"
        const val BASE_URL = "base_url"
        const val MODEL = "model"
        const val LANGUAGE = "language"
        const val KEY_SEALED = "key_sealed"
        const val KEY_PREFIX = "key_for_"
    }
}
