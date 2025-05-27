package com.example.overlaytranslator.domain.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.overlaytranslator.data.OcrTextBlock
import com.example.overlaytranslator.data.SettingsRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.tasks.await
import java.lang.Character.UnicodeBlock
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

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
}

@Singleton
class OcrManagerImpl @Inject constructor(
    private val recognizers: Map<String, TextRecognizer>,
    private val settingsRepository: SettingsRepository, // 현재 코드에서는 사용되지 않지만, 생성자에 남아있으므로 그대로 둡니다.
    private val logger: AppLogger
) : OcrManager {

    companion object {
        private const val TAG = "OcrManagerImpl"
    }

    /**
     * 텍스트에서 일본어 관련 문자, 지정된 구두점, 공백, 그리고 숫자를 제외한 모든 문자를 제거합니다.
     * (현재 recognizeText에서는 직접 호출되지 않지만, 유틸리티 함수로 남겨둡니다.)
     */
    private fun keepOnlyJapaneseRelatedChars(text: String): String {
        val stringBuilder = StringBuilder()
        for (char in text) {
            val block = UnicodeBlock.of(char)
            if (char.isDigit() || // 숫자를 허용하도록 조건 추가
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
                char == '？' || char == '！' || // 전각 물음표/느낌표로 가정
                (char.code in 0x3000..0x303F) // CJK Symbols and Punctuation (물결표시(〜) 등 포함)
            ) {
                stringBuilder.append(char)
            }
        }
        return stringBuilder.toString()
    }

    /**
     * 텍스트 내에서 반복되는 특정 일본어 구두점을 하나로 축약합니다.
     * 예: "。。。" -> "。"
     * 요청하신 2번 규칙에 해당합니다.
     */
    private fun condenseRepeatingJapanesePunctuation(text: String): String {
        var result = text
        val condensablePunctuation = listOf('。', '、', '？', '！', '・', 'ー', '〜')
        val repetitionThreshold = 1 // 1번 초과 시 축약 (즉, 2번 이상 반복 시 1개로)

        for (puncChar in condensablePunctuation) {
            val puncString = puncChar.toString()
            val escapedPunc = Pattern.quote(puncString)
            val regex = Regex("$escapedPunc{${repetitionThreshold + 1},}")
            result = regex.replace(result, puncString)
        }
        return result
    }

    /**
     * 문자가 히라가나인지 확인합니다.
     */
    private fun isHiragana(char: Char): Boolean {
        return UnicodeBlock.of(char) == UnicodeBlock.HIRAGANA
    }

    /**
     * 문자가 가타카나인지 확인합니다. (확장 가타카나 포함)
     */
    private fun isKatakana(char: Char): Boolean {
        val block = UnicodeBlock.of(char)
        return block == UnicodeBlock.KATAKANA || block == UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
    }

    /**
     * 문자가 CJK 한자인지 확인합니다.
     */
    private fun isHanChar(char: Char): Boolean {
        val block = UnicodeBlock.of(char)
        return block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
    }

    /**
     * 문자가 허용된 일본어 구두점 또는 기호인지 확인합니다.
     * 이 함수는 순수하게 구두점/기호로만 이루어진 문자열을 필터링하기 위해 사용됩니다.
     * (현재 recognizeText에서는 직접 호출되지 않지만, 유틸리티 함수로 남겨둡니다.)
     */
    private fun isAllowedJapanesePunctuationOrSymbol(char: Char): Boolean {
        if (isHiragana(char) || isKatakana(char) || isHanChar(char) || char.isWhitespace() || char.isDigit()) {
            return false
        }
        return char == '。' || char == '、' || char == '「' || char == '」' ||
                char == '『' || char == '』' || char == '・' || char == 'ー' ||
                char == '？' || char == '！' ||
                (char.code in 0x3000..0x303F)
    }

    /**
     * 문자열이 오직 숫자만으로 구성되어 있는지 확인합니다.
     * (현재 recognizeText에서는 직접 호출되지 않지만, 유틸리티 함수로 남겨둡니다.)
     */
    private fun isNumericOnly(text: String): Boolean {
        if (text.isEmpty()) {
            return false
        }
        return text.all { it.isDigit() }
    }

    /**
     * 요청하신 1번 필터링 규칙을 적용합니다.
     * 텍스트가 (히라가나 2문자 이상) 또는 (가타카나 2문자 이상) 또는 (한자 1문자 이상)인지 검사합니다.
     * 이 함수는 `keepOnlyJapaneseRelatedChars`를 통과한 텍스트 또는 원본 텍스트에 대해 호출될 수 있습니다.
     * 현재 로직에서는 `keepOnlyJapaneseRelatedChars`가 실행되지 않으므로, 이 함수가 다양한 문자를 처리해야 합니다.
     */
    private fun meetsJapaneseCharacterRequirement(text: String): Boolean {
        if (text.isEmpty()) return false

        var hiraganaCount = 0
        var katakanaCount = 0
        var hanCount = 0

        for (char in text) {
            // 이 함수가 원본 텍스트(또는 keepOnlyJapaneseRelatedChars를 거치지 않은 텍스트)를 처리하므로,
            // 히라가나, 가타카나, 한자 외의 문자는 여기서 걸러지지 않고 단순히 카운트되지 않습니다.
            if (isHiragana(char)) {
                hiraganaCount++
            } else if (isKatakana(char)) {
                katakanaCount++
            } else if (isHanChar(char)) {
                hanCount++
            }
        }

        val meetsRequirement = hiraganaCount >= 2 || katakanaCount >= 2 || hanCount >= 1
        if (!meetsRequirement) {
            // logger.d(TAG, "Text \"$text\" does not meet Japanese character requirements (H:$hiraganaCount, K:$katakanaCount, Han:$hanCount).")
        }
        return meetsRequirement
    }


    override suspend fun recognizeText(bitmap: Bitmap, targetLanguageHint: String?): Result<List<OcrTextBlock>> {
        logger.d(TAG, "recognizeText called. Language hint: $targetLanguageHint")
        val image = InputImage.fromBitmap(bitmap, 0)

        val recognizer = recognizers[targetLanguageHint?.lowercase()] ?: recognizers["latin"] ?: run {
            logger.e(TAG, "No suitable TextRecognizer found for hint: $targetLanguageHint. Defaulting failed or no latin recognizer.")
            return Result.failure(IllegalStateException("No suitable TextRecognizer available. Check recognizer map and language hint."))
        }

        logger.d(TAG, "Using recognizer: ${recognizer::class.java.simpleName}")

        return try {
            val result: Text = recognizer.process(image).await()
            val filteredOcrTextBlocks = mutableListOf<OcrTextBlock>()

            if (result.textBlocks.isEmpty()) {
                logger.d(TAG, "No text found in the image.")
            } else {
                logger.d(TAG, "Text found: ${result.textBlocks.size} raw blocks. Applying new simplified filters.")
            }

            for (block in result.textBlocks) {
                val originalText = block.text
                var finalTextToUse = originalText.trim() // 초기 finalTextToUse는 trim된 원본

                if (finalTextToUse.isEmpty()) {
                    continue
                }

                // 1. 핵심 일본어 문자 포함 여부 검사 (사용자 요청 규칙 1) - 가장 먼저 실행
                // 이 검사는 이제 다양한 문자가 포함될 수 있는 finalTextToUse(trimmedOriginalText)에 대해 수행됩니다.
                if (!meetsJapaneseCharacterRequirement(finalTextToUse)) {
                    logger.d(TAG, "Original: \"$originalText\" -> Filtered out by Japanese Character Requirement. Current: \"$finalTextToUse\"")
                    finalTextToUse = "" // 조건 미달 시 필터링, 이후 로직 건너뜀
                }

                // finalTextToUse가 이전 단계에서 빈 문자열이 되지 않았다면 다음 처리 진행
                if (finalTextToUse.isNotEmpty()) {
                    // 2. 반복되는 일본어 구두점 축약 (사용자 요청 규칙 2)
                    // keepOnlyJapaneseRelatedChars를 건너뛰므로, 다양한 문자가 포함된 텍스트에 대해 수행됩니다.
                    // condenseRepeatingJapanesePunctuation 함수는 구두점만 대상으로 하므로 큰 문제는 없을 것입니다.
                    finalTextToUse = condenseRepeatingJapanesePunctuation(finalTextToUse)

                    // 3. 최종 정리 (앞뒤 공백 한 번 더 제거)
                    finalTextToUse = finalTextToUse.trim()
                }

                // 1. (실행 제거) 일본어 관련 문자, 숫자, 지정된 구두점, 공백만 남기기
                // val japaneseRelatedText = keepOnlyJapaneseRelatedChars(trimmedOriginalText)

                // --- 기존 필터 B, C, D의 실행부 주석 처리 ---
                /*
                // B. 기존 필터: 단일 히라가나 또는 단일 가타카나 문자 필터링 (한자는 제외)
                if (finalTextToUse.length == 1) {
                    val singleChar = finalTextToUse.first()
                    if ((isHiragana(singleChar) || isKatakana(singleChar)) && !isHanChar(singleChar)) {
                        logger.d(TAG, "Original: \"$originalText\" -> Filtered out (single Hiragana/Katakana after main filter: \"$finalTextToUse\")")
                        finalTextToUse = ""
                    }
                }

                // C. 기존 필터: 허용된 일본어 구두점/기호만으로 이루어진 문자열 필터링
                if (finalTextToUse.isNotEmpty() && finalTextToUse.all { isAllowedJapanesePunctuationOrSymbol(it) }) {
                    logger.d(TAG, "Original: \"$originalText\" -> Filtered out (consists only of allowed Japanese punctuation/symbols: \"$finalTextToUse\")")
                    finalTextToUse = ""
                }

                // D. 기존 필터: 숫자만으로 이루어진 문자열 필터링
                if (finalTextToUse.isNotEmpty() && isNumericOnly(finalTextToUse)) {
                    logger.d(TAG, "Original: \"$originalText\" -> Filtered out (consists only of digits: \"$finalTextToUse\")")
                    finalTextToUse = ""
                }
                */
                // --- 주석 처리 끝 ---

                if (finalTextToUse.isNotEmpty()) {
                    // keepOnlyJapaneseRelatedChars를 실행하지 않았으므로,
                    // finalTextToUse에는 여전히 일본어 외 문자(예: 영어 알파벳, 한국어 등)가 포함될 수 있습니다.
                    // 만약 최종 결과물에 오직 일본어 관련 문자만 남기길 원한다면,
                    // 여기서 한 번 더 keepOnlyJapaneseRelatedChars(finalTextToUse)를 호출하거나,
                    // meetsJapaneseCharacterRequirement 통과 후 바로 적용하는 것을 고려해야 합니다.
                    // 현재 요청은 실행부 제거이므로, 이 부분은 그대로 둡니다.
                    logger.d(TAG, "Original: \"$originalText\" -> 최종 Processed: \"$finalTextToUse\"")
                    filteredOcrTextBlocks.add(
                        OcrTextBlock(
                            text = finalTextToUse,
                            boundingBox = block.boundingBox ?: Rect(0, 0, 0, 0),
                            languageCode = block.recognizedLanguage
                        )
                    )
                } else {
                    // logger.d(TAG, "Original: \"$originalText\" -> 최종 Filtered out (resulted in empty string after all filters)")
                }
            }
            logger.d(TAG, "Filtering complete. Kept ${filteredOcrTextBlocks.size} blocks after all processing.")
            Result.success(filteredOcrTextBlocks)

        } catch (e: Exception) {
            logger.e(TAG, "Text recognition or filtering failed", e)
            Result.failure(e)
        }
    }
}
