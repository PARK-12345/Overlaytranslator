package com.example.overlaytranslator.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.overlaytranslator.R
import com.example.overlaytranslator.data.GeneralSettings
import com.example.overlaytranslator.data.OverlayButtonSettings
import com.example.overlaytranslator.data.TranslationTextStyle
import com.example.overlaytranslator.databinding.ActivitySettingsBinding
import dagger.hilt.android.AndroidEntryPoint

// Helper extension function to format Float? for EditText (기존과 동일)
fun Float?.toEditableString(): String {
    if (this == null) return ""
    return if (this % 1 == 0.0f) {
        this.toInt().toString()
    } else {
        this.toString()
    }
}

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
    }

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()

    private val textAlignmentMap = mapOf(
        "LEFT" to Gravity.START,
        "CENTER" to Gravity.CENTER,
        "RIGHT" to Gravity.END
    )
    // R.array.source_language_values 와 동일한 순서여야 함
    private val languageValueKeys = arrayOf("ja", "en", "ko")

    private var isUpdatingUIFromViewModel = false // ViewModel 업데이트로 인한 UI 변경 시 리스너 중복 호출 방지 플래그

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate called.")

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_activity_settings)

        setupSpinners()
        setupListeners()
        observeViewModel()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupSpinners() {
        Log.d(TAG, "Setting up spinners.")
        // 텍스트 정렬 스피너
        ArrayAdapter.createFromResource(
            this,
            R.array.text_alignment_entries, // 표시될 텍스트 배열
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerContentAlignment.adapter = adapter
        }

        // 기본 원본 언어 스피너
        ArrayAdapter.createFromResource(
            this,
            R.array.source_language_entries, // 표시될 텍스트 배열 (예: 일본어, 영어, 한국어)
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerDefaultSourceLanguage.adapter = adapter
        }
    }


    private fun setupListeners() {
        Log.d(TAG, "Setting up listeners.")
        binding.buttonSaveSettings.setOnClickListener {
            Log.d(TAG, "Save Settings button clicked.")
            saveSettings()
        }

        // 배경 투명도 SeekBar 리스너
        binding.seekBarBackgroundAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.textViewBackgroundAlphaValue.text = progress.toString()
                if (fromUser && !isUpdatingUIFromViewModel) {
                    viewModel.updateBackgroundAlpha(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // EditText 리스너들
        binding.editTextFontSize.addTextChangedListener(createTextWatcher { s ->
            if (!isUpdatingUIFromViewModel) viewModel.updateFontSize(s.toFloatOrNull())
        })
        binding.editTextTextColor.addTextChangedListener(createTextWatcher { s ->
            if (!isUpdatingUIFromViewModel) viewModel.updateTextColor(s)
        })
        binding.editTextBackgroundColor.addTextChangedListener(createTextWatcher { s ->
            if (!isUpdatingUIFromViewModel) viewModel.updateBackgroundColor(s)
        })
        binding.editTextLineSpacing.addTextChangedListener(createTextWatcher { s ->
            if (!isUpdatingUIFromViewModel) viewModel.updateLineSpacing(s.toFloatOrNull())
        })

        // 텍스트 정렬 스피너 리스너
        binding.spinnerContentAlignment.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isUpdatingUIFromViewModel) {
                    // R.array.text_alignment_values 와 매핑하여 실제 Gravity 값 전달
                    val selectedValueKey = resources.getStringArray(R.array.text_alignment_values)[position]
                    textAlignmentMap[selectedValueKey]?.let { viewModel.updateTextAlignment(it) }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 기본 원본 언어 스피너 리스너
        binding.spinnerDefaultSourceLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isUpdatingUIFromViewModel) {
                    val selectedLangCode = languageValueKeys[position] // 실제 언어 코드 (예: "ja")
                    viewModel.updateDefaultSourceLanguage(selectedLangCode)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 자동 언어 감지 스위치 리스너
        binding.switchAutoDetectLanguage.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUIFromViewModel) {
                viewModel.updateAutoDetectLanguage(isChecked)
            }
            binding.spinnerDefaultSourceLanguage.isEnabled = !isChecked // 자동 감지 시 기본 언어 스피너 비활성화
        }

        // API 관련 EditText 리스너들
        binding.editTextGeminiApiKey.addTextChangedListener(createTextWatcher { s -> if (!isUpdatingUIFromViewModel) viewModel.updateGeminiApiKey(s) })
        binding.editTextGeminiPrompt.addTextChangedListener(createTextWatcher { s -> if (!isUpdatingUIFromViewModel) viewModel.updateGeminiPrompt(s) })
        binding.editTextGeminiModelName.addTextChangedListener(createTextWatcher { s -> if (!isUpdatingUIFromViewModel) viewModel.updateGeminiModelName(s) })
        binding.editTextThinkingBudget.addTextChangedListener(createTextWatcher { s -> if (!isUpdatingUIFromViewModel) viewModel.updateThinkingBudget(s.toIntOrNull()) })
        binding.editTextTemperature.addTextChangedListener(createTextWatcher { s -> if (!isUpdatingUIFromViewModel) viewModel.updateTemperature(s.toFloatOrNull()) })

        // 필터 및 캐시 관련 EditText 리스너들
        binding.editTextForbiddenKeywords.addTextChangedListener(createTextWatcher { s -> if (!isUpdatingUIFromViewModel) viewModel.updateForbiddenKeywords(s) })
        binding.editTextSimilarityTolerance.addTextChangedListener(createTextWatcher { s ->
            s.toIntOrNull()?.let { if (!isUpdatingUIFromViewModel) viewModel.updateSimilarityTolerance(it) }
        })
        binding.editTextMaxCacheSize.addTextChangedListener(createTextWatcher { s ->
            s.toIntOrNull()?.let { if (!isUpdatingUIFromViewModel) viewModel.updateMaxCacheSize(it) }
        })


        // 오버레이 버튼 관련 EditText 리스너들
        binding.editTextButtonSize.addTextChangedListener(createTextWatcher { s -> s.toIntOrNull()?.let { if (!isUpdatingUIFromViewModel) viewModel.updateButtonSize(it) } })
        binding.editTextCaptureDelay.addTextChangedListener(createTextWatcher { s -> s.toIntOrNull()?.let { if (!isUpdatingUIFromViewModel) viewModel.updateCaptureDelay(it) } })
    }

    private fun createTextWatcher(onTextChangedLogic: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                onTextChangedLogic(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
    }

    private fun observeViewModel() {
        Log.d(TAG, "Observing ViewModel.")
        viewModel.translationTextStyle.observe(this) { style ->
            style?.let { updateTranslationStyleUI(it) }
        }
        viewModel.overlayButtonSettings.observe(this) { settings ->
            settings?.let { updateButtonSettingsUI(it) }
        }
        viewModel.generalSettings.observe(this) { settings ->
            settings?.let { updateGeneralSettingsUI(it) }
        }
        viewModel.toastMessage.observe(this) { message ->
            message?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                viewModel.onToastShown() // Toast가 표시된 후 ViewModel에 알림
            }
        }
    }

    // UI 업데이트 중복 방지를 위한 래퍼 함수
    private fun <T> updateUI(action: () -> T): T {
        isUpdatingUIFromViewModel = true
        val result = action()
        isUpdatingUIFromViewModel = false
        return result
    }

    private fun updateTranslationStyleUI(style: TranslationTextStyle) = updateUI {
        Log.d(TAG, "Updating TranslationStyleUI with: $style")
        val fontSizeStr = style.fontSize.toEditableString()
        if (binding.editTextFontSize.text.toString() != fontSizeStr) {
            binding.editTextFontSize.setText(fontSizeStr)
        }
        if (binding.editTextTextColor.text.toString() != style.textColor) {
            binding.editTextTextColor.setText(style.textColor)
        }
        if (binding.editTextBackgroundColor.text.toString() != style.backgroundColor) {
            binding.editTextBackgroundColor.setText(style.backgroundColor)
        }
        if (binding.seekBarBackgroundAlpha.progress != style.backgroundAlpha) {
            binding.seekBarBackgroundAlpha.progress = style.backgroundAlpha
        }
        binding.textViewBackgroundAlphaValue.text = style.backgroundAlpha.toString()

        val lineSpacingStr = style.lineSpacingExtra.toEditableString()
        if (binding.editTextLineSpacing.text.toString() != lineSpacingStr) {
            binding.editTextLineSpacing.setText(lineSpacingStr)
        }

        val alignmentKey = textAlignmentMap.entries.firstOrNull { it.value == style.textAlignment }?.key
        val alignmentValuesArray = resources.getStringArray(R.array.text_alignment_values)
        val alignmentPosition = alignmentValuesArray.indexOf(alignmentKey)
        if (alignmentPosition >= 0 && binding.spinnerContentAlignment.selectedItemPosition != alignmentPosition) {
            binding.spinnerContentAlignment.setSelection(alignmentPosition)
        }
    }

    private fun updateButtonSettingsUI(settings: OverlayButtonSettings) = updateUI {
        Log.d(TAG, "Updating ButtonSettingsUI with: $settings")
        if (binding.editTextButtonSize.text.toString() != settings.size.toString()) {
            binding.editTextButtonSize.setText(settings.size.toString())
        }
    }

    private fun updateGeneralSettingsUI(settings: GeneralSettings) = updateUI {
        Log.d(TAG, "Updating GeneralSettingsUI with: $settings")
        if (binding.editTextGeminiApiKey.text.toString() != settings.geminiApiKey) {
            binding.editTextGeminiApiKey.setText(settings.geminiApiKey)
        }
        if (binding.editTextGeminiPrompt.text.toString() != settings.geminiPrompt) {
            binding.editTextGeminiPrompt.setText(settings.geminiPrompt)
        }
        if (binding.editTextGeminiModelName.text.toString() != settings.geminiModelName) {
            binding.editTextGeminiModelName.setText(settings.geminiModelName)
        }
        val thinkingBudgetStr = settings.thinkingBudget?.toString() ?: ""
        if (binding.editTextThinkingBudget.text.toString() != thinkingBudgetStr) {
            binding.editTextThinkingBudget.setText(thinkingBudgetStr)
        }
        val temperatureStr = settings.temperature.toEditableString()
        if (binding.editTextTemperature.text.toString() != temperatureStr) {
            binding.editTextTemperature.setText(temperatureStr)
        }
        if (binding.editTextForbiddenKeywords.text.toString() != settings.forbiddenKeywords) {
            binding.editTextForbiddenKeywords.setText(settings.forbiddenKeywords)
        }
        if (binding.editTextCaptureDelay.text.toString() != settings.captureDelayMs.toString()) {
            binding.editTextCaptureDelay.setText(settings.captureDelayMs.toString())
        }
        if (binding.switchAutoDetectLanguage.isChecked != settings.autoDetectSourceLanguage) {
            binding.switchAutoDetectLanguage.isChecked = settings.autoDetectSourceLanguage
        }
        binding.spinnerDefaultSourceLanguage.isEnabled = !settings.autoDetectSourceLanguage

        val langPosition = languageValueKeys.indexOf(settings.defaultSourceLanguage)
        if (langPosition >= 0 && binding.spinnerDefaultSourceLanguage.selectedItemPosition != langPosition) {
            binding.spinnerDefaultSourceLanguage.setSelection(langPosition)
        }

        // 새로 추가된 설정 UI 업데이트
        if (binding.editTextSimilarityTolerance.text.toString() != settings.similarityTolerance.toString()) {
            binding.editTextSimilarityTolerance.setText(settings.similarityTolerance.toString())
        }
        if (binding.editTextMaxCacheSize.text.toString() != settings.maxCacheSize.toString()) {
            binding.editTextMaxCacheSize.setText(settings.maxCacheSize.toString())
        }
    }

    private fun saveSettings() {
        try {
            // 스타일 설정 가져오기
            val fontSize = binding.editTextFontSize.text.toString().toFloatOrNull()
            val textColor = binding.editTextTextColor.text.toString().ifBlank { TranslationTextStyle().textColor }
            val backgroundColor = binding.editTextBackgroundColor.text.toString().ifBlank { TranslationTextStyle().backgroundColor }
            val backgroundAlpha = binding.seekBarBackgroundAlpha.progress
            val lineSpacing = binding.editTextLineSpacing.text.toString().toFloatOrNull()
            val selectedAlignmentValueKey = resources.getStringArray(R.array.text_alignment_values)[binding.spinnerContentAlignment.selectedItemPosition]
            val textAlignment = textAlignmentMap[selectedAlignmentValueKey] ?: Gravity.START
            val translationStyle = TranslationTextStyle(fontSize, textColor, backgroundColor, backgroundAlpha, lineSpacing, textAlignment)

            // 버튼 설정 가져오기
            val buttonSize = binding.editTextButtonSize.text.toString().toIntOrNull() ?: OverlayButtonSettings().size
            val currentButtonSettingsVal = viewModel.overlayButtonSettings.value ?: OverlayButtonSettings() // lastX, lastY 등 유지
            val buttonSettings = currentButtonSettingsVal.copy(size = buttonSize)

            // 일반 설정 가져오기
            val geminiApiKey = binding.editTextGeminiApiKey.text.toString()
            val geminiPrompt = binding.editTextGeminiPrompt.text.toString().ifBlank { GeneralSettings().geminiPrompt }
            val geminiModelName = binding.editTextGeminiModelName.text.toString().ifBlank { GeneralSettings().geminiModelName }
            val thinkingBudget = binding.editTextThinkingBudget.text.toString().toIntOrNull()
            val temperature = binding.editTextTemperature.text.toString().toFloatOrNull()
            val forbiddenKeywords = binding.editTextForbiddenKeywords.text.toString()
            val captureDelay = binding.editTextCaptureDelay.text.toString().toIntOrNull() ?: GeneralSettings().captureDelayMs
            val autoDetect = binding.switchAutoDetectLanguage.isChecked
            val defaultLangCode = languageValueKeys[binding.spinnerDefaultSourceLanguage.selectedItemPosition]
            val targetLanguage = viewModel.generalSettings.value?.targetLanguage ?: GeneralSettings().targetLanguage // 기존 값 유지 또는 기본값

            // 새로 추가된 설정값 가져오기
            val similarityTolerance = binding.editTextSimilarityTolerance.text.toString().toIntOrNull() ?: GeneralSettings().similarityTolerance
            val maxCacheSize = binding.editTextMaxCacheSize.text.toString().toIntOrNull() ?: GeneralSettings().maxCacheSize

            val generalSettings = GeneralSettings(
                geminiApiKey = geminiApiKey,
                geminiPrompt = geminiPrompt,
                geminiModelName = geminiModelName,
                thinkingBudget = thinkingBudget,
                temperature = temperature,
                captureDelayMs = captureDelay,
                autoDetectSourceLanguage = autoDetect,
                defaultSourceLanguage = defaultLangCode,
                targetLanguage = targetLanguage,
                forbiddenKeywords = forbiddenKeywords,
                similarityTolerance = similarityTolerance, // 새 값 할당
                maxCacheSize = maxCacheSize             // 새 값 할당
            )

            viewModel.saveAllSettings(translationStyle, buttonSettings, generalSettings)
            Log.i(TAG, "Attempting to save settings: Style=$translationStyle, Button=$buttonSettings, General=$generalSettings")

        } catch (e: Exception) {
            Toast.makeText(this, "입력 값에 오류가 있습니다: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Error parsing settings input", e)
        }
    }
}
