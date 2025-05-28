package com.example.overlaytranslator.data

import android.graphics.Rect
import android.view.Gravity

/**
 * 번역 결과 텍스트 스타일 설정을 담는 데이터 클래스
 * (기존과 동일)
 */
data class TranslationTextStyle(
    val fontSize: Float? = 13f,
    val textColor: String = "#FFFFFFFF",
    val backgroundColor: String = "#80000000",
    val backgroundAlpha: Int = 200, // 0-255
    val lineSpacingExtra: Float? = 4f,
    val textAlignment: Int = Gravity.START
)

/**
 * 오버레이 버튼 설정을 담는 데이터 클래스
 * (기존과 동일)
 */
data class OverlayButtonSettings(
    val size: Int = 80, // dp
    val lastX: Int = 0,
    val lastY: Int = 200,
    val referenceScreenWidth: Int = 0,
    val referenceScreenHeight: Int = 0
)

/**
 * 일반 설정을 담는 데이터 클래스
 * @property geminiApiKey Gemini API 키
 * @property geminiPrompt 번역 지시 프롬프트 (사용자 정의 부분)
 * @property geminiModelName 사용할 Gemini 모델 이름
 * @property thinkingBudget Gemini 모델에 사용될 thinkingBudget 값 (선택 사항)
 * @property temperature 모델의 온도 (생성 다양성 제어) - null 가능
 * @property captureDelayMs 캡처 지연 시간 (밀리초)
 * @property autoDetectSourceLanguage 원본 언어 자동 감지 사용 여부
 * @property defaultSourceLanguage 기본 원본 언어 코드
 * @property targetLanguage 번역 목표 언어 코드
 * @property forbiddenKeywords 필터링할 금지 단어 목록
 * @property similarityTolerance 유사도 검사 시 허용 오차 (예: 편집 거리)
 * @property maxCacheSize OCR 번역 결과 캐시 최대 저장 개수
 */
data class GeneralSettings(
    val geminiApiKey: String = "",
    val geminiPrompt: String = "This is OCR-extracted text.",
    val geminiModelName: String = "gemini-2.0-flash",
    val thinkingBudget: Int? = null,
    val temperature: Float? = 0.7f,
    val captureDelayMs: Int = 10,
    val autoDetectSourceLanguage: Boolean = false,
    val defaultSourceLanguage: String = "ja",
    val targetLanguage: String = "ko", // 현재는 고정값이지만, 향후 설정 가능하게 할 수 있음
    val forbiddenKeywords: String = "",
    val similarityTolerance: Int = 2, // 기본 유사도 허용치 (0 이상)
    val maxCacheSize: Int = 200      // 기본 캐시 크기 (1 이상)
) {
    override fun toString(): String {
        return "GeneralSettings(geminiApiKey='***MASKED***', geminiPrompt='$geminiPrompt', " +
                "geminiModelName='$geminiModelName', thinkingBudget=$thinkingBudget, temperature=$temperature, " +
                "captureDelayMs=$captureDelayMs, autoDetectSourceLanguage=$autoDetectSourceLanguage, " +
                "defaultSourceLanguage='$defaultSourceLanguage', targetLanguage='$targetLanguage', " +
                "forbiddenKeywords='$forbiddenKeywords', similarityTolerance=$similarityTolerance, " +
                "maxCacheSize=$maxCacheSize)"
    }
}

/**
 * OCR 결과로 인식된 텍스트 블록 정보를 담는 데이터 클래스
 * (기존과 동일)
 */
data class OcrTextBlock(
    val text: String,
    val boundingBox: Rect,
    val languageCode: String? = null // ML Kit에서 감지한 언어 코드 (예: "ja", "en", "und")
)

/**
 * 번역된 텍스트와 원본 위치 정보를 담는 데이터 클래스
 * (기존과 동일)
 */
data class TranslatedTextElement(
    val originalText: String, // OcrTextBlock의 text (정적 필터링 후)
    val translatedText: String,
    val originalBoundingBox: Rect, // OcrTextBlock의 boundingBox
    val sourceLanguage: String, // 번역에 사용된 실제 원본 언어
    val targetLanguage: String // 번역된 목표 언어
)
