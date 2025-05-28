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
import android.view.Surface
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
// import kotlinx.coroutines.async // Not used directly in this file after changes
// import kotlinx.coroutines.awaitAll // Not used directly in this file after changes
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections // For synchronizedMap if needed
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

        // Default values for dynamic settings, actual values will be loaded from GeneralSettings
        private const val DEFAULT_SIMILARITY_TOLERANCE_CONST = 2
        private const val DEFAULT_MAX_CACHE_SIZE_CONST = 200
        private const val SIMILARITY_SEARCH_RECENT_ITEMS = 10
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

    private var initialButtonX = 0; private var initialButtonY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f
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

    // LRU Cache
    // Consider making this thread-safe if accessed by multiple threads concurrently for modification.
    // For now, modifications are happening within processAndTranslateOcrBlocks, which is a suspend function.
    private val translationCache by lazy {
        val initialCacheCapacity = currentGeneralSettings.maxCacheSize.coerceAtLeast(16) // Initial capacity for LinkedHashMap
        object : LinkedHashMap<String, TranslatedTextElement>(initialCacheCapacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranslatedTextElement>?): Boolean {
                val currentMaxCache = if (::currentGeneralSettings.isInitialized) {
                    currentGeneralSettings.maxCacheSize
                } else {
                    DEFAULT_MAX_CACHE_SIZE_CONST
                }
                val shouldRemove = size > currentMaxCache.coerceAtLeast(1) // Ensure cache size is at least 1
                if (shouldRemove) {
                    Log.d(TAG, "Cache dynamic limit ($currentMaxCache) reached, removing eldest: ${eldest?.key?.take(30)}...")
                }
                return shouldRemove
            }
        }
        // For thread safety if needed:
        // Collections.synchronizedMap(object : LinkedHashMap<String, TranslatedTextElement>... )
    }


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")
        isRunning = true
        // Load settings first as other initializations might depend on them
        loadAllSettings() // This loads currentGeneralSettings
        screenCaptureManager.initialize()
        initializeOverlayButton()
        initializeTranslationOverlay()
        setupGestureDetector()
        observeSettingsChanges()
        createNotificationChannel()
        notification = createNotification()
        Log.d(TAG, "Notification object created. Initial cache capacity based on settings: ${currentGeneralSettings.maxCacheSize}")
    }

    private fun loadAllSettings() {
        currentTextStyle = settingsRepository.getTranslationTextStyle()
        currentButtonSettings = settingsRepository.getOverlayButtonSettings()
        currentGeneralSettings = settingsRepository.getGeneralSettings() // Crucial for cache initialization
        Log.d(TAG, "Initial settings loaded. GeneralSettings: $currentGeneralSettings")
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
                if (oldSettings.size != settings.size && ::overlayButtonView.isInitialized && overlayButtonView.isAttachedToWindow) {
                    updateOverlayButtonSizeAndPosition(overlayButtonParams.x, overlayButtonParams.y)
                }
            }
        }
        mainScope.launch {
            settingsRepository.generalSettingsFlow.collectLatest { settings ->
                val oldGeneralSettings = currentGeneralSettings
                currentGeneralSettings = settings
                Log.d(TAG, "GeneralSettings updated: $settings")
                // If cache size changed, the removeEldestEntry will handle it.
                // If similarity tolerance changed, it will be used in the next check.
                if (oldGeneralSettings.maxCacheSize != settings.maxCacheSize) {
                    Log.d(TAG, "Max cache size changed from ${oldGeneralSettings.maxCacheSize} to ${settings.maxCacheSize}. Cache will adjust.")
                }
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

        val stopSelfIntent = Intent(this, OverlayService::class.java).apply {
            action = "STOP_SERVICE_ACTION"
        }
        val stopSelfPendingIntent = PendingIntent.getService(this, 0, stopSelfIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_close_24, getString(R.string.action_stop_service), stopSelfPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
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
            var initialXPos = currentButtonSettings.lastX
            var initialYPos = currentButtonSettings.lastY
            val refWidth = currentButtonSettings.referenceScreenWidth
            val refHeight = currentButtonSettings.referenceScreenHeight
            if (refWidth > 0 && refHeight > 0) {
                val currentDisplayMetrics = getCurrentDisplayMetrics()
                if (currentDisplayMetrics.widthPixels > 0 && currentDisplayMetrics.heightPixels > 0) {
                    initialXPos = (initialXPos.toFloat() / refWidth * currentDisplayMetrics.widthPixels).toInt()
                    initialYPos = (initialYPos.toFloat() / refHeight * currentDisplayMetrics.heightPixels).toInt()
                }
            }
            x = initialXPos; y = initialYPos
        }
        updateOverlayButtonSize()

        val currentScreenMetrics = getCurrentDisplayMetrics()
        if (overlayButtonParams.width > 0 && overlayButtonParams.height > 0) {
            overlayButtonParams.x = max(0, min(overlayButtonParams.x, currentScreenMetrics.widthPixels - overlayButtonParams.width))
            overlayButtonParams.y = max(0, min(overlayButtonParams.y, currentScreenMetrics.heightPixels - overlayButtonParams.height))
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
                        catch (e: Exception) { Log.w(TAG, "Error updating button view layout during move", e) }
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

        overlayButtonParams.x = newX ?: overlayButtonParams.x
        overlayButtonParams.y = newY ?: overlayButtonParams.y

        val currentScreenMetrics = getCurrentDisplayMetrics()
        if (overlayButtonParams.width > 0 && overlayButtonParams.height > 0) {
            overlayButtonParams.x = max(0, min(overlayButtonParams.x, currentScreenMetrics.widthPixels - overlayButtonParams.width))
            overlayButtonParams.y = max(0, min(overlayButtonParams.y, currentScreenMetrics.heightPixels - overlayButtonParams.height))
        }
        try { windowManager.updateViewLayout(overlayButtonView, overlayButtonParams) }
        catch (e: Exception) { Log.w(TAG, "Error updating overlay button size/position", e) }
    }


    private fun updateOverlayButtonSize() {
        if (!::overlayButtonBinding.isInitialized) return
        val sizeInPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, currentButtonSettings.size.toFloat(), resources.displayMetrics).toInt()
        overlayButtonBinding.overlayButton.layoutParams.apply { width = sizeInPx; height = sizeInPx }
        overlayButtonBinding.overlayButton.requestLayout()

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
                Log.d(TAG, "onLongPress - Start moving button")
                isMoving = true
            }
        })
    }

    private fun handleSingleTap() {
        if (!isForegroundServiceSuccessfullyStartedWithType) { Toast.makeText(this, getString(R.string.error_service_not_ready_media_projection), Toast.LENGTH_LONG).show(); return }
        if (!screenCaptureManager.isMediaProjectionReady()) { Toast.makeText(this, getString(R.string.error_media_projection_not_ready), Toast.LENGTH_LONG).show(); sendBroadcast(Intent(ACTION_REQUEST_MEDIA_PROJECTION)); return }

        areTranslationsTemporarilyHidden = false
        overlayButtonView.visibility = View.GONE
        clearTranslationOverlay()
        Log.d(TAG, "Overlays hidden for new capture. Translations will be shown.")

        handler.postDelayed({
            if (screenCaptureManager.isCapturing()) {
                Log.w(TAG, "Capture requested but already capturing. Aborting new capture.")
                if (overlayButtonView.visibility == View.GONE) overlayButtonView.visibility = View.VISIBLE
                return@postDelayed
            }
            Log.d(TAG, "Delay ended (${currentGeneralSettings.captureDelayMs}ms). Performing screen capture.")
            performScreenCapture()
        }, currentGeneralSettings.captureDelayMs.toLong().coerceAtLeast(0)) // Ensure delay is not negative
    }

    private fun performScreenCapture() {
        screenCaptureManager.captureScreen { bitmap ->
            mainScope.launch {
                try {
                    if (bitmap != null) {
                        Log.d(TAG, "Screen capture successful. Bitmap received.")
                        processCapturedImage(bitmap)
                    } else {
                        Log.e(TAG, "Screen capture failed, bitmap is null.")
                        Toast.makeText(this@OverlayService, R.string.error_capture_failed, Toast.LENGTH_SHORT).show()
                        if (overlayButtonView.visibility == View.GONE) overlayButtonView.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in performScreenCapture's mainScope.launch", e)
                    Toast.makeText(this@OverlayService, "${getString(R.string.error_processing_capture)}: ${e.message}", Toast.LENGTH_SHORT).show()
                    if (overlayButtonView.visibility == View.GONE) overlayButtonView.visibility = View.VISIBLE
                    if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                }
            }
        }
    }

    private suspend fun processCapturedImage(bitmap: Bitmap) {
        var bitmapHandled = false
        try {
            val ocrSourceLanguage = if (currentGeneralSettings.autoDetectSourceLanguage) null else currentGeneralSettings.defaultSourceLanguage
            val ocrResult = ocrManager.recognizeText(bitmap, ocrSourceLanguage)

            ocrResult.fold(
                onSuccess = { staticallyFilteredOcrBlocks ->
                    if (staticallyFilteredOcrBlocks.isEmpty()) {
                        Log.d(TAG, "OCR (static filtered) found no text blocks.")
                        withContext(Dispatchers.Main) { clearTranslationOverlay() }
                        // Bitmap will be handled in finally block if not already
                    } else {
                        Log.d(TAG, "OCR (static filtered) successful, ${staticallyFilteredOcrBlocks.size} blocks. Starting dynamic filtering & translation.")
                        processAndTranslateOcrBlocks(staticallyFilteredOcrBlocks) // Pass bitmap to be handled by this function
                        // processAndTranslateOcrBlocks will handle the bitmap
                        bitmapHandled = true // Mark as handled as it's passed down
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "OCR (static filtered) failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@OverlayService, R.string.error_ocr_failed, Toast.LENGTH_SHORT).show()
                        clearTranslationOverlay()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception in processCapturedImage", e) // Catch exceptions from ocrManager or fold
            withContext(Dispatchers.Main) {
                Toast.makeText(this@OverlayService, "${getString(R.string.error_processing_capture)} (Outer): ${e.message}", Toast.LENGTH_SHORT).show()
                clearTranslationOverlay()
            }
        } finally {
            if (!bitmapHandled && !bitmap.isRecycled) {
                Log.w(TAG, "Bitmap was not handled in processCapturedImage, recycling in finally.")
                bitmap.recycle()
            }
            withContext(Dispatchers.Main) {
                if (overlayButtonView.visibility == View.GONE) {
                    overlayButtonView.visibility = View.VISIBLE
                }
            }
        }
    }


    private suspend fun processAndTranslateOcrBlocks(
        staticallyFilteredBlocks: List<OcrTextBlock>
        // originalBitmapForRecycle: Bitmap // No longer needed here, handled by caller's finally
    ) {
        val finalTranslatedElements = mutableListOf<TranslatedTextElement>()
        val blocksToTranslateApi = mutableListOf<OcrTextBlock>()
        // val blockIndexMap = mutableMapOf<OcrTextBlock, Int>() // Not strictly needed if order is maintained

        staticallyFilteredBlocks.forEach { block -> // Removed index as it's not used for now
            val originalTextKey = block.text
            var foundInCache = false

            // 1. Exact cache hit
            translationCache[originalTextKey]?.let { cachedElement -> // 'get' updates access order
                Log.d(TAG, "Exact cache hit for: \"${originalTextKey.take(30)}...\"")
                finalTranslatedElements.add(cachedElement.copy(originalBoundingBox = block.boundingBox))
                foundInCache = true
            }

            // 2. Similar cache hit (if not exact match)
            if (!foundInCache) {
                // Iterate a limited number of recent items for similarity check.
                // A more robust way for "recent" would be a separate list or a custom LRU that exposes recency.
                // For simplicity, we iterate a portion of the cache. This is not strictly LRU order for similarity.
                val cacheEntries = synchronized(translationCache) { translationCache.entries.toList() } // Snapshot for thread safety
                val recentEntries = cacheEntries.takeLast(SIMILARITY_SEARCH_RECENT_ITEMS)

                for (entry in recentEntries.reversed()) { // Check most recent of this subset first
                    val cachedKey = entry.key
                    val cachedElement = entry.value // No need to call get() again on translationCache here

                    val langHintForSimilarity = if (currentGeneralSettings.autoDetectSourceLanguage) block.languageCode else currentGeneralSettings.defaultSourceLanguage
                    val tolerance = currentGeneralSettings.similarityTolerance.coerceAtLeast(0)

                    if (ocrManager.areTextsSimilar(originalTextKey, cachedKey, langHintForSimilarity, tolerance)) {
                        Log.d(TAG, "Similar cache hit: \"${originalTextKey.take(30)}...\" similar to \"${cachedKey.take(30)}...\"")
                        // Important: Update access order for the actual found key in the main cache
                        translationCache[cachedKey] // Access the original cache to update LRU order
                        finalTranslatedElements.add(cachedElement.copy(originalBoundingBox = block.boundingBox))
                        foundInCache = true
                        break
                    }
                }
            }

            if (!foundInCache) {
                blocksToTranslateApi.add(block)
                // blockIndexMap[block] = index // If reordering is complex later
            }
        }
        Log.d(TAG, "Cache lookup complete. Found in cache (exact or similar): ${finalTranslatedElements.size}. To translate via API: ${blocksToTranslateApi.size}")

        if (blocksToTranslateApi.isNotEmpty()) {
            if (currentGeneralSettings.geminiApiKey.isBlank()) {
                withContext(Dispatchers.Main) { Toast.makeText(this@OverlayService, R.string.error_api_key_not_set, Toast.LENGTH_LONG).show() }
            } else {
                val singleLineTextsForBatch = blocksToTranslateApi.map { it.text.replace('\n', ' ').trim() }
                val combinedTextForBatching = singleLineTextsForBatch.joinToString(REQUEST_BATCH_SEPARATOR)
                val numSegmentsSent = singleLineTextsForBatch.size
                val targetLang = currentGeneralSettings.targetLanguage
                val sourceLangForBatch = if (currentGeneralSettings.autoDetectSourceLanguage) null else currentGeneralSettings.defaultSourceLanguage

                Log.d(TAG, "Attempting API batch translation for ${blocksToTranslateApi.size} blocks.")
                val batchTranslationResult = translationManager.translateText(
                    combinedTextForBatching, sourceLangForBatch, targetLang, isBatchedRequest = true
                )

                batchTranslationResult.fold(
                    onSuccess = { batchedTranslatedString ->
                        val translatedApiSegments = batchedTranslatedString.split(RESPONSE_SPLIT_REGEX).map { it.trim() }.filter { it.isNotBlank() }
                        Log.d(TAG, "API Batch translation success. Sent $numSegmentsSent, Got ${translatedApiSegments.size} segments.")

                        if (translatedApiSegments.size == numSegmentsSent) {
                            blocksToTranslateApi.forEachIndexed { i, originalBlock ->
                                val translatedText = translatedApiSegments[i]
                                val actualSourceLang = determineActualSourceLanguage(originalBlock, currentGeneralSettings)
                                val newElement = TranslatedTextElement(
                                    originalText = originalBlock.text,
                                    translatedText = translatedText,
                                    originalBoundingBox = originalBlock.boundingBox,
                                    sourceLanguage = actualSourceLang,
                                    targetLanguage = targetLang
                                )
                                finalTranslatedElements.add(newElement) // Add to list for display
                                translationCache[originalBlock.text] = newElement // Store in cache
                            }
                        } else {
                            Log.w(TAG, "Batch translation segment count mismatch. Sent: $numSegmentsSent, Got: ${translatedApiSegments.size}. Some translations might be missing or incorrect.")
                            // Handle mismatch: could try to map what we have, or show error, or fallback to individual.
                            // For now, we'll try to map as many as possible if API gives fewer, or use only the first N if API gives more.
                            val commonCount = min(translatedApiSegments.size, blocksToTranslateApi.size)
                            for (i in 0 until commonCount) {
                                val originalBlock = blocksToTranslateApi[i]
                                val translatedText = translatedApiSegments[i]
                                val actualSourceLang = determineActualSourceLanguage(originalBlock, currentGeneralSettings)
                                val newElement = TranslatedTextElement(originalBlock.text, translatedText, originalBlock.boundingBox, actualSourceLang, targetLang)
                                finalTranslatedElements.add(newElement)
                                translationCache[originalBlock.text] = newElement
                            }
                        }
                    },
                    onFailure = { e ->
                        Log.e(TAG, "API Batch translation failed.", e)
                        val toastMessage = if (e is ApiKeyNotSetException) R.string.error_api_key_not_set else R.string.error_translation_failed_all
                        withContext(Dispatchers.Main) { Toast.makeText(this@OverlayService, toastMessage, Toast.LENGTH_LONG).show() }
                    }
                )
            }
        }

        // Ensure elements are displayed on the main thread
        withContext(Dispatchers.Main) {
            if (finalTranslatedElements.isNotEmpty()) {
                displayTranslatedTexts(finalTranslatedElements)
            } else {
                Log.d(TAG, "No text to display after dynamic filtering and translation.")
                clearTranslationOverlay()
            }
        }
    }


    private fun determineActualSourceLanguage(block: OcrTextBlock, settings: GeneralSettings): String {
        return if (settings.autoDetectSourceLanguage) {
            block.languageCode?.takeIf { it.isNotBlank() && it != "und" } ?: settings.defaultSourceLanguage
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
        clearTranslationOverlay() // Clear previous before drawing new
        val container = translationOverlayBinding.translationOverlayContainer ?: return

        val currentOrientation = resources.configuration.orientation
        val statusBarHeight = getStatusBarHeight()

        // FIX: Use windowManager to get display rotation
        @Suppress("DEPRECATION")
        val displayRotation = windowManager.defaultDisplay.rotation // Consistently use windowManager

        Log.d(TAG, "Displaying ${translatedElements.size} translated elements. Orientation: $currentOrientation, Rotation: $displayRotation, SBH: $statusBarHeight. Hidden: $areTranslationsTemporarilyHidden")

        for (el in translatedElements) {
            TextView(this).apply {
                text = el.translatedText
                textSize = currentTextStyle.fontSize ?: TranslationTextStyle().fontSize ?: 16f
                try { setTextColor(Color.parseColor(currentTextStyle.textColor)) }
                catch (e: IllegalArgumentException) { setTextColor(Color.WHITE); Log.w(TAG, "Invalid text color: ${currentTextStyle.textColor}", e) }
                try {
                    val bg = Color.parseColor(currentTextStyle.backgroundColor)
                    setBackgroundColor(Color.argb(currentTextStyle.backgroundAlpha, Color.red(bg), Color.green(bg), Color.blue(bg)))
                } catch (e: IllegalArgumentException) {
                    setBackgroundColor(Color.argb(currentTextStyle.backgroundAlpha, 0, 0, 0)); Log.w(TAG, "Invalid background color: ${currentTextStyle.backgroundColor}", e)
                }
                setLineSpacing(currentTextStyle.lineSpacingExtra ?: TranslationTextStyle().lineSpacingExtra ?: 4f, 1.0f)
                gravity = currentTextStyle.textAlignment
                setPadding(10, 0, 10, 5)

                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    val originalBox = el.originalBoundingBox
                    var finalLeftMargin = originalBox.left
                    var finalTopMargin = originalBox.top

                    if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
                        finalTopMargin -= statusBarHeight
                    } else if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                        if (displayRotation == Surface.ROTATION_90) {
                            finalLeftMargin -= statusBarHeight
                        }
                        // ROTATION_270 (camera right landscape) typically doesn't need status bar adjustment for left margin
                    }
                    leftMargin = max(0, finalLeftMargin)
                    topMargin = max(0, finalTopMargin)
                }
                alpha = if (areTranslationsTemporarilyHidden) 0.0f else 1.0f
            }.also {
                container.addView(it)
                translatedTextViews.add(it)
            }
        }
        translationOverlayView.visibility = View.VISIBLE
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
            tv.alpha = if (areTranslationsTemporarilyHidden) 0.0f else 1.0f
        }
    }

    private fun clearTranslationOverlay() {
        translationOverlayBinding.translationOverlayContainer?.removeAllViews()
        translatedTextViews.clear()
        translationOverlayView.visibility = View.GONE
        Log.d(TAG, "Translation overlay cleared and hidden.")
    }

    private fun handleDoubleTap() {
        if (translationOverlayView.visibility == View.VISIBLE && translatedTextViews.isNotEmpty()) {
            areTranslationsTemporarilyHidden = !areTranslationsTemporarilyHidden
            val newAlpha = if (areTranslationsTemporarilyHidden) 0.0f else 1.0f
            for (tv in translatedTextViews) { tv.alpha = newAlpha }
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
                            stopSelf(); return START_NOT_STICKY
                        }
                    } ?: run {
                        Log.e(TAG, "Notification object was null, cannot start foreground service.")
                        stopSelf(); return START_NOT_STICKY
                    }
                }

                if (isForegroundServiceSuccessfullyStartedWithType) {
                    if (!screenCaptureManager.handleMediaProjectionResult(resultCode, data)) {
                        Log.e(TAG, "Failed to setup media projection with ScreenCaptureManager.")
                        Toast.makeText(this, R.string.error_media_projection_setup_failed, Toast.LENGTH_LONG).show()
                        stopSelf(); return START_NOT_STICKY
                    }
                    Log.d(TAG, "Media projection result handled by ScreenCaptureManager.")
                } else {
                    Log.w(TAG, "Foreground service was not ready when media projection result was being handled.")
                    Toast.makeText(this, R.string.error_service_not_ready_for_projection, Toast.LENGTH_LONG).show()
                    stopSelf(); return START_NOT_STICKY
                }
            } else {
                Log.w(TAG, "Media projection permission denied or data is null.")
                Toast.makeText(this, R.string.error_media_projection_permission_denied, Toast.LENGTH_LONG).show()
                stopSelf(); return START_NOT_STICKY
            }
        } else if (!isForegroundServiceSuccessfullyStartedWithType && notification != null) {
            Log.d(TAG, "Starting foreground service (general type or media projection type if Q+ and permission already granted).")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // For Q+, if media projection permission is expected later, startForeground with type
                    // is typically done *after* permission is granted.
                    // If starting before permission, a general type might be safer, or no type.
                    // However, the current logic correctly starts with type *after* getting result code.
                    // This path is for when service starts without media projection intent (e.g. app launch).
                    startForeground(NOTIFICATION_ID, notification!!) // General start
                } else {
                    startForeground(NOTIFICATION_ID, notification!!)
                }
                // isForegroundServiceSuccessfullyStartedWithType is set true only after media projection is confirmed.
            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground service (general)", e)
                Toast.makeText(this, getString(R.string.error_foreground_service_start_failed) + ": " + e.message, Toast.LENGTH_LONG).show()
                stopSelf(); return START_NOT_STICKY
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

        if (::screenCaptureManager.isInitialized) {
            screenCaptureManager.updateScreenParameters(newScreenWidth, newScreenHeight, newScreenMetrics.densityDpi)
        }

        val refX = currentButtonSettings.lastX
        val refY = currentButtonSettings.lastY
        val refWidth = currentButtonSettings.referenceScreenWidth
        val refHeight = currentButtonSettings.referenceScreenHeight
        var newButtonX = overlayButtonParams.x
        var newButtonY = overlayButtonParams.y

        if (refWidth > 0 && refHeight > 0 && newScreenWidth > 0 && newScreenHeight > 0) {
            newButtonX = (refX.toFloat() / refWidth * newScreenWidth).toInt()
            newButtonY = (refY.toFloat() / refHeight * newScreenHeight).toInt()
        }
        updateOverlayButtonSizeAndPosition(newButtonX, newButtonY)

        settingsRepository.saveOverlayButtonPosition(
            overlayButtonParams.x,
            overlayButtonParams.y,
            newScreenWidth,
            newScreenHeight
        )

        if (translationOverlayView.visibility == View.VISIBLE && translatedTextViews.isNotEmpty()) {
            Log.d(TAG, "Configuration changed, clearing existing translations to re-evaluate positions.")
            clearTranslationOverlay()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OverlayService onDestroy. Cache size: ${translationCache.size}")
        isRunning = false
        try {
            if (::overlayButtonView.isInitialized && overlayButtonView.isAttachedToWindow) windowManager.removeView(overlayButtonView)
            if (::translationOverlayView.isInitialized && translationOverlayView.isAttachedToWindow) windowManager.removeView(translationOverlayView)
        } catch (e: Exception) { Log.w(TAG, "Error removing overlay views during onDestroy", e) }

        serviceJob.cancel()
        handler.removeCallbacksAndMessages(null)
        if (::screenCaptureManager.isInitialized) screenCaptureManager.stopCapture()

        synchronized(translationCache) { // Synchronize access if clearing from a different thread context potentially
            translationCache.clear()
        }
        isForegroundServiceSuccessfullyStartedWithType = false
        Log.d(TAG, "OverlayService fully destroyed and cleaned up.")
    }
}
