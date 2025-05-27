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
     * @param isBatchedRequest 이것이 여러 세그먼트를 한 번에 번역하는 일괄 요청인지 여부 (현재 구현에서는 시스템 프롬프트 변경에 사용되지 않음).
     * @return 번역된 텍스트를 포함하는 Result 객체. 성공 시 String, 실패 시 Exception.
     */
    suspend fun translateText(
        textToTranslate: String,
        sourceLanguage: String?,
        targetLanguage: String,
        isBatchedRequest: Boolean = false // 이 플래그는 여전히 전달되지만, 시스템 프롬프트 분기에는 사용되지 않음
    ): Result<String>
}

@Singleton
class TranslationManagerImpl @Inject constructor(
    private val settingsRepository: SettingsRepository
) : TranslationManager {

    companion object {
        private const val TAG = "TranslationManager"
        // 통합된 번역 지시사항
        private const val UNIVERSAL_TRANSLATION_INSTRUCTION = "Translate the provided text while strictly maintaining the original number of lines/segments. Respond with only the translated text."
    }

    private var generativeModel: GenerativeModel? = null
    private var currentApiKey: String? = null
    private var currentSystemInstructionText: String? = null
    private var currentModelName: String? = null
    private var currentTemperature: Float? = null

    private fun initializeModelIfNeeded(
        apiKey: String,
        userDefinedBasePrompt: String, // GeneralSettings.geminiPrompt 값
        sourceClauseForSystem: String,
        targetLangDescriptionForSystem: String,
        modelName: String,
        temperature: Float
        // isBatchedRequest 파라미터는 시스템 프롬프트 구성에 더 이상 직접적인 영향을 주지 않음
    ) {
        val systemPromptParts = mutableListOf<String>()

        // 1. 사용자가 정의한 기본 프롬프트 추가
        if (userDefinedBasePrompt.isNotBlank()) {
            systemPromptParts.add(userDefinedBasePrompt.trim())
        }

        // 2. 번역 방향 지시 추가
        val translateDirectionInstruction = "Translate from ${sourceClauseForSystem.trim()} to $targetLangDescriptionForSystem."
        systemPromptParts.add(translateDirectionInstruction)

        // 3. 통합된 출력 형식 지시 추가
        systemPromptParts.add(UNIVERSAL_TRANSLATION_INSTRUCTION.trim())

        val newSystemInstructionText = systemPromptParts.joinToString(separator = " ")

        if (generativeModel != null &&
            currentApiKey == apiKey &&
            currentSystemInstructionText == newSystemInstructionText && // 이 비교는 여전히 유효 (사용자 정의 프롬프트 등 변경 시)
            currentModelName == modelName &&
            currentTemperature == temperature
        ) {
            Log.v(TAG, "GenerativeModel is already initialized with the same settings.")
            return
        }

        synchronized(this) {
            // 여기서 다시 한번 조건을 체크하여, 다른 스레드에 의해 이미 초기화되었는지 확인
            if (generativeModel == null ||
                currentApiKey != apiKey ||
                currentSystemInstructionText != newSystemInstructionText ||
                currentModelName != modelName ||
                currentTemperature != temperature
            ) {
                if (apiKey.isBlank()) {
                    Log.e(TAG, "API Key is blank. Cannot initialize GenerativeModel.")
                    generativeModel = null
                    currentApiKey = null
                    currentSystemInstructionText = null
                    currentModelName = null
                    currentTemperature = null
                    return
                }
                try {
                    val config = generationConfig {
                        this.temperature = temperature
                        this.topK = 1
                        this.topP = 1f
                        this.maxOutputTokens = 2048
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
                    generativeModel = null
                    currentApiKey = null
                    currentSystemInstructionText = null
                    currentModelName = null
                    currentTemperature = null
                }
            } else {
                Log.d(TAG, "GenerativeModel already initialized by another thread with the same new settings.")
            }
        }
    }

    override suspend fun translateText(
        textToTranslate: String,
        sourceLanguage: String?,
        targetLanguage: String,
        isBatchedRequest: Boolean // 이 플래그는 여전히 API 호출 시점에는 존재하나, 모델 재초기화 조건에는 직접 관여하지 않음
    ): Result<String> {
        Log.d(TAG, "translateText called. Batched: $isBatchedRequest, Text length: ${textToTranslate.length}, Source: $sourceLanguage, Target: $targetLanguage")
        val generalSettings = settingsRepository.getGeneralSettings()
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
            else -> if (generalSettings.autoDetectSourceLanguage && sourceLanguage.isNullOrEmpty()) {
                "the auto-detected language"
            } else {
                sourceLanguage ?: "the specified language"
            }
        }
        val targetLangDescriptionForSystem = when (targetLanguage.lowercase()) {
            "ko" -> "Korean"
            "en" -> "English"
            "ja" -> "Japanese"
            else -> targetLanguage
        }

        val userDefinedBasePromptFromSettings = generalSettings.geminiPrompt

        // initializeModelIfNeeded 호출 시 isBatchedRequest는 더 이상 시스템 프롬프트 구성에 영향을 주지 않음
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
        Log.v(TAG, "Prompt for generateContent: \"$promptForThisCall\" (System Instruction is set separately and is now universal)")

        return try {
            val response = withContext(Dispatchers.IO) {
                generativeModel!!.generateContent(promptForThisCall)
            }
            val translatedText = response.text
            if (translatedText != null) {
                Log.d(TAG, "Translation successful. Output length: ${translatedText.length}")
                Result.success(translatedText)
            } else {
                Log.e(TAG, "Translation failed: Response text is null. Candidates: ${response.candidates}")
                Result.failure(Exception("Translation failed: No text in response."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation API call failed for text: \"$textToTranslate\"", e)
            Result.failure(e)
        }
    }
}

class ApiKeyNotSetException(message: String) : Exception(message)
