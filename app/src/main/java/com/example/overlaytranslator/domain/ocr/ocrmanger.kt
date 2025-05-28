package com.example.overlaytranslator.domain.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.overlaytranslator.data.OcrTextBlock
// import com.example.overlaytranslator.data.SettingsRepository // OcrManagerImpl에서 직접 사용하지 않으므로 주석 처리 또는 제거 가능
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.tasks.await
import java.lang.Character.UnicodeBlock
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

// 기존 AppLogger 및 AndroidLogger 인터페이스/클래스는 변경 없이 그대로 사용합니다.
interface AppLogger {
    fun d(tag: String, msg: String)
    fun e(tag: String, msg: String, tr: Throwable? = null)
    fun w(tag: String, msg: String, tr: Throwable? = null)
}

class AndroidLogger @Inject constructor() : AppLogger {
    override fun d(tag: String, msg: String) { android.util.Log.d(tag, msg) }
    override fun e(tag: String, msg: String, tr: Throwable?) {
        if (tr != null) android.util.Log.e(tag, msg, tr) else android.util.Log.e(tag, msg)
    }
    override fun w(tag: String, msg: String, tr: Throwable?) {
        if (tr != null) android.util.Log.w(tag, msg, tr) else android.util.Log.w(tag, msg)
    }
}

interface OcrManager {
    suspend fun recognizeText(bitmap: Bitmap, targetLanguageHint: String? = null): Result<List<OcrTextBlock>>
    fun preprocessForSimilarityCheck(text: String): String
    fun areTextsSimilar(
        text1: String,
        text2: String,
        languageHint: String?, // 예: "ja", "zh", "en" 등
        tolerance: Int // 언어별로 다른 의미를 가질 수 있음 (예: 일본어의 경우 히라가나/가타카나 허용 오차)
    ): Boolean
}

@Singleton
class OcrManagerImpl @Inject constructor(
    private val recognizers: Map<String, TextRecognizer>,
    // private val settingsRepository: SettingsRepository, // 현재 OcrManagerImpl에서 직접 사용하지 않음
    private val logger: AppLogger
) : OcrManager {

    companion object {
        private const val TAG = "OcrManagerImpl"
        // 일본어 유사도 검사를 위한 문자 패턴 (한자, 히라가나, 가타카나, 숫자, 영문 알파벳 유지)
        private val JAPANESE_SIMILARITY_PATTERN = Regex("[^\\p{InHiragana}\\p{InKatakana}\\p{InCJKUnifiedIdeographs}0-9a-zA-Z]+")
        // 일반적인 유사도 검사를 위한 문자 패턴 (숫자, 영문 알파벳, 일반적인 스크립트 문자 유지, 대부분의 특수문자/공백 제거)
        // 좀 더 정교한 패턴이 필요할 수 있음
        private val GENERAL_SIMILARITY_PATTERN = Regex("[^\\p{L}\\p{N}]+")
    }

    // 정적 필터링 로직 (기존 함수들)
    private fun keepOnlyJapaneseRelatedChars(text: String): String {
        val stringBuilder = StringBuilder()
        for (char in text) {
            val block = UnicodeBlock.of(char)
            if (char.isDigit() ||
                block == UnicodeBlock.HIRAGANA ||
                block == UnicodeBlock.KATAKANA ||
                block == UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
                block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                char.isWhitespace() ||
                char == '。' || char == '、' || char == '「' || char == '」' ||
                char == '『' || char == '』' || char == '・' || char == 'ー' ||
                char == '？' || char == '！' ||
                (char.code in 0x3000..0x303F)
            ) {
                stringBuilder.append(char)
            }
        }
        return stringBuilder.toString()
    }

    private fun condenseRepeatingJapanesePunctuation(text: String): String {
        var result = text
        val condensablePunctuation = listOf('。', '、', '？', '！', '・', 'ー', '〜')
        val repetitionThreshold = 1
        for (puncChar in condensablePunctuation) {
            val puncString = puncChar.toString()
            val escapedPunc = Pattern.quote(puncString)
            val regex = Regex("$escapedPunc{${repetitionThreshold + 1},}")
            result = regex.replace(result, puncString)
        }
        return result
    }

    private fun isHiragana(char: Char): Boolean = UnicodeBlock.of(char) == UnicodeBlock.HIRAGANA
    private fun isKatakana(char: Char): Boolean {
        val block = UnicodeBlock.of(char)
        return block == UnicodeBlock.KATAKANA || block == UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
    }
    private fun isHanChar(char: Char): Boolean {
        val block = UnicodeBlock.of(char)
        return block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
    }

    private fun meetsJapaneseCharacterRequirement(text: String): Boolean {
        if (text.isEmpty()) return false
        var hiraganaCount = 0
        var katakanaCount = 0
        var hanCount = 0
        for (char in text) {
            if (isHiragana(char)) hiraganaCount++
            else if (isKatakana(char)) katakanaCount++
            else if (isHanChar(char)) hanCount++
        }
        return hiraganaCount >= 2 || katakanaCount >= 2 || hanCount >= 1
    }

    // 동적 필터링(캐싱)을 위한 유사도 비교 관련 함수들

    /**
     * 유사도 비교를 위해 텍스트에서 특수문자, 공백 등을 제거하고 주요 문자만 남깁니다.
     * 언어에 따라 다른 정규식을 사용할 수 있습니다.
     */
    override fun preprocessForSimilarityCheck(text: String): String {
        // 현재는 일본어 중심의 패턴을 사용하거나, 좀 더 일반적인 패턴을 선택할 수 있습니다.
        // 여기서는 일단 일본어에 사용될 법한 패턴을 적용합니다.
        // 실제 사용 시 languageHint에 따라 다른 패턴을 적용하는 로직 추가 가능.
        return JAPANESE_SIMILARITY_PATTERN.replace(text, "").lowercase()
    }

    /**
     * Levenshtein 편집 거리를 계산합니다.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[len1][len2]
    }

    /**
     * 두 텍스트가 유사한지 비교합니다. 일본어의 경우 한자 일치 후 히라가나/가타카나 편집 거리를 확인합니다.
     * @param text1 첫 번째 텍스트 (정적 필터링 거친 후)
     * @param text2 두 번째 텍스트 (정적 필터링 거친 후, 캐시된 항목의 원본 텍스트일 수 있음)
     * @param languageHint 언어 힌트 ("ja", "en" 등)
     * @param tolerance 일본어의 경우 히라가나/가타카나 허용 편집 거리, 다른 언어는 전체 문자열 편집 거리 등
     */
    override fun areTextsSimilar(
        text1: String,
        text2: String,
        languageHint: String?,
        tolerance: Int
    ): Boolean {
        val processedText1 = preprocessForSimilarityCheck(text1)
        val processedText2 = preprocessForSimilarityCheck(text2)

        if (processedText1 == processedText2) return true // 전처리 후 완전히 같으면 유사함

        // 일본어("ja") 특화 로직
        if (languageHint?.equals("ja", ignoreCase = true) == true) {
            val han1 = processedText1.filter { isHanChar(it) }
            val han2 = processedText2.filter { isHanChar(it) }

            if (han1 != han2) return false // 한자 구성 또는 순서가 다르면 다른 텍스트로 간주

            // 한자가 같다면, 나머지 부분 (주로 히라가나/가타카나)에 대해 편집 거리 비교
            val nonHan1 = processedText1.filter { !isHanChar(it) }
            val nonHan2 = processedText2.filter { !isHanChar(it) }

            return levenshteinDistance(nonHan1, nonHan2) <= tolerance
        } else {
            // 다른 언어의 경우, 전체 전처리된 텍스트에 대해 편집 거리 비교 (일반적인 경우)
            // 이 부분은 각 언어 특성에 맞게 더 정교화될 수 있습니다.
            return levenshteinDistance(processedText1, processedText2) <= tolerance
        }
    }

    override suspend fun recognizeText(bitmap: Bitmap, targetLanguageHint: String?): Result<List<OcrTextBlock>> {
        logger.d(TAG, "recognizeText called. Language hint: $targetLanguageHint")
        val image = InputImage.fromBitmap(bitmap, 0)

        // 언어 힌트에 따라 적절한 TextRecognizer 선택
        // 일본어 모델은 "ja", 라틴 문자 기반 모델은 "latin" 등으로 recognizers 맵에 주입되어야 함
        val recognizerKey = when (targetLanguageHint?.lowercase()) {
            "ja" -> "japanese" // ML Kit Japanese TextRecognizer
            "ko" -> "korean"   // ML Kit Korean TextRecognizer
            "zh" -> "chinese"  // ML Kit Chinese TextRecognizer
            // 기타 라틴 문자 기반 언어들은 "latin"으로 통합하거나 개별 키 사용
            "en", "es", "fr", "de" -> "latin" // ML Kit Latin TextRecognizer
            else -> "latin" // 기본값 또는 자동 감지 시 주로 라틴
        }

        val recognizer = recognizers[recognizerKey] ?: recognizers["latin"] ?: run {
            logger.e(TAG, "No suitable TextRecognizer found for hint: $targetLanguageHint (key: $recognizerKey). Defaulting failed or no latin recognizer.")
            return Result.failure(IllegalStateException("No suitable TextRecognizer available for $targetLanguageHint. Check recognizer map."))
        }
        logger.d(TAG, "Using recognizer for key: $recognizerKey")


        return try {
            val result: Text = recognizer.process(image).await()
            val filteredOcrTextBlocks = mutableListOf<OcrTextBlock>()

            logger.d(TAG, "Raw text blocks from ML Kit: ${result.textBlocks.size}")

            for (block in result.textBlocks) {
                val originalText = block.text
                var finalTextToUse = originalText.trim()

                if (finalTextToUse.isEmpty()) continue

                // --- 정적 필터링 단계 ---
                // 이 필터들은 주로 targetLanguageHint가 "ja"일 때 의미가 있습니다.
                // 다른 언어의 경우, 해당 언어에 맞는 정적 필터가 필요하거나, 이 단계를 건너뛸 수 있습니다.
                if (targetLanguageHint?.equals("ja", ignoreCase = true) == true) {
                    // 1. 핵심 일본어 문자 포함 여부 검사 (사용자 요청 규칙 1)
                    if (!meetsJapaneseCharacterRequirement(finalTextToUse)) {
                        // logger.d(TAG, "Original: \"$originalText\" -> Filtered out by Japanese Character Requirement. Current: \"$finalTextToUse\"")
                        finalTextToUse = ""
                    }

                    // 2. 반복되는 일본어 구두점 축약 (사용자 요청 규칙 2)
                    if (finalTextToUse.isNotEmpty()) {
                        finalTextToUse = condenseRepeatingJapanesePunctuation(finalTextToUse)
                    }
                }
                // (필요 시 여기에 다른 언어에 대한 정적 필터 추가)

                // 최종 정리 (앞뒤 공백 한 번 더 제거)
                finalTextToUse = finalTextToUse.trim()
                // --- 정적 필터링 종료 ---


                if (finalTextToUse.isNotEmpty()) {
                    // logger.d(TAG, "Original: \"$originalText\" -> Statically Processed: \"$finalTextToUse\" for language: ${block.recognizedLanguage}")
                    filteredOcrTextBlocks.add(
                        OcrTextBlock(
                            text = finalTextToUse, // 정적 필터링이 완료된 텍스트
                            boundingBox = block.boundingBox ?: Rect(0, 0, 0, 0),
                            languageCode = block.recognizedLanguage // ML Kit이 감지한 언어
                        )
                    )
                }
            }
            logger.d(TAG, "Static filtering complete. Kept ${filteredOcrTextBlocks.size} blocks.")
            Result.success(filteredOcrTextBlocks)

        } catch (e: Exception) {
            logger.e(TAG, "Text recognition or static filtering failed", e)
            Result.failure(e)
        }
    }
}
