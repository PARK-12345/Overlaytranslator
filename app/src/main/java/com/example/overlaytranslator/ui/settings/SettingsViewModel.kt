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
            Log.d(TAG, "All settings loaded into ViewModel. GeneralSettings: ${_generalSettings.value}")
        }
    }

    fun saveAllSettings(
        style: TranslationTextStyle,
        buttonSettings: OverlayButtonSettings,
        general: GeneralSettings
    ) {
        viewModelScope.launch {
            try {
                // 유효성 검사 추가 (예: 캐시 크기, 유사도 허용치)
                val validatedGeneral = general.copy(
                    maxCacheSize = general.maxCacheSize.coerceAtLeast(10), // 최소 캐시 크기 제한 예시
                    similarityTolerance = general.similarityTolerance.coerceAtLeast(0) // 최소 유사도 허용치
                )

                settingsRepository.saveTranslationTextStyle(style)
                settingsRepository.saveOverlayButtonSettings(buttonSettings)
                settingsRepository.saveGeneralSettings(validatedGeneral) // 유효성 검사된 설정 저장

                // LiveData 업데이트
                _translationTextStyle.value = style
                _overlayButtonSettings.value = buttonSettings
                _generalSettings.value = validatedGeneral // 유효성 검사된 설정으로 업데이트

                _toastMessage.value = "설정이 저장되었습니다."
                Log.d(TAG, "All settings saved successfully. General: $validatedGeneral")
            } catch (e: Exception) {
                _toastMessage.value = "설정 저장에 실패했습니다: ${e.message}"
                Log.e(TAG, "Failed to save settings", e)
            }
        }
    }

    fun onToastShown() {
        _toastMessage.value = null
    }

    // TranslationTextStyle 업데이트 함수들 (기존과 동일)
    fun updateFontSize(size: Float?) {
        val currentStyle = _translationTextStyle.value ?: return
        if (currentStyle.fontSize != size) {
            _translationTextStyle.value = currentStyle.copy(fontSize = size)
        }
    }
    fun updateTextColor(color: String) {
        val currentStyle = _translationTextStyle.value ?: return
        if (currentStyle.textColor != color) {
            _translationTextStyle.value = currentStyle.copy(textColor = color)
        }
    }
    fun updateBackgroundColor(color: String) {
        val currentStyle = _translationTextStyle.value ?: return
        if (currentStyle.backgroundColor != color) {
            _translationTextStyle.value = currentStyle.copy(backgroundColor = color)
        }
    }
    fun updateBackgroundAlpha(alpha: Int) {
        val currentStyle = _translationTextStyle.value ?: return
        if (currentStyle.backgroundAlpha != alpha) {
            _translationTextStyle.value = currentStyle.copy(backgroundAlpha = alpha)
        }
    }
    fun updateLineSpacing(spacing: Float?) {
        val currentStyle = _translationTextStyle.value ?: return
        if (currentStyle.lineSpacingExtra != spacing) {
            _translationTextStyle.value = currentStyle.copy(lineSpacingExtra = spacing)
        }
    }
    fun updateTextAlignment(alignment: Int) {
        val currentStyle = _translationTextStyle.value ?: return
        if (currentStyle.textAlignment != alignment) {
            _translationTextStyle.value = currentStyle.copy(textAlignment = alignment)
        }
    }

    // OverlayButtonSettings 업데이트 함수 (기존과 동일)
    fun updateButtonSize(size: Int) {
        val currentButton = _overlayButtonSettings.value ?: return
        if (currentButton.size != size) {
            _overlayButtonSettings.value = currentButton.copy(size = size)
        }
    }

    // GeneralSettings 업데이트 함수들
    fun updateGeminiApiKey(apiKey: String) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.geminiApiKey != apiKey) {
            _generalSettings.value = currentGeneral.copy(geminiApiKey = apiKey)
        }
    }
    fun updateGeminiPrompt(prompt: String) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.geminiPrompt != prompt) {
            _generalSettings.value = currentGeneral.copy(geminiPrompt = prompt)
        }
    }
    fun updateGeminiModelName(modelName: String) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.geminiModelName != modelName) {
            _generalSettings.value = currentGeneral.copy(geminiModelName = modelName)
        }
    }
    fun updateThinkingBudget(budget: Int?) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.thinkingBudget != budget) {
            _generalSettings.value = currentGeneral.copy(thinkingBudget = budget)
        }
    }
    fun updateTemperature(temperature: Float?) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.temperature != temperature) {
            _generalSettings.value = currentGeneral.copy(temperature = temperature)
        }
    }
    fun updateForbiddenKeywords(keywords: String) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.forbiddenKeywords != keywords) {
            _generalSettings.value = currentGeneral.copy(forbiddenKeywords = keywords)
        }
    }
    fun updateCaptureDelay(delay: Int) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.captureDelayMs != delay) {
            _generalSettings.value = currentGeneral.copy(captureDelayMs = delay.coerceAtLeast(0)) // 0 이상
        }
    }
    fun updateAutoDetectLanguage(autoDetect: Boolean) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.autoDetectSourceLanguage != autoDetect) {
            _generalSettings.value = currentGeneral.copy(autoDetectSourceLanguage = autoDetect)
        }
    }
    fun updateDefaultSourceLanguage(language: String) {
        val currentGeneral = _generalSettings.value ?: return
        if (currentGeneral.defaultSourceLanguage != language) {
            _generalSettings.value = currentGeneral.copy(defaultSourceLanguage = language)
        }
    }

    // 새로운 설정값 업데이트 함수
    fun updateSimilarityTolerance(tolerance: Int) {
        val currentGeneral = _generalSettings.value ?: return
        val newTolerance = tolerance.coerceAtLeast(0) // 0 이상으로 제한
        if (currentGeneral.similarityTolerance != newTolerance) {
            _generalSettings.value = currentGeneral.copy(similarityTolerance = newTolerance)
            Log.d(TAG, "SimilarityTolerance updated to $newTolerance")
        }
    }

    fun updateMaxCacheSize(size: Int) {
        val currentGeneral = _generalSettings.value ?: return
        val newSize = size.coerceAtLeast(10) // 최소 10개 이상으로 제한 (예시)
        if (currentGeneral.maxCacheSize != newSize) {
            _generalSettings.value = currentGeneral.copy(maxCacheSize = newSize)
            Log.d(TAG, "MaxCacheSize updated to $newSize")
        }
    }
}
