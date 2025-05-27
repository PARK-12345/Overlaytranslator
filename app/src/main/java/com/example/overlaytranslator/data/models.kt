package com.example.overlaytranslator.data

import android.graphics.Rect
import android.view.Gravity

/**
 * 번역 결과 텍스트 스타일 설정을 담는 데이터 클래스
 * @property fontSize 글꼴 크기 (sp 단위) - null 가능
 * @property textColor 텍스트 색상 (ARGB 형식, 예: "#FFFF0000")
 * @property backgroundColor 배경 색상 (ARGB 형식)
 * @property backgroundAlpha 배경 투명도 (0-255)
 * @property lineSpacingExtra 줄 간격 추가 값 (sp 단위) - null 가능
 * @property textAlignment 텍스트 정렬 (Gravity.START, Gravity.CENTER, Gravity.END)
 */
data class TranslationTextStyle(
    val fontSize: Float? = 13f,
    val textColor: String = "#FFFFFFFF",
    val backgroundColor: String = "#80000000",
    val backgroundAlpha: Int = 200,
    val lineSpacingExtra: Float? = 4f,
    val textAlignment: Int = Gravity.START
)

/**
 * 오버레이 버튼 설정을 담는 데이터 클래스
 * @property size 버튼 크기 (dp 단위)
 * @property lastX 마지막 X 좌표
 * @property lastY 마지막 Y 좌표
 * @property referenceScreenWidth 버튼 위치 저장 시점의 화면 너비 (px)
 * @property referenceScreenHeight 버튼 위치 저장 시점의 화면 높이 (px)
 */
data class OverlayButtonSettings(
    val size: Int = 80,
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
 */
data class GeneralSettings(
    val geminiApiKey: String = "",
    val geminiPrompt: String = "This is OCR-extracted text.", // 사용자가 정의하는 기본 프롬프트
    val geminiModelName: String = "gemini-2.0-flash",
    val thinkingBudget: Int? = null,
    val temperature: Float? = 0.7f,
    val captureDelayMs: Int = 10,
    val autoDetectSourceLanguage: Boolean = false,
    val defaultSourceLanguage: String = "ja",
    val targetLanguage: String = "ko",
    val forbiddenKeywords: String = ""
)

{
    override fun toString(): String {
        return "GeneralSettings(geminiApiKey='***MASKED***', geminiPrompt='$geminiPrompt', geminiModelName='$geminiModelName', thinkingBudget=$thinkingBudget, temperature=$temperature, captureDelayMs=$captureDelayMs, autoDetectSourceLanguage=$autoDetectSourceLanguage, defaultSourceLanguage='$defaultSourceLanguage', targetLanguage='$targetLanguage', forbiddenKeywords='$forbiddenKeywords')"
    }
}

/**
 * OCR 결과로 인식된 텍스트 블록 정보를 담는 데이터 클래스
 * @property text 인식된 텍스트 (OcrManager에서 필터링 및 처리된 결과)
 * @property boundingBox 텍스트 블록의 화면 내 위치 및 크기
 * @property languageCode 인식된 언어 코드 (ML Kit에서 제공, 자동 감지 시 활용)
 */
data class OcrTextBlock(
    val text: String,
    val boundingBox: Rect,
    val languageCode: String? = null
)

/**
 * 번역된 텍스트와 원본 위치 정보를 담는 데이터 클래스
 * @property originalText 원본 OcrTextBlock의 가공되지 않은 텍스트
 * @property translatedText 번역된 텍스트
 * @property originalBoundingBox 원본 텍스트의 화면 내 위치 및 크기
 * @property sourceLanguage 번역에 사용된 실제 원본 언어 코드
 * @property targetLanguage 번역된 목표 언어 코드
 */
data class TranslatedTextElement(
    val originalText: String,
    val translatedText: String,
    val originalBoundingBox: Rect,
    val sourceLanguage: String,
    val targetLanguage: String
)
