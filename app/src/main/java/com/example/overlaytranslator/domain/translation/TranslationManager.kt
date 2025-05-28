package com.example.overlaytranslator.domain.translation

import android.util.Log
import com.example.overlaytranslator.data.GeneralSettings
import com.example.overlaytranslator.data.SettingsRepository
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface TranslationManager {
    /**
     * 주어진 텍스트를 지정된 목표 언어로 번역합니다.
     * @param textToTranslate 번역할 텍스트. 각 세그먼트는 \n\n으로 구분될 수 있습니다.
     * @param sourceLanguage 원본 언어 코드 (예: "ja", "en"). 자동 감지 시 null 또는 빈 문자열 가능.
     * @param targetLanguage 목표 언어 코드 (예: "ko").
     * @param isBatchedRequest 이것이 여러 세그먼트를 한 번에 번역하는 일괄 요청인지 여부.
     * @return 번역된 텍스트를 포함하는 Result 객체. 성공 시 String, 실패 시 Exception.
     */
    suspend fun translateText(
        textToTranslate: String,
        sourceLanguage: String?,
        targetLanguage: String,
        isBatchedRequest: Boolean = false
    ): Result<String>
}

@Singleton
class TranslationManagerImpl @Inject constructor(
    private val settingsRepository: SettingsRepository
) : TranslationManager {

    companion object {
        private const val TAG = "TranslationManager"
        private const val UNIVERSAL_TRANSLATION_INSTRUCTION = "Translate the provided text while strictly maintaining the original number of lines/segments. Respond with only the translated text."
    }

    private var generativeModel: GenerativeModel? = null
    private var currentApiKey: String? = null
    private var currentSystemInstructionText: String? = null
    private var currentModelName: String? = null
    private var currentTemperature: Float? = null

    private fun initializeModelIfNeeded(
        apiKey: String,
        userDefinedBasePrompt: String,
        sourceClauseForSystem: String,
        targetLangDescriptionForSystem: String,
        modelName: String,
        temperature: Float
    ) {
        val systemPromptParts = mutableListOf<String>()

        if (userDefinedBasePrompt.isNotBlank()) {
            systemPromptParts.add(userDefinedBasePrompt.trim())
        }

        val translateDirectionInstruction = "Translate from ${sourceClauseForSystem.trim()} to $targetLangDescriptionForSystem."
        systemPromptParts.add(translateDirectionInstruction)
        systemPromptParts.add(UNIVERSAL_TRANSLATION_INSTRUCTION.trim())

        val newSystemInstructionText = systemPromptParts.joinToString(separator = " ")

        if (generativeModel != null &&
            currentApiKey == apiKey &&
            currentSystemInstructionText == newSystemInstructionText &&
            currentModelName == modelName &&
            currentTemperature == temperature
        ) {
            // Log.v(TAG, "GenerativeModel is already initialized with the same settings.") // 너무 빈번한 로그일 수 있어 주석 처리
            return
        }

        synchronized(this) {
            if (generativeModel == null ||
                currentApiKey != apiKey ||
                currentSystemInstructionText != newSystemInstructionText ||
                currentModelName != modelName ||
                currentTemperature != temperature
            ) {
                if (apiKey.isBlank()) {
                    Log.e(TAG, "API Key is blank. Cannot initialize GenerativeModel.")
                    generativeModel = null; currentApiKey = null; currentSystemInstructionText = null; currentModelName = null; currentTemperature = null
                    return
                }
                try {
                    val config = generationConfig {
                        this.temperature = temperature
                        // this.topK = 1 // 필요시 설정
                        // this.topP = 1f // 필요시 설정
                        // this.maxOutputTokens = 2048 // 필요시 설정
                    }

                    Log.d(TAG, "Initializing/Re-initializing GenerativeModel. Model: $modelName, Temp: $temperature")
                    Log.d(TAG, "System Instruction for model: \"$newSystemInstructionText\"")

                    generativeModel = GenerativeModel(
                        modelName = modelName,
                        apiKey = apiKey,
                        generationConfig = config,
                        systemInstruction = Content(role = "system", parts = listOf(TextPart(newSystemInstructionText)))
                    )

                    currentApiKey = apiKey
                    currentSystemInstructionText = newSystemInstructionText
                    currentModelName = modelName
                    currentTemperature = temperature
                    Log.d(TAG, "GenerativeModel initialized/updated. Model: ${generativeModel?.modelName}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize GenerativeModel", e)
                    generativeModel = null; currentApiKey = null; currentSystemInstructionText = null; currentModelName = null; currentTemperature = null
                }
            }
        }
    }

    override suspend fun translateText(
        textToTranslate: String,
        sourceLanguage: String?,
        targetLanguage: String,
        isBatchedRequest: Boolean
    ): Result<String> {
        Log.d(TAG, "translateText called. Batched: $isBatchedRequest, Text length: ${textToTranslate.length}, Source: $sourceLanguage, Target: $targetLanguage")
        val generalSettings = settingsRepository.getGeneralSettings() // 매번 호출하는 대신, 서비스 시작 시 로드하거나 flow로 관찰하는 것 고려
        val apiKey = generalSettings.geminiApiKey
        val modelName = generalSettings.geminiModelName
        val temperatureToUse = generalSettings.temperature ?: GeneralSettings().temperature ?: 0.3f

        if (apiKey.isBlank()) {
            Log.e(TAG, "Gemini API key is not set in GeneralSettings.")
            return Result.failure(ApiKeyNotSetException("Gemini API key is not set."))
        }
        if (modelName.isBlank()) {
            Log.e(TAG, "Gemini Model Name is blank in GeneralSettings.")
            return Result.failure(IllegalStateException("Gemini Model Name is not set."))
        }

        val sourceLangDescriptionForSystem = when (sourceLanguage?.lowercase()) {
            "ja" -> "Japanese"
            "en" -> "English"
            "ko" -> "Korean"
            "zh" -> "Chinese"
            else -> if (generalSettings.autoDetectSourceLanguage && sourceLanguage.isNullOrEmpty()) {
                "the auto-detected language"
            } else {
                sourceLanguage ?: "the specified language" // 좀 더 명확한 기본값
            }
        }
        val targetLangDescriptionForSystem = when (targetLanguage.lowercase()) {
            "ko" -> "Korean"
            "en" -> "English"
            "ja" -> "Japanese"
            "zh" -> "Chinese"
            else -> targetLanguage
        }

        val userDefinedBasePromptFromSettings = generalSettings.geminiPrompt

        initializeModelIfNeeded(
            apiKey,
            userDefinedBasePromptFromSettings,
            sourceLangDescriptionForSystem,
            targetLangDescriptionForSystem,
            modelName,
            temperatureToUse
        )

        if (generativeModel == null) {
            val errorMsg = "GenerativeModel is not initialized. API key or model name might be invalid, or model init failed."
            Log.e(TAG, errorMsg)
            return Result.failure(IllegalStateException("Translation model not initialized. Check API key, model name, or other initialization parameters."))
        }

        val promptForThisCall = textToTranslate
        // Log.v(TAG, "Prompt for generateContent: \"$promptForThisCall\"") // 너무 길 수 있어 상세 로그는 필요시 활성화

        return try {
            val response = withContext(Dispatchers.IO) { // API 호출은 IO 스레드에서
                generativeModel!!.generateContent(promptForThisCall)
            }
            val translatedText = response.text
            if (translatedText != null) {
                // Log.d(TAG, "Translation successful. Output length: ${translatedText.length}")
                Result.success(translatedText)
            } else {
                Log.e(TAG, "Translation failed: Response text is null. Candidates: ${response.candidates}")
                Result.failure(Exception("Translation failed: No text in response."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation API call failed for text (first 50 chars): \"${textToTranslate.take(50)}...\"", e)
            Result.failure(e)
        }
    }
}

class ApiKeyNotSetException(message: String) : Exception(message)
