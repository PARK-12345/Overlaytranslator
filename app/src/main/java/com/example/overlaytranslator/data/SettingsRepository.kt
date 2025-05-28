package com.example.overlaytranslator.data

import android.content.SharedPreferences
import android.util.Log
// import android.view.Gravity // 이 파일에서 직접 사용하지 않으므로 제거 가능
import androidx.core.content.edit
// import com.google.gson.Gson // 현재 구현에서는 직접 사용되지 않음
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

interface SettingsRepository {
    fun getTranslationTextStyle(): TranslationTextStyle
    fun saveTranslationTextStyle(style: TranslationTextStyle)
    val translationTextStyleFlow: StateFlow<TranslationTextStyle>

    fun getOverlayButtonSettings(): OverlayButtonSettings
    fun saveOverlayButtonSettings(settings: OverlayButtonSettings)
    val overlayButtonSettingsFlow: StateFlow<OverlayButtonSettings>

    fun getGeneralSettings(): GeneralSettings
    fun saveGeneralSettings(settings: GeneralSettings)
    val generalSettingsFlow: StateFlow<GeneralSettings>

    fun getGeminiApiKey(): String
    fun saveGeminiApiKey(apiKey: String)

    fun saveOverlayButtonPosition(x: Int, y: Int, screenWidth: Int, screenHeight: Int)
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val prefs: SharedPreferences
) : SettingsRepository {

    companion object {
        private const val TAG = "SettingsRepositoryImpl"
        // Translation Text Style Keys
        private const val KEY_FONT_SIZE = "font_size_str" // String으로 저장함을 명시
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_BACKGROUND_COLOR = "background_color"
        private const val KEY_BACKGROUND_ALPHA = "background_alpha"
        private const val KEY_LINE_SPACING = "line_spacing_str" // String으로 저장함을 명시
        private const val KEY_TEXT_ALIGNMENT = "text_alignment"

        // Overlay Button Settings Keys
        private const val KEY_BUTTON_SIZE = "button_size"
        private const val KEY_BUTTON_LAST_X = "button_last_x"
        private const val KEY_BUTTON_LAST_Y = "button_last_y"
        private const val KEY_BUTTON_REF_SCREEN_WIDTH = "button_ref_screen_width"
        private const val KEY_BUTTON_REF_SCREEN_HEIGHT = "button_ref_screen_height"


        // General Settings Keys
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_PROMPT = "gemini_prompt"
        private const val KEY_GEMINI_MODEL_NAME = "gemini_model_name"
        private const val KEY_THINKING_BUDGET = "thinking_budget_int" // Int로 저장함을 명시 (null 가능)
        private const val KEY_TEMPERATURE = "temperature_str" // String으로 저장함을 명시
        private const val KEY_CAPTURE_DELAY_MS = "capture_delay_ms"
        private const val KEY_AUTO_DETECT_SOURCE_LANG = "auto_detect_source_lang"
        private const val KEY_DEFAULT_SOURCE_LANG = "default_source_lang"
        private const val KEY_TARGET_LANG = "target_lang"
        private const val KEY_FORBIDDEN_KEYWORDS = "forbidden_keywords"
        private const val KEY_SIMILARITY_TOLERANCE = "similarity_tolerance"
        private const val KEY_MAX_CACHE_SIZE = "max_cache_size"

        private val DEFAULT_TRANSLATION_TEXT_STYLE = TranslationTextStyle()
        private val DEFAULT_OVERLAY_BUTTON_SETTINGS = OverlayButtonSettings()
        private val DEFAULT_GENERAL_SETTINGS = GeneralSettings()
    }

    private val _translationTextStyleFlow by lazy { MutableStateFlow(getTranslationTextStyle()) }
    override val translationTextStyleFlow: StateFlow<TranslationTextStyle> = _translationTextStyleFlow.asStateFlow()

    private val _overlayButtonSettingsFlow by lazy { MutableStateFlow(getOverlayButtonSettings()) }
    override val overlayButtonSettingsFlow: StateFlow<OverlayButtonSettings> = _overlayButtonSettingsFlow.asStateFlow()

    private val _generalSettingsFlow by lazy { MutableStateFlow(getGeneralSettings()) }
    override val generalSettingsFlow: StateFlow<GeneralSettings> = _generalSettingsFlow.asStateFlow()

    init {
        Log.d(TAG, "SettingsRepositoryImpl initialized.")
    }

    override fun getTranslationTextStyle(): TranslationTextStyle {
        // Float? 타입은 String으로 읽어서 Float으로 변환
        val fontSizeString = prefs.getString(KEY_FONT_SIZE, null)
        val lineSpacingString = prefs.getString(KEY_LINE_SPACING, null)

        return TranslationTextStyle(
            fontSize = fontSizeString?.toFloatOrNull() ?: DEFAULT_TRANSLATION_TEXT_STYLE.fontSize,
            textColor = prefs.getString(KEY_TEXT_COLOR, DEFAULT_TRANSLATION_TEXT_STYLE.textColor) ?: DEFAULT_TRANSLATION_TEXT_STYLE.textColor,
            backgroundColor = prefs.getString(KEY_BACKGROUND_COLOR, DEFAULT_TRANSLATION_TEXT_STYLE.backgroundColor) ?: DEFAULT_TRANSLATION_TEXT_STYLE.backgroundColor,
            backgroundAlpha = prefs.getInt(KEY_BACKGROUND_ALPHA, DEFAULT_TRANSLATION_TEXT_STYLE.backgroundAlpha),
            lineSpacingExtra = lineSpacingString?.toFloatOrNull() ?: DEFAULT_TRANSLATION_TEXT_STYLE.lineSpacingExtra,
            textAlignment = prefs.getInt(KEY_TEXT_ALIGNMENT, DEFAULT_TRANSLATION_TEXT_STYLE.textAlignment)
        ).also {
            // Log.d(TAG, "Loaded TranslationTextStyle: $it")
        }
    }

    override fun saveTranslationTextStyle(style: TranslationTextStyle) {
        prefs.edit {
            // Float? 타입은 String으로 저장, null이면 해당 키 제거
            style.fontSize?.let { putString(KEY_FONT_SIZE, it.toString()) } ?: remove(KEY_FONT_SIZE)
            putString(KEY_TEXT_COLOR, style.textColor)
            putString(KEY_BACKGROUND_COLOR, style.backgroundColor)
            putInt(KEY_BACKGROUND_ALPHA, style.backgroundAlpha)
            style.lineSpacingExtra?.let { putString(KEY_LINE_SPACING, it.toString()) } ?: remove(KEY_LINE_SPACING)
            putInt(KEY_TEXT_ALIGNMENT, style.textAlignment)
        }
        _translationTextStyleFlow.value = style
        Log.d(TAG, "Saved TranslationTextStyle: $style")
    }

    override fun getOverlayButtonSettings(): OverlayButtonSettings {
        return OverlayButtonSettings(
            size = prefs.getInt(KEY_BUTTON_SIZE, DEFAULT_OVERLAY_BUTTON_SETTINGS.size),
            lastX = prefs.getInt(KEY_BUTTON_LAST_X, DEFAULT_OVERLAY_BUTTON_SETTINGS.lastX),
            lastY = prefs.getInt(KEY_BUTTON_LAST_Y, DEFAULT_OVERLAY_BUTTON_SETTINGS.lastY),
            referenceScreenWidth = prefs.getInt(KEY_BUTTON_REF_SCREEN_WIDTH, DEFAULT_OVERLAY_BUTTON_SETTINGS.referenceScreenWidth),
            referenceScreenHeight = prefs.getInt(KEY_BUTTON_REF_SCREEN_HEIGHT, DEFAULT_OVERLAY_BUTTON_SETTINGS.referenceScreenHeight)
        ).also {
            // Log.d(TAG, "Loaded OverlayButtonSettings: $it")
        }
    }

    override fun saveOverlayButtonSettings(settings: OverlayButtonSettings) {
        prefs.edit {
            putInt(KEY_BUTTON_SIZE, settings.size)
            putInt(KEY_BUTTON_LAST_X, settings.lastX)
            putInt(KEY_BUTTON_LAST_Y, settings.lastY)
            putInt(KEY_BUTTON_REF_SCREEN_WIDTH, settings.referenceScreenWidth)
            putInt(KEY_BUTTON_REF_SCREEN_HEIGHT, settings.referenceScreenHeight)
        }
        _overlayButtonSettingsFlow.value = settings
        Log.d(TAG, "Saved OverlayButtonSettings: $settings")
    }

    override fun getGeneralSettings(): GeneralSettings {
        // Int? 타입 처리: 키가 존재하면 Int로 읽고, 없으면 null
        val thinkingBudget = if (prefs.contains(KEY_THINKING_BUDGET)) prefs.getInt(KEY_THINKING_BUDGET, 0) else null // 0은 기본값일 뿐, 실제로는 null이 될 수 있음
        // Float? 타입 처리: String으로 읽어서 Float으로 변환
        val temperatureString = prefs.getString(KEY_TEMPERATURE, null)

        return GeneralSettings(
            geminiApiKey = prefs.getString(KEY_GEMINI_API_KEY, DEFAULT_GENERAL_SETTINGS.geminiApiKey) ?: DEFAULT_GENERAL_SETTINGS.geminiApiKey,
            geminiPrompt = prefs.getString(KEY_GEMINI_PROMPT, DEFAULT_GENERAL_SETTINGS.geminiPrompt) ?: DEFAULT_GENERAL_SETTINGS.geminiPrompt,
            geminiModelName = prefs.getString(KEY_GEMINI_MODEL_NAME, DEFAULT_GENERAL_SETTINGS.geminiModelName) ?: DEFAULT_GENERAL_SETTINGS.geminiModelName,
            thinkingBudget = thinkingBudget,
            temperature = temperatureString?.toFloatOrNull() ?: DEFAULT_GENERAL_SETTINGS.temperature,
            captureDelayMs = prefs.getInt(KEY_CAPTURE_DELAY_MS, DEFAULT_GENERAL_SETTINGS.captureDelayMs),
            autoDetectSourceLanguage = prefs.getBoolean(KEY_AUTO_DETECT_SOURCE_LANG, DEFAULT_GENERAL_SETTINGS.autoDetectSourceLanguage),
            defaultSourceLanguage = prefs.getString(KEY_DEFAULT_SOURCE_LANG, DEFAULT_GENERAL_SETTINGS.defaultSourceLanguage) ?: DEFAULT_GENERAL_SETTINGS.defaultSourceLanguage,
            targetLanguage = prefs.getString(KEY_TARGET_LANG, DEFAULT_GENERAL_SETTINGS.targetLanguage) ?: DEFAULT_GENERAL_SETTINGS.targetLanguage,
            forbiddenKeywords = prefs.getString(KEY_FORBIDDEN_KEYWORDS, DEFAULT_GENERAL_SETTINGS.forbiddenKeywords) ?: DEFAULT_GENERAL_SETTINGS.forbiddenKeywords,
            similarityTolerance = prefs.getInt(KEY_SIMILARITY_TOLERANCE, DEFAULT_GENERAL_SETTINGS.similarityTolerance),
            maxCacheSize = prefs.getInt(KEY_MAX_CACHE_SIZE, DEFAULT_GENERAL_SETTINGS.maxCacheSize)
        ).also {
            // Log.d(TAG, "Loaded GeneralSettings: $it")
        }
    }

    override fun saveGeneralSettings(settings: GeneralSettings) {
        prefs.edit {
            putString(KEY_GEMINI_API_KEY, settings.geminiApiKey)
            putString(KEY_GEMINI_PROMPT, settings.geminiPrompt)
            putString(KEY_GEMINI_MODEL_NAME, settings.geminiModelName)
            // Int? 타입 저장: null이 아니면 Int로 저장, null이면 해당 키 제거
            settings.thinkingBudget?.let { putInt(KEY_THINKING_BUDGET, it) } ?: remove(KEY_THINKING_BUDGET)
            // Float? 타입 저장: null이 아니면 String으로 저장, null이면 해당 키 제거
            settings.temperature?.let { putString(KEY_TEMPERATURE, it.toString()) } ?: remove(KEY_TEMPERATURE)
            putInt(KEY_CAPTURE_DELAY_MS, settings.captureDelayMs)
            putBoolean(KEY_AUTO_DETECT_SOURCE_LANG, settings.autoDetectSourceLanguage)
            putString(KEY_DEFAULT_SOURCE_LANG, settings.defaultSourceLanguage)
            putString(KEY_TARGET_LANG, settings.targetLanguage)
            putString(KEY_FORBIDDEN_KEYWORDS, settings.forbiddenKeywords)
            putInt(KEY_SIMILARITY_TOLERANCE, settings.similarityTolerance)
            putInt(KEY_MAX_CACHE_SIZE, settings.maxCacheSize)
        }
        _generalSettingsFlow.value = settings
        Log.d(TAG, "Saved GeneralSettings: $settings")
    }

    override fun getGeminiApiKey(): String {
        return prefs.getString(KEY_GEMINI_API_KEY, DEFAULT_GENERAL_SETTINGS.geminiApiKey) ?: DEFAULT_GENERAL_SETTINGS.geminiApiKey
    }

    override fun saveGeminiApiKey(apiKey: String) {
        val currentSettings = getGeneralSettings()
        saveGeneralSettings(currentSettings.copy(geminiApiKey = apiKey))
        Log.d(TAG, "Saved Gemini API Key (via GeneralSettings)")
    }

    override fun saveOverlayButtonPosition(x: Int, y: Int, screenWidth: Int, screenHeight: Int) {
        val currentSettings = getOverlayButtonSettings()
        saveOverlayButtonSettings(currentSettings.copy(
            lastX = x,
            lastY = y,
            referenceScreenWidth = screenWidth,
            referenceScreenHeight = screenHeight
        ))
        // Log.d(TAG, "Saved OverlayButtonPosition: x=$x, y=$y with refScreen WxH: ${screenWidth}x$screenHeight")
    }
}
