package com.example.overlaytranslator.services

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.overlaytranslator.R
import com.example.overlaytranslator.data.GeneralSettings
import com.example.overlaytranslator.data.OcrTextBlock
import com.example.overlaytranslator.data.OverlayButtonSettings
import com.example.overlaytranslator.data.SettingsRepository
import com.example.overlaytranslator.data.TranslatedTextElement
import com.example.overlaytranslator.data.TranslationTextStyle
import com.example.overlaytranslator.databinding.OverlayButtonLayoutBinding
import com.example.overlaytranslator.databinding.OverlayTranslationLayoutBinding
import com.example.overlaytranslator.domain.ocr.OcrManager
import com.example.overlaytranslator.domain.screencapture.ScreenCaptureManager
import com.example.overlaytranslator.domain.translation.ApiKeyNotSetException
import com.example.overlaytranslator.domain.translation.TranslationManager
import com.example.overlaytranslator.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

@AndroidEntryPoint
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 12345
        private const val NOTIFICATION_CHANNEL_ID = "OverlayServiceChannel"
        const val ACTION_REQUEST_MEDIA_PROJECTION = "com.example.overlaytranslator.REQUEST_MEDIA_PROJECTION"

        var isRunning = false
            private set
    }

    @Inject
    lateinit var windowManager: WindowManager
    @Inject
    lateinit var settingsRepository: SettingsRepository
    @Inject
    lateinit var screenCaptureManager: ScreenCaptureManager
    @Inject
    lateinit var ocrManager: OcrManager
    @Inject
    lateinit var translationManager: TranslationManager

    private val serviceJob = SupervisorJob()
    private val mainScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val ioScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var overlayButtonBinding: OverlayButtonLayoutBinding
    private lateinit var overlayButtonView: View
    private lateinit var overlayButtonParams: WindowManager.LayoutParams

    private lateinit var translationOverlayBinding: OverlayTranslationLayoutBinding
    private lateinit var translationOverlayView: View
    private lateinit var translationOverlayParams: WindowManager.LayoutParams
    private val translatedTextViews = mutableListOf<TextView>()

    private var initialButtonX = 0
    private var initialButtonY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isMoving = false

    private lateinit var gestureDetector: GestureDetector

    private lateinit var currentTextStyle: TranslationTextStyle
    private lateinit var currentButtonSettings: OverlayButtonSettings
    private lateinit var currentGeneralSettings: GeneralSettings

    private val handler = Handler(Looper.getMainLooper())
    private var notification: Notification? = null
    private var isForegroundServiceSuccessfullyStartedWithType = false
    private var areTranslationsTemporarilyHidden = false

    private val REQUEST_BATCH_SEPARATOR = "\n\n"
    private val RESPONSE_SPLIT_REGEX = Regex("\n+")


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")
        isRunning = true
        screenCaptureManager.initialize()
        loadAllSettings()
        initializeOverlayButton()
        initializeTranslationOverlay()
        setupGestureDetector()
        observeSettingsChanges()
        createNotificationChannel()
        notification = createNotification()
        Log.d(TAG, "Notification object created in onCreate.")
    }

    private fun loadAllSettings() {
        currentTextStyle = settingsRepository.getTranslationTextStyle()
        currentButtonSettings = settingsRepository.getOverlayButtonSettings()
        currentGeneralSettings = settingsRepository.getGeneralSettings()
        Log.d(TAG, "Initial settings loaded. Button Settings: $currentButtonSettings")
    }

    private fun observeSettingsChanges() {
        mainScope.launch {
            settingsRepository.translationTextStyleFlow.collectLatest { style ->
                currentTextStyle = style
                updateAllTranslatedTextViewsStyle()
            }
        }
        mainScope.launch {
            settingsRepository.overlayButtonSettingsFlow.collectLatest { settings ->
                val oldSettings = currentButtonSettings
                currentButtonSettings = settings
                Log.d(TAG, "OverlayButtonSettings updated via flow: $settings")

                if (oldSettings.size != settings.size) {
                    updateOverlayButtonSize()
                    if (::overlayButtonParams.isInitialized && overlayButtonView.isAttachedToWindow) {
                        updateOverlayButtonSizeAndPosition(overlayButtonParams.x, overlayButtonParams.y)
                    }
                }
            }
        }
        mainScope.launch {
            settingsRepository.generalSettingsFlow.collectLatest { settings ->
                currentGeneralSettings = settings
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            channel.description = getString(R.string.notification_channel_description)
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent).setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun initializeOverlayButton() {
        overlayButtonBinding = OverlayButtonLayoutBinding.inflate(LayoutInflater.from(this))
        overlayButtonView = overlayButtonBinding.root
        val button = overlayButtonBinding.overlayButton
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        overlayButtonParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            var initialX = currentButtonSettings.lastX
            var initialY = currentButtonSettings.lastY
            val refWidth = currentButtonSettings.referenceScreenWidth
            val refHeight = currentButtonSettings.referenceScreenHeight

            if (refWidth > 0 && refHeight > 0) {
                val currentDisplayMetrics = getCurrentDisplayMetrics()
                val currentDisplayWidth = currentDisplayMetrics.widthPixels
                val currentDisplayHeight = currentDisplayMetrics.heightPixels

                if (currentDisplayWidth > 0 && currentDisplayHeight > 0) {
                    val xRatio = initialX.toFloat() / refWidth.toFloat()
                    val yRatio = initialY.toFloat() / refHeight.toFloat()
                    initialX = (xRatio * currentDisplayWidth).toInt()
                    initialY = (yRatio * currentDisplayHeight).toInt()
                }
            }
            x = initialX
            y = initialY
        }
        updateOverlayButtonSize()

        val currentScreenMetrics = getCurrentDisplayMetrics()
        val screenWidthForClamp = currentScreenMetrics.widthPixels
        val screenHeightForClamp = currentScreenMetrics.heightPixels

        if (overlayButtonParams.width > 0 && overlayButtonParams.height > 0) {
            overlayButtonParams.x = max(0, min(overlayButtonParams.x, screenWidthForClamp - overlayButtonParams.width))
            overlayButtonParams.y = max(0, min(overlayButtonParams.y, screenHeightForClamp - overlayButtonParams.height))
        }

        try {
            windowManager.addView(overlayButtonView, overlayButtonParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay button", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, getString(R.string.error_permission_draw_overlay), Toast.LENGTH_LONG).show()
            }
            stopSelf(); return
        }

        button.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isMoving) {
                        initialButtonX = overlayButtonParams.x
                        initialButtonY = overlayButtonParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isMoving) {
                        overlayButtonParams.x = initialButtonX + (event.rawX - initialTouchX).toInt()
                        overlayButtonParams.y = initialButtonY + (event.rawY - initialTouchY).toInt()
                        try { windowManager.updateViewLayout(overlayButtonView, overlayButtonParams) }
                        catch (e: Exception) { Log.e(TAG, "Error updating button view layout during move", e) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isMoving) {
                        isMoving = false
                        val currentScreenMetricsOnSave = getCurrentDisplayMetrics()
                        settingsRepository.saveOverlayButtonPosition(
                            overlayButtonParams.x,
                            overlayButtonParams.y,
                            currentScreenMetricsOnSave.widthPixels,
                            currentScreenMetricsOnSave.heightPixels
                        )
                    }
                }
            }
            true
        }
    }

    private fun updateOverlayButtonSizeAndPosition(newX: Int?, newY: Int? = null) {
        if (!::overlayButtonView.isInitialized || !overlayButtonView.isAttachedToWindow) return
        updateOverlayButtonSize()

        val finalX = newX ?: overlayButtonParams.x
        val finalY = newY ?: overlayButtonParams.y

        overlayButtonParams.x = finalX
        overlayButtonParams.y = finalY

        val currentScreenMetrics = getCurrentDisplayMetrics()
        val screenWidth = currentScreenMetrics.widthPixels
        val screenHeight = currentScreenMetrics.heightPixels
        if (overlayButtonParams.width > 0 && overlayButtonParams.height > 0) {
            overlayButtonParams.x = max(0, min(overlayButtonParams.x, screenWidth - overlayButtonParams.width))
            overlayButtonParams.y = max(0, min(overlayButtonParams.y, screenHeight - overlayButtonParams.height))
        }

        try {
            windowManager.updateViewLayout(overlayButtonView, overlayButtonParams)
        } catch (e: Exception) { Log.e(TAG, "Error updating overlay button size/position", e) }
    }


    private fun updateOverlayButtonSize() {
        if (!::overlayButtonBinding.isInitialized) return
        val sizeInPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, currentButtonSettings.size.toFloat(), resources.displayMetrics).toInt()
        val lp = overlayButtonBinding.overlayButton.layoutParams.apply { width = sizeInPx; height = sizeInPx }
        overlayButtonBinding.overlayButton.layoutParams = lp

        if (::overlayButtonParams.isInitialized) {
            overlayButtonParams.width = sizeInPx
            overlayButtonParams.height = sizeInPx
        }
    }

    @SuppressLint("InflateParams")
    private fun initializeTranslationOverlay() {
        translationOverlayBinding = OverlayTranslationLayoutBinding.inflate(LayoutInflater.from(this))
        translationOverlayView = translationOverlayBinding.root
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        translationOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        translationOverlayView.visibility = View.GONE
        try { windowManager.addView(translationOverlayView, translationOverlayParams) }
        catch (e: Exception) { Log.e(TAG, "Error adding translation overlay", e); stopSelf() }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                Log.d(TAG, "onSingleTapConfirmed")
                handleSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.d(TAG, "onDoubleTap")
                handleDoubleTap()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                Log.d(TAG, "onLongPress")
                isMoving = true
            }
        })
    }

    private fun handleSingleTap() {
        if (!isForegroundServiceSuccessfullyStartedWithType) { Toast.makeText(this, getString(R.string.error_service_not_ready_media_projection), Toast.LENGTH_LONG).show(); return }
        if (!screenCaptureManager.isMediaProjectionReady()) { Toast.makeText(this, getString(R.string.error_media_projection_not_ready), Toast.LENGTH_LONG).show(); sendBroadcast(Intent(ACTION_REQUEST_MEDIA_PROJECTION)); return }

        // 싱글 탭 시에는 항상 번역 내용이 보이도록 플래그를 초기화
        areTranslationsTemporarilyHidden = false
        Log.d(TAG, "Translations will be shown on this single tap.")

        // 1. 버튼 및 번역 오버레이 숨김
        overlayButtonView.visibility = View.GONE
        translationOverlayView.visibility = View.GONE // 새로운 번역을 위해 기존 번역은 숨김
        Log.d(TAG, "Overlays hidden for new capture.")

        // 2. 사용자가 설정한 캡처 지연 시간 후 화면 캡처
        handler.postDelayed({
            if (screenCaptureManager.isCapturing()) {
                if (overlayButtonView.visibility == View.GONE) overlayButtonView.visibility = View.VISIBLE
                return@postDelayed
            }
            Log.d(TAG, "After user's captureDelayMs (${currentGeneralSettings.captureDelayMs}ms). Performing screen capture.")
            performScreenCapture()

        }, currentGeneralSettings.captureDelayMs.toLong())
    }

    private fun performScreenCapture() {
        screenCaptureManager.captureScreen { bitmap ->
            mainScope.launch {
                try {
                    if (bitmap != null) {
                        processCapturedImage(bitmap)
                    } else {
                        Log.e(TAG, "Screen capture failed, bitmap is null.")
                        Toast.makeText(this@OverlayService, R.string.error_capture_failed, Toast.LENGTH_SHORT).show()
                        clearTranslationOverlay() // 이전 번역 내용 클리어
                        if (overlayButtonView.visibility == View.GONE) overlayButtonView.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing captured image or displaying results", e)
                    Toast.makeText(this@OverlayService, "${getString(R.string.error_processing_capture)}: ${e.message}", Toast.LENGTH_SHORT).show()
                    clearTranslationOverlay() // 이전 번역 내용 클리어
                    if (overlayButtonView.visibility == View.GONE) overlayButtonView.visibility = View.VISIBLE
                }
            }
        }
    }

    private suspend fun processCapturedImage(bitmap: Bitmap) {
        val ocrResult = withContext(Dispatchers.IO) {
            val ocrSourceLanguage = if (currentGeneralSettings.autoDetectSourceLanguage) null else currentGeneralSettings.defaultSourceLanguage
            ocrManager.recognizeText(bitmap, ocrSourceLanguage)
        }

        ocrResult.fold(
            onSuccess = { initialOcrTextBlocks ->
                if (initialOcrTextBlocks.isEmpty()) {
                    Log.d(TAG, "OCR found no text blocks.")
                    withContext(Dispatchers.Main) { clearTranslationOverlay() } // 이전 번역 내용 클리어
                    if (!bitmap.isRecycled) bitmap.recycle()
                    if (overlayButtonView.visibility == View.GONE) overlayButtonView.visibility = View.VISIBLE
                } else {
                    Log.d(TAG, "OCR successful, ${initialOcrTextBlocks.size} blocks found. Starting translation.")
                    translateOcrResults(initialOcrTextBlocks, bitmap)
                }
            },
            onFailure = { e ->
                Log.e(TAG, "OCR failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OverlayService, R.string.error_ocr_failed, Toast.LENGTH_SHORT).show()
                    clearTranslationOverlay() // 이전 번역 내용 클리어
                }
                if (!bitmap.isRecycled) bitmap.recycle()
                if (overlayButtonView.visibility == View.GONE) overlayButtonView.visibility = View.VISIBLE
            }
        )
    }

    private suspend fun translateOcrResults(initialOcrTextBlocks: List<OcrTextBlock>, originalBitmap: Bitmap) {
        var bitmapHandled = false
        try {
            if (currentGeneralSettings.geminiApiKey.isBlank()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OverlayService, R.string.error_api_key_not_set, Toast.LENGTH_LONG).show()
                    clearTranslationOverlay() // 이전 번역 내용 클리어
                }
                if (!originalBitmap.isRecycled) originalBitmap.recycle()
                bitmapHandled = true
                return
            }

            val validBlocksForBatch = mutableListOf<OcrTextBlock>()
            val singleLineTextsForBatch = mutableListOf<String>()

            for (block in initialOcrTextBlocks) {
                val processedTextForBatchSegment = block.text.replace('\n', ' ').trim()
                if (processedTextForBatchSegment.isNotEmpty()) {
                    validBlocksForBatch.add(block)
                    singleLineTextsForBatch.add(processedTextForBatchSegment)
                }
            }

            if (singleLineTextsForBatch.isEmpty()) {
                Log.d(TAG, "No valid text segments for batch translation after preprocessing.")
                withContext(Dispatchers.Main) { clearTranslationOverlay() } // 이전 번역 내용 클리어
                if (!originalBitmap.isRecycled) originalBitmap.recycle()
                bitmapHandled = true
                return
            }

            val combinedTextForBatching = singleLineTextsForBatch.joinToString(REQUEST_BATCH_SEPARATOR)
            val numSegmentsSent = singleLineTextsForBatch.size
            val targetLang = currentGeneralSettings.targetLanguage

            Log.d(TAG, "Attempting batch translation for $numSegmentsSent OCR blocks (sent as $numSegmentsSent segments separated by '$REQUEST_BATCH_SEPARATOR'). Combined text: \"$combinedTextForBatching\"")

            val sourceLangForBatch = if (currentGeneralSettings.autoDetectSourceLanguage) null else currentGeneralSettings.defaultSourceLanguage
            val batchTranslationResult = translationManager.translateText(
                combinedTextForBatching,
                sourceLangForBatch,
                targetLang,
                isBatchedRequest = true
            )

            batchTranslationResult.fold(
                onSuccess = { batchedTranslatedString ->
                    Log.d(TAG, "Batch translation API response (raw): \"$batchedTranslatedString\"")
                    val translatedApiSegmentsProvisional = batchedTranslatedString.split(RESPONSE_SPLIT_REGEX)
                    val translatedApiSegments = translatedApiSegmentsProvisional.map { it.trim() }.filter { it.isNotBlank() }

                    Log.d(TAG, "Batch translation - OCR blocks sent: $numSegmentsSent, API returned non-blank segments after splitting by REGEX '$RESPONSE_SPLIT_REGEX' and filtering: ${translatedApiSegments.size}")
                    translatedApiSegments.forEachIndexed { index, segment -> Log.d(TAG, "API Segment $index: \"$segment\"") }

                    val translatedElements = mutableListOf<TranslatedTextElement>()

                    if (numSegmentsSent == 1 && translatedApiSegments.isNotEmpty()) {
                        Log.d(TAG, "Single OCR block sent. Joining ${translatedApiSegments.size} API segments for it using '\\n'.")
                        val singleBlockTranslation = translatedApiSegments.joinToString("\n").trim()
                        val originalBlock = validBlocksForBatch[0]
                        val actualSourceLanguage = determineActualSourceLanguage(originalBlock, currentGeneralSettings)
                        translatedElements.add(
                            TranslatedTextElement(
                                originalText = originalBlock.text,
                                translatedText = singleBlockTranslation,
                                originalBoundingBox = originalBlock.boundingBox,
                                sourceLanguage = actualSourceLanguage,
                                targetLanguage = targetLang
                            )
                        )
                        withContext(Dispatchers.Main) { displayTranslatedTexts(translatedElements) }
                        if (!originalBitmap.isRecycled) originalBitmap.recycle()
                        bitmapHandled = true
                    } else if (translatedApiSegments.size == numSegmentsSent) {
                        Log.d(TAG, "Multiple OCR blocks sent, and API segment count matches after splitting by REGEX and filtering.")
                        for (i in 0 until numSegmentsSent) {
                            val originalBlock = validBlocksForBatch[i]
                            val translatedTextSegment = translatedApiSegments[i]
                            val actualSourceLanguage = determineActualSourceLanguage(originalBlock, currentGeneralSettings)
                            translatedElements.add(
                                TranslatedTextElement(
                                    originalText = originalBlock.text,
                                    translatedText = translatedTextSegment,
                                    originalBoundingBox = originalBlock.boundingBox,
                                    sourceLanguage = actualSourceLanguage,
                                    targetLanguage = targetLang
                                )
                            )
                        }
                        withContext(Dispatchers.Main) { displayTranslatedTexts(translatedElements) }
                        if (!originalBitmap.isRecycled) originalBitmap.recycle()
                        bitmapHandled = true
                    } else {
                        Log.w(TAG, "Batch translation segment count mismatch. OCR blocks sent: $numSegmentsSent, API non-blank segments: ${translatedApiSegments.size}. API Response (raw): \"$batchedTranslatedString\". Falling back to individual translation.")
                        translateIndividually(validBlocksForBatch, targetLang, originalBitmap)
                        bitmapHandled = true
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "Batch translation API call failed. Falling back.", e)
                    if (e is ApiKeyNotSetException) {
                        withContext(Dispatchers.Main) { Toast.makeText(this@OverlayService, R.string.error_api_key_not_set, Toast.LENGTH_LONG).show(); clearTranslationOverlay() }
                        if (!originalBitmap.isRecycled) originalBitmap.recycle()
                        bitmapHandled = true
                    } else {
                        translateIndividually(validBlocksForBatch, targetLang, originalBitmap)
                        bitmapHandled = true
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception in translateOcrResults", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@OverlayService, "${getString(R.string.error_internal_translation_error)}: ${e.message}", Toast.LENGTH_SHORT).show()
                clearTranslationOverlay() // 이전 번역 내용 클리어
            }
            if (!bitmapHandled && !originalBitmap.isRecycled) {
                originalBitmap.recycle()
                bitmapHandled = true
            }
        } finally {
            if (!bitmapHandled && !originalBitmap.isRecycled) {
                Log.w(TAG, "Bitmap was not handled earlier in translateOcrResults or its calls, recycling in finally.")
                originalBitmap.recycle()
            }
            withContext(Dispatchers.Main) {
                if (overlayButtonView.visibility == View.GONE) {
                    overlayButtonView.visibility = View.VISIBLE
                }
            }
        }
    }


    private suspend fun translateIndividually(
        blocksToTranslate: List<OcrTextBlock>,
        targetLang: String,
        bitmapToRecycle: Bitmap?
    ) {
        Log.d(TAG, "Executing individual translation for ${blocksToTranslate.size} blocks.")
        val translatedElements = mutableListOf<TranslatedTextElement>()
        var apiKeyErrorOccurred = false

        try {
            val translationJobs = blocksToTranslate.map { block ->
                ioScope.async {
                    val textForIndividualTranslation = block.text.replace('\n', ' ').trim()
                    if (textForIndividualTranslation.isEmpty()) return@async null

                    val sourceLangForIndividual = if (currentGeneralSettings.autoDetectSourceLanguage) block.languageCode else currentGeneralSettings.defaultSourceLanguage
                    translationManager.translateText(
                        textForIndividualTranslation,
                        sourceLangForIndividual,
                        targetLang,
                        isBatchedRequest = false
                    ).fold(
                        onSuccess = { translatedText ->
                            val actualSourceLanguage = determineActualSourceLanguage(block, currentGeneralSettings)
                            TranslatedTextElement(
                                originalText = block.text,
                                translatedText = translatedText.trim(),
                                originalBoundingBox = block.boundingBox,
                                sourceLanguage = actualSourceLanguage,
                                targetLanguage = targetLang
                            )
                        },
                        onFailure = { e ->
                            if (e is ApiKeyNotSetException) apiKeyErrorOccurred = true
                            Log.e(TAG, "Individual translation failed for block: '${block.text}'", e)
                            null
                        }
                    )
                }
            }
            val results = translationJobs.awaitAll()

            if (apiKeyErrorOccurred) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OverlayService, R.string.error_api_key_not_set, Toast.LENGTH_LONG).show()
                    clearTranslationOverlay() // 이전 번역 내용 클리어
                }
                return
            }

            results.filterNotNull().forEach { translatedElements.add(it) }

            withContext(Dispatchers.Main) {
                if (translatedElements.isNotEmpty()) {
                    displayTranslatedTexts(translatedElements)
                } else {
                    clearTranslationOverlay() // 이전 번역 내용 클리어
                    if (blocksToTranslate.any { it.text.replace('\n', ' ').trim().isNotEmpty() }) {
                        Toast.makeText(this@OverlayService, R.string.error_translation_failed_all, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during individual translations", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@OverlayService, R.string.error_translation_failed_all, Toast.LENGTH_SHORT).show()
                clearTranslationOverlay() // 이전 번역 내용 클리어
            }
        } finally {
            bitmapToRecycle?.let {
                if (!it.isRecycled) {
                    Log.d(TAG, "Recycling bitmap in translateIndividually finally block.")
                    it.recycle()
                }
            }
        }
    }


    private fun determineActualSourceLanguage(block: OcrTextBlock, settings: GeneralSettings): String {
        return if (settings.autoDetectSourceLanguage) {
            block.languageCode ?: "und"
        } else {
            settings.defaultSourceLanguage
        }
    }

    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) result = resources.getDimensionPixelSize(resourceId)
        return result
    }

    private fun displayTranslatedTexts(translatedElements: List<TranslatedTextElement>) {
        clearTranslationOverlay() // 새로운 내용을 표시하기 전에 이전 내용을 확실히 제거
        val container = translationOverlayBinding.translationOverlayContainer ?: return
        val currentOrientation = resources.configuration.orientation
        val statusBarHeight = getStatusBarHeight()

        Log.d(TAG, "Displaying ${translatedElements.size} translated elements. Orientation: $currentOrientation, SBH: $statusBarHeight. areTranslationsTemporarilyHidden: $areTranslationsTemporarilyHidden")

        for (el in translatedElements) {
            TextView(this).apply {
                text = el.translatedText
                textSize = currentTextStyle.fontSize ?: TranslationTextStyle().fontSize ?: 16f
                try {
                    setTextColor(Color.parseColor(currentTextStyle.textColor))
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Invalid text color format: ${currentTextStyle.textColor}", e)
                    setTextColor(Color.WHITE)
                }
                try {
                    val bg = Color.parseColor(currentTextStyle.backgroundColor)
                    setBackgroundColor(Color.argb(currentTextStyle.backgroundAlpha, Color.red(bg), Color.green(bg), Color.blue(bg)))
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Invalid background color format: ${currentTextStyle.backgroundColor}", e)
                    setBackgroundColor(Color.argb(currentTextStyle.backgroundAlpha, 0, 0, 0))
                }
                setLineSpacing(currentTextStyle.lineSpacingExtra ?: TranslationTextStyle().lineSpacingExtra ?: 4f, 1.0f)
                gravity = currentTextStyle.textAlignment
                setPadding(10, 0, 10, 5)

                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    val originalBox = el.originalBoundingBox
                    var finalLeftMargin = originalBox.left
                    var finalTopMargin = originalBox.top

                    if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
                        finalTopMargin -= statusBarHeight
                    } else {
                        finalLeftMargin -= statusBarHeight
                    }

                    leftMargin = max(0, finalLeftMargin)
                    topMargin = max(0, finalTopMargin)
                }
                // areTranslationsTemporarilyHidden 플래그에 따라 alpha 값 설정 (handleSingleTap에서 false로 설정됨)
                alpha = if (areTranslationsTemporarilyHidden) 0.0f else 1.0f
            }.also {
                container.addView(it)
                translatedTextViews.add(it)
            }
        }
        translationOverlayView.visibility = View.VISIBLE // 번역 내용 표시
    }


    private fun updateAllTranslatedTextViewsStyle() {
        if (translationOverlayView.visibility == View.GONE || translatedTextViews.isEmpty()) return
        for (tv in translatedTextViews) {
            tv.textSize = currentTextStyle.fontSize ?: TranslationTextStyle().fontSize ?: 16f
            try { tv.setTextColor(Color.parseColor(currentTextStyle.textColor)) }
            catch (e: IllegalArgumentException) { tv.setTextColor(Color.WHITE) }
            try {
                val bgColor = Color.parseColor(currentTextStyle.backgroundColor)
                tv.setBackgroundColor(Color.argb(currentTextStyle.backgroundAlpha, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)))
            } catch (e: IllegalArgumentException) {
                tv.setBackgroundColor(Color.argb(currentTextStyle.backgroundAlpha, 0, 0, 0))
            }
            tv.setLineSpacing(currentTextStyle.lineSpacingExtra ?: TranslationTextStyle().lineSpacingExtra ?: 4f, 1.0f)
            tv.gravity = currentTextStyle.textAlignment
            // 스타일 업데이트 시에도 areTranslationsTemporarilyHidden 상태를 반영
            tv.alpha = if (areTranslationsTemporarilyHidden) 0.0f else 1.0f
        }
    }

    private fun clearTranslationOverlay() {
        translationOverlayBinding.translationOverlayContainer?.removeAllViews()
        translatedTextViews.clear()
        translationOverlayView.visibility = View.GONE // 번역 오버레이 숨김
        Log.d(TAG, "Translation overlay cleared and hidden.")
    }

    private fun handleDoubleTap() {
        if (translationOverlayView.visibility == View.VISIBLE && translatedTextViews.isNotEmpty()) {
            areTranslationsTemporarilyHidden = !areTranslationsTemporarilyHidden
            val newAlpha = if (areTranslationsTemporarilyHidden) 0.0f else 1.0f
            for (tv in translatedTextViews) {
                tv.alpha = newAlpha
            }
            Log.d(TAG, "Double tap: translations visibility toggled to ${if(areTranslationsTemporarilyHidden) "hidden" else "visible"}")
        } else {
            Log.d(TAG, "Double tap: No translations to toggle or overlay not visible.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        Log.d(TAG, "OverlayService onStartCommand, action: ${intent?.action}")

        if (intent?.action == "STOP_SERVICE_ACTION") {
            Log.d(TAG, "STOP_SERVICE_ACTION received, stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.hasExtra("media_projection_result_code") == true) {
            Log.d(TAG, "Media projection result received.")
            val resultCode = intent.getIntExtra("media_projection_result_code", Activity.RESULT_CANCELED)
            val data: Intent? = intent.getParcelableExtra("media_projection_data")

            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "Media projection permission granted.")
                if (!isForegroundServiceSuccessfullyStartedWithType) {
                    notification?.let { notif ->
                        try {
                            Log.d(TAG, "Attempting to start foreground service with media projection type.")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                            } else {
                                startForeground(NOTIFICATION_ID, notif)
                            }
                            isForegroundServiceSuccessfullyStartedWithType = true
                            Log.d(TAG, "Foreground service started successfully with media projection type.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting foreground service with media projection type", e)
                            Toast.makeText(this, getString(R.string.error_foreground_service_start_failed) + ": " + e.message, Toast.LENGTH_LONG).show()
                            stopSelf()
                            return START_NOT_STICKY
                        }
                    } ?: run {
                        Log.e(TAG, "Notification object was null, cannot start foreground service.")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }

                if (isForegroundServiceSuccessfullyStartedWithType) {
                    if (!screenCaptureManager.handleMediaProjectionResult(resultCode, data)) {
                        Log.e(TAG, "Failed to setup media projection with ScreenCaptureManager.")
                        Toast.makeText(this, R.string.error_media_projection_setup_failed, Toast.LENGTH_LONG).show()
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    Log.d(TAG, "Media projection result handled by ScreenCaptureManager.")
                } else {
                    Log.w(TAG, "Foreground service was not ready when media projection result was being handled.")
                    Toast.makeText(this, R.string.error_service_not_ready_for_projection, Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
            } else {
                Log.w(TAG, "Media projection permission denied or data is null.")
                Toast.makeText(this, R.string.error_media_projection_permission_denied, Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = OverlayBinder()
    inner class OverlayBinder : Binder() { fun getService(): OverlayService = this@OverlayService }

    private fun getCurrentDisplayMetrics(): DisplayMetrics {
        val displayMetrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            displayMetrics.widthPixels = windowMetrics.bounds.width()
            displayMetrics.heightPixels = windowMetrics.bounds.height()
            displayMetrics.densityDpi = resources.configuration.densityDpi
            displayMetrics.density = resources.displayMetrics.density
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
        }
        return displayMetrics
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged: New orientation: ${newConfig.orientation}. Screen: w=${newConfig.screenWidthDp}dp, h=${newConfig.screenHeightDp}dp")

        if (!::overlayButtonView.isInitialized || !overlayButtonView.isAttachedToWindow) {
            Log.w(TAG, "onConfigurationChanged: Overlay button not initialized or not attached. Skipping update.")
            return
        }

        val newScreenMetrics = getCurrentDisplayMetrics()
        val newScreenWidth = newScreenMetrics.widthPixels
        val newScreenHeight = newScreenMetrics.heightPixels
        Log.d(TAG, "onConfigurationChanged: New screen dimensions (px): ${newScreenWidth}x${newScreenHeight}")

        val screenDpi: Int = newScreenMetrics.densityDpi
        if (::screenCaptureManager.isInitialized) {
            screenCaptureManager.updateScreenParameters(newScreenWidth, newScreenHeight, screenDpi)
        }

        val refX = currentButtonSettings.lastX
        val refY = currentButtonSettings.lastY
        val refWidth = currentButtonSettings.referenceScreenWidth
        val refHeight = currentButtonSettings.referenceScreenHeight

        var newButtonX = overlayButtonParams.x
        var newButtonY = overlayButtonParams.y

        if (refWidth > 0 && refHeight > 0 && newScreenWidth > 0 && newScreenHeight > 0) {
            val xRatio = refX.toFloat() / refWidth.toFloat()
            val yRatio = refY.toFloat() / refHeight.toFloat()
            newButtonX = (xRatio * newScreenWidth).toInt()
            newButtonY = (yRatio * newScreenHeight).toInt()
        } else {
            Log.w(TAG, "Reference dimensions for button not valid. Using current absolute X/Y for clamping: $newButtonX, $newButtonY.")
        }

        updateOverlayButtonSizeAndPosition(newButtonX, newButtonY)

        settingsRepository.saveOverlayButtonPosition(
            overlayButtonParams.x,
            overlayButtonParams.y,
            newScreenWidth,
            newScreenHeight
        )

        if (translationOverlayView.visibility == View.VISIBLE && translatedTextViews.isNotEmpty()) {
            Log.d(TAG, "Configuration changed, clearing existing translations.")
            clearTranslationOverlay()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OverlayService onDestroy")
        isRunning = false
        try {
            if (::overlayButtonView.isInitialized && overlayButtonView.isAttachedToWindow) windowManager.removeView(overlayButtonView)
            if (::translationOverlayView.isInitialized && translationOverlayView.isAttachedToWindow) windowManager.removeView(translationOverlayView)
        } catch (e: Exception) { Log.e(TAG, "Error removing overlay views during onDestroy", e) }
        serviceJob.cancel()
        handler.removeCallbacksAndMessages(null)
        if (::screenCaptureManager.isInitialized) screenCaptureManager.stopCapture()
        isForegroundServiceSuccessfullyStartedWithType = false
        Log.d(TAG, "OverlayService fully destroyed and cleaned up.")
    }
}
