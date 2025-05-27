package com.example.overlaytranslator.ui.settings

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.overlaytranslator.data.GeneralSettings
import com.example.overlaytranslator.data.OverlayButtonSettings
import com.example.overlaytranslator.data.SettingsRepository
import com.example.overlaytranslator.data.TranslationTextStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    private val _translationTextStyle = MutableLiveData<TranslationTextStyle>()
    val translationTextStyle: LiveData<TranslationTextStyle> = _translationTextStyle

    private val _overlayButtonSettings = MutableLiveData<OverlayButtonSettings>()
    val overlayButtonSettings: LiveData<OverlayButtonSettings> = _overlayButtonSettings

    private val _generalSettings = MutableLiveData<GeneralSettings>()
    val generalSettings: LiveData<GeneralSettings> = _generalSettings

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    init {
        loadAllSettings()
    }

    private fun loadAllSettings() {
        viewModelScope.launch {
            _translationTextStyle.value = settingsRepository.getTranslationTextStyle()
            _overlayButtonSettings.value = settingsRepository.getOverlayButtonSettings()
            _generalSettings.value = settingsRepository.getGeneralSettings()
            Log.d(TAG, "All settings loaded into ViewModel.")
        }
    }

    fun saveAllSettings(
        style: TranslationTextStyle,
        buttonSettings: OverlayButtonSettings,
        general: GeneralSettings
    ) {
        viewModelScope.launch {
            try {
                settingsRepository.saveTranslationTextStyle(style)
                settingsRepository.saveOverlayButtonSettings(buttonSettings)
                settingsRepository.saveGeneralSettings(general)

                // LiveData 업데이트
                _translationTextStyle.value = style
                _overlayButtonSettings.value = buttonSettings
                _generalSettings.value = general

                _toastMessage.value = "설정이 저장되었습니다."
                Log.d(TAG, "All settings saved successfully.")
            } catch (e: Exception) {
                _toastMessage.value = "설정 저장에 실패했습니다: ${e.message}"
                Log.e(TAG, "Failed to save settings", e)
            }
        }
    }

    fun onToastShown() {
        _toastMessage.value = null
    }

    // Nullable Float? 타입으로 변경
    fun updateFontSize(size: Float?) {
        val currentStyle = _translationTextStyle.value ?: return
        if (currentStyle.fontSize != size) {
            _translationTextStyle.value = currentStyle.copy(fontSize = size)
            Log.d(TAG, "FontSize updated to $size")
        }
    }

    fun updateTextColor(color: String) {
        val currentStyle = _translationTextStyle.value ?: return
        if (currentStyle.textColor != color) {
            _translationTextStyle.value = currentStyle.copy(textColor = color)
            Log.d(TAG, "TextColor updated to $color")
        }
    }

    fun updateBackgroundColor(color: String) {
        val currentStyle = _translationTextStyle.value ?: return
        if (currentStyle.backgroundColor != color) {
            _translationTextStyle.value = currentStyle.copy(backgroundColor = color)
            Log.d(TAG, "BackgroundColor updated to $color")
        }
    }
    fun updateBackgroundAlpha(alpha: Int) {
        val currentStyle = _translationTextStyle.value ?: return
        if (currentStyle.backgroundAlpha != alpha) {
            _translationTextStyle.value = currentStyle.copy(backgroundAlpha = alpha)
            Log.d(TAG, "BackgroundAlpha updated to $alpha")
        }
    }

    // Nullable Float? 타입으로 변경
    fun updateLineSpacing(spacing: Float?) {
        val currentStyle = _translationTextStyle.value ?: return
        if (currentStyle.lineSpacingExtra != spacing) {
            _translationTextStyle.value = currentStyle.copy(lineSpacingExtra = spacing)
            Log.d(TAG, "LineSpacing updated to $spacing")
        }
    }

    fun updateTextAlignment(alignment: Int) {
        val currentStyle = _translationTextStyle.value ?: return
        if (currentStyle.textAlignment != alignment) {
            _translationTextStyle.value = currentStyle.copy(textAlignment = alignment)
            Log.d(TAG, "TextAlignment updated to $alignment")
        }
    }

    fun updateGeminiApiKey(apiKey: String) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.geminiApiKey != apiKey) {
            _generalSettings.value = currentGeneral.copy(geminiApiKey = apiKey)
            Log.d(TAG, "GeminiApiKey updated.")
        }
    }
    fun updateGeminiPrompt(prompt: String) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.geminiPrompt != prompt) {
            _generalSettings.value = currentGeneral.copy(geminiPrompt = prompt)
            Log.d(TAG, "GeminiPrompt updated.")
        }
    }

    fun updateGeminiModelName(modelName: String) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.geminiModelName != modelName) {
            _generalSettings.value = currentGeneral.copy(geminiModelName = modelName)
            Log.d(TAG, "GeminiModelName updated to: $modelName")
        }
    }

    fun updateThinkingBudget(budget: Int?) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.thinkingBudget != budget) {
            _generalSettings.value = currentGeneral.copy(thinkingBudget = budget)
            Log.d(TAG, "ThinkingBudget updated to: $budget")
        }
    }

    // Nullable Float? 타입으로 변경
    fun updateTemperature(temperature: Float?) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.temperature != temperature) {
            _generalSettings.value = currentGeneral.copy(temperature = temperature)
            Log.d(TAG, "Temperature updated to: $temperature")
        }
    }

    fun updateForbiddenKeywords(keywords: String) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.forbiddenKeywords != keywords) {
            _generalSettings.value = currentGeneral.copy(forbiddenKeywords = keywords)
            Log.d(TAG, "ForbiddenKeywords updated to: $keywords")
        }
    }

    fun updateButtonSize(size: Int) {
        val currentButton = _overlayButtonSettings.value ?: return
        if (currentButton.size != size) {
            _overlayButtonSettings.value = currentButton.copy(size = size)
            Log.d(TAG, "ButtonSize updated to $size")
        }
    }
    fun updateCaptureDelay(delay: Int) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.captureDelayMs != delay) {
            _generalSettings.value = currentGeneral.copy(captureDelayMs = delay)
            Log.d(TAG, "CaptureDelay updated to $delay")
        }
    }
    fun updateAutoDetectLanguage(autoDetect: Boolean) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.autoDetectSourceLanguage != autoDetect) {
            _generalSettings.value = currentGeneral.copy(autoDetectSourceLanguage = autoDetect)
            Log.d(TAG, "AutoDetectLanguage updated to $autoDetect")
        }
    }
    fun updateDefaultSourceLanguage(language: String) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.defaultSourceLanguage != language) {
            _generalSettings.value = currentGeneral.copy(defaultSourceLanguage = language)
            Log.d(TAG, "DefaultSourceLanguage updated to $language")
        }
    }
}
