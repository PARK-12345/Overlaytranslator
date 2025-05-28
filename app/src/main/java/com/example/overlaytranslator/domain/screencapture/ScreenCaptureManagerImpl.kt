package com.example.overlaytranslator.domain.screencapture

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenCaptureManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaProjectionManager: MediaProjectionManager
) : ScreenCaptureManager {

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val VIRTUAL_DISPLAY_NAME = "ScreenCaptureVD"
        private const val CAPTURE_TIMEOUT_MS = 2500L // 캡처 타임아웃 시간 (5초)
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var windowManager: WindowManager? = null
    private var displayMetrics: DisplayMetrics = DisplayMetrics()

    private var isInitialized = false
    @Volatile
    private var isCurrentlyCapturingImage = false

    private var captureHandlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun initialize() {
        if (isInitialized) {
            // Log.d(TAG, "Already initialized.") // 너무 빈번할 수 있어 주석 처리
            return
        }
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateDisplayMetrics()

        captureHandlerThread = HandlerThread("ScreenCaptureThread").apply {
            start()
            captureHandler = Handler(looper)
        }
        Log.d(TAG, "ScreenCaptureManager initialized. Capture HandlerThread started.")
        isInitialized = true
    }

    private fun updateDisplayMetrics() {
        val wm = windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { windowManager = it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val currentMetrics = wm.currentWindowMetrics
            displayMetrics.widthPixels = currentMetrics.bounds.width()
            displayMetrics.heightPixels = currentMetrics.bounds.height()
            displayMetrics.densityDpi = context.resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealMetrics(displayMetrics)
        }
        displayMetrics.density = displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT

        if (displayMetrics.widthPixels == 0 || displayMetrics.heightPixels == 0) {
            Log.e(TAG, "Failed to get valid display metrics. Using resource metrics as fallback.")
            context.resources.displayMetrics.let {
                displayMetrics.widthPixels = it.widthPixels
                displayMetrics.heightPixels = it.heightPixels
                displayMetrics.density = it.density
                displayMetrics.densityDpi = it.densityDpi
            }
        }
        Log.d(TAG, "DisplayMetrics updated: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels} DPI ${displayMetrics.densityDpi}")
    }

    override fun requestScreenCapturePermission(activity: Activity, requestCode: Int) {
        if (!isInitialized) initialize()
        try {
            activity.startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), requestCode)
            Log.d(TAG, "MediaProjection permission request sent.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting screen capture permission intent", e)
            // Consider notifying the user or service
        }
    }

    override fun handleMediaProjectionResult(resultCode: Int, data: Intent?): Boolean {
        Log.d(TAG, "handleMediaProjectionResult. ResultCode: $resultCode, Data: ${data != null}")
        if (!isInitialized) initialize()

        if (resultCode == Activity.RESULT_OK && data != null) {
            releaseMediaProjection() // 이전 프로젝션과 관련 리소스 정리

            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                Log.e(TAG, "Failed to get MediaProjection from result data.")
                return false
            }
            Log.i(TAG, "New MediaProjection obtained: ${mediaProjection.hashCode()}")

            mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped (Callback). MP Hash: ${mediaProjection?.hashCode()}")
                    mainHandler.post {
                        releaseVirtualDisplayAndReader() // VD와 IR은 MP에 의존
                        mediaProjection = null
                        mediaProjectionCallback = null
                    }
                }
            }
            mediaProjection?.registerCallback(mediaProjectionCallback!!, mainHandler)

            // 새 MediaProjection 토큰으로 VirtualDisplay 및 ImageReader 설정 시도
            return setupVirtualDisplayAndReader()
        } else {
            Log.w(TAG, "MediaProjection request denied or failed. ResultCode: $resultCode")
            releaseAllCaptureResources() // 모든 것 정리
            return false
        }
    }

    override fun updateScreenParameters(newWidth: Int, newHeight: Int, newDpi: Int) {
        Log.d(TAG, "updateScreenParameters: W=$newWidth, H=$newHeight, DPI=$newDpi")
        if (newWidth <= 0 || newHeight <= 0 || newDpi <= 0) {
            Log.e(TAG, "Invalid screen parameters: $newWidth, $newHeight, $newDpi. Update aborted.")
            return
        }
        if (!isInitialized) {
            Log.w(TAG, "Manager not initialized during updateScreenParameters. Initializing first.")
            initialize()
        }

        val oldWidth = displayMetrics.widthPixels
        val oldHeight = displayMetrics.heightPixels
        // val oldDpi = displayMetrics.densityDpi // DPI 변경은 resize에서 처리

        // displayMetrics 업데이트
        displayMetrics.widthPixels = newWidth
        displayMetrics.heightPixels = newHeight
        displayMetrics.densityDpi = newDpi
        displayMetrics.density = newDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT
        Log.i(TAG, "Updated displayMetrics internally to: ${newWidth}x${newHeight} DPI $newDpi")

        val currentMediaProjection = mediaProjection
        val currentVirtualDisplay = virtualDisplay
        var currentImageReader = imageReader // var로 변경하여 재할당 가능하게

        if (currentMediaProjection != null && currentVirtualDisplay != null && currentImageReader != null) {
            // MediaProjection이 활성 상태이고, 기존 VD와 IR이 존재할 경우
            Log.d(TAG, "Active MediaProjection. Attempting to update existing VirtualDisplay and ImageReader.")

            // 크기가 실제로 변경되었는지 확인 (단순 DPI 변경은 resize만으로 충분할 수 있음)
            if (oldWidth != newWidth || oldHeight != newHeight) {
                Log.d(TAG, "Screen dimensions changed. Recreating ImageReader and resizing VirtualDisplay.")
                try {
                    // 1. 기존 ImageReader 닫기
                    currentImageReader.setOnImageAvailableListener(null, null) // 리스너 먼저 제거
                    currentImageReader.close()

                    // 2. 새 크기로 ImageReader 재생성
                    this.imageReader = ImageReader.newInstance(newWidth, newHeight, PixelFormat.RGBA_8888, 2)
                    currentImageReader = this.imageReader // 참조 업데이트
                    Log.d(TAG, "New ImageReader created: ${currentImageReader.hashCode()} with ${newWidth}x${newHeight}")

                    // 3. 기존 VirtualDisplay 리사이즈
                    currentVirtualDisplay.resize(newWidth, newHeight, newDpi)
                    Log.d(TAG, "Existing VirtualDisplay resized to ${newWidth}x${newHeight} DPI $newDpi.")

                    // 4. VirtualDisplay에 새 Surface 설정 (API 28+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        currentImageReader?.surface?.let { newSurface ->
                            if (newSurface.isValid) {
                                currentVirtualDisplay.surface = newSurface
                                Log.d(TAG, "New ImageReader surface set on existing VirtualDisplay.")
                            } else {
                                Log.e(TAG, "New ImageReader surface is invalid. Capture may fail.")
                                // 이 경우, 더 이상 진행하기 어려움. 서비스 재시작 유도 필요.
                                stopCapture() // 현재 프로젝션 중단
                                // TODO: 서비스에 알려서 MediaProjection 재요청하도록 하는 메커니즘 필요
                            }
                        } ?: Log.e(TAG, "New ImageReader surface is null. Cannot set on VirtualDisplay.")
                    } else {
                        Log.w(TAG, "setSurface for VirtualDisplay not available below API 28. Full MediaProjection restart might be needed for screen changes to take effect reliably.")
                        // 이 경우, resize만으로는 부족할 수 있으며, 오래된 surface로 계속 작동할 수 있음.
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating existing VirtualDisplay/ImageReader components.", e)
                    // 업데이트 실패 시, 현재 프로젝션을 중단하고 사용자가 다시 시작하도록 유도하는 것이 안전할 수 있음.
                    stopCapture() // 현재 프로젝션 중단
                    // TODO: 서비스에 알려서 MediaProjection 재요청하도록 하는 메커니즘 필요
                }
            } else {
                // 크기 변경 없이 DPI만 변경된 경우 (또는 변경 없는 호출)
                // VirtualDisplay.resize()는 DPI도 업데이트하므로, ImageReader 재생성 없이 호출 가능
                try {
                    currentVirtualDisplay.resize(newWidth, newHeight, newDpi)
                    Log.d(TAG, "Only DPI or no dimension change. VirtualDisplay resized for DPI: $newDpi")
                } catch (e: Exception) {
                    Log.e(TAG, "Error resizing VirtualDisplay for DPI update.", e)
                }
            }
        } else if (currentMediaProjection != null && (currentVirtualDisplay == null || currentImageReader == null)) {
            // MediaProjection은 있지만 VD/IR이 없는 경우 (예: 이전 설정 실패)
            // 이 MediaProjection 토큰으로 *처음* VD를 생성하는 것이므로 허용됨.
            Log.d(TAG, "MediaProjection exists, but VD/IR are missing. Attempting initial setup.")
            setupVirtualDisplayAndReader()
        } else {
            Log.w(TAG, "MediaProjection not available during updateScreenParameters. New parameters stored for next setup.")
        }
    }


    @SuppressLint("WrongConstant")
    private fun setupVirtualDisplayAndReader(): Boolean {
        val currentMediaProjection = mediaProjection ?: run {
            Log.e(TAG, "setupVirtualDisplayAndReader: MediaProjection is null.")
            return false
        }
        if (!isInitialized || captureHandler == null) {
            Log.e(TAG, "setupVirtualDisplayAndReader: Manager not initialized or captureHandler is null.")
            return false
        }

        releaseVirtualDisplayAndReader() // 이전 인스턴스 정리

        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        if (width <= 0 || height <= 0) {
            Log.e(TAG, "Invalid dimensions for VirtualDisplay setup: ${width}x${height}. Attempting metrics update.")
            updateDisplayMetrics()
            if (displayMetrics.widthPixels <= 0 || displayMetrics.heightPixels <= 0) {
                Log.e(TAG, "Still invalid dimensions after metrics update. Aborting VD setup.")
                return false
            }
            // Log.w(TAG, "Retrying VD setup with re-fetched dimensions: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
            // 재귀 호출 대신, 업데이트된 displayMetrics를 바로 사용
        }

        Log.d(TAG, "Setting up VirtualDisplay with: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels} DPI $density")
        try {
            imageReader = ImageReader.newInstance(displayMetrics.widthPixels, displayMetrics.heightPixels, PixelFormat.RGBA_8888, 2)
            Log.d(TAG, "ImageReader created: ${imageReader.hashCode()} for MP ${currentMediaProjection.hashCode()}")

            virtualDisplay = currentMediaProjection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                displayMetrics.widthPixels, displayMetrics.heightPixels, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, captureHandler
            )
            if (virtualDisplay == null) {
                Log.e(TAG, "Failed to create VirtualDisplay (returned null).")
                imageReader?.close(); imageReader = null
                return false
            }
            Log.i(TAG, "VirtualDisplay (${virtualDisplay.hashCode()}) and ImageReader created successfully.")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating VirtualDisplay. (API 34+ restriction? Token: ${currentMediaProjection.hashCode()})", e)
            releaseVirtualDisplayAndReader()
            // 이 경우, MediaProjection 토큰이 이미 사용되었거나 유효하지 않을 수 있음.
            // 상위(서비스)에서 MediaProjection을 다시 요청해야 할 수 있음을 알려야 함.
            mediaProjection?.stop() // 현재 MediaProjection을 중지하여 재사용 방지
            mediaProjection = null
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Exception during VirtualDisplay/ImageReader setup", e)
            releaseVirtualDisplayAndReader()
            return false
        }
    }

    override fun captureScreen(callback: (Bitmap?) -> Unit) {
        if (!isMediaProjectionReady()) {
            Log.e(TAG, "captureScreen: Not ready. MP: ${mediaProjection != null}, VD: ${virtualDisplay != null}, IR: ${imageReader != null}, Surface: ${imageReader?.surface?.isValid}")
            // 준비 안된 경우, setup 시도 (주의: setupVirtualDisplayAndReader는 createVirtualDisplay 호출)
            // 이 경로는 handleMediaProjectionResult에서 이미 setup을 시도했어야 함.
            // 여기서 다시 setup을 시도하는 것은 API 34+에서 문제를 일으킬 수 있음.
            // 따라서, isMediaProjectionReady가 false면 즉시 실패 처리하는 것이 더 안전할 수 있음.
            // 또는, OverlayService에서 isMediaProjectionReady를 먼저 확인하고, false면 권한 재요청 유도.
            // 여기서는 일단 실패 콜백.
            callback(null); return
        }

        if (isCurrentlyCapturingImage) {
            Log.w(TAG, "captureScreen: Already capturing. Ignoring request.")
            return
        }
        isCurrentlyCapturingImage = true
        // Log.d(TAG, "Starting screen capture. IR: ${imageReader?.hashCode()}")

        val currentImageReader = imageReader ?: run {
            Log.e(TAG, "captureScreen: ImageReader is null.");
            isCurrentlyCapturingImage = false; callback(null); return
        }
        val currentCaptureHandler = captureHandler ?: run {
            Log.e(TAG, "captureScreen: CaptureHandler is null.");
            isCurrentlyCapturingImage = false; callback(null); return
        }

        try {
            var staleImage: Image?; var clearedCount = 0
            while (currentImageReader.acquireNextImage().also { staleImage = it } != null) {
                staleImage?.close(); clearedCount++
            }
            if (clearedCount > 0) Log.d(TAG, "Cleared $clearedCount stale images.")
        } catch (e: Exception) { Log.w(TAG, "Exception clearing stale images.", e) }

        val timeoutRunnable = Runnable {
            if (isCurrentlyCapturingImage) {
                Log.w(TAG, "Capture timeout (${CAPTURE_TIMEOUT_MS}ms). Resetting.")
                currentImageReader.setOnImageAvailableListener(null, null)
                isCurrentlyCapturingImage = false
                mainHandler.post { callback(null) }
            }
        }
        currentCaptureHandler.postDelayed(timeoutRunnable, CAPTURE_TIMEOUT_MS)

        currentImageReader.setOnImageAvailableListener(object : ImageReader.OnImageAvailableListener {
            private var imageAcquired = false
            override fun onImageAvailable(reader: ImageReader) {
                if (imageAcquired || !isCurrentlyCapturingImage) {
                    try { reader.acquireLatestImage()?.close() } catch (e: Exception) { /* ignore */ }
                    return
                }
                var image: Image? = null; var bitmap: Bitmap? = null
                try {
                    image = reader.acquireLatestImage()
                    if (image != null) {
                        imageAcquired = true
                        currentCaptureHandler.removeCallbacks(timeoutRunnable)
                        // Log.d(TAG, "Image acquired: ${image.width}x${image.height}")

                        val planes = image.planes; val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride; val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * image.width

                        val tempBitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
                        tempBitmap.copyPixelsFromBuffer(buffer)
                        bitmap = if (rowPadding == 0 && tempBitmap.width == image.width) tempBitmap else {
                            Bitmap.createBitmap(tempBitmap, 0, 0, image.width, image.height).also {
                                if (tempBitmap != it && !tempBitmap.isRecycled) tempBitmap.recycle()
                            }
                        }
                        // Log.d(TAG, "Bitmap created.")
                        mainHandler.post { callback(bitmap) }
                    } else { Log.w(TAG, "acquireLatestImage returned null.") }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image in listener", e)
                    currentCaptureHandler.removeCallbacks(timeoutRunnable)
                    bitmap?.recycle()
                    if (isCurrentlyCapturingImage) mainHandler.post { callback(null) }
                } finally {
                    image?.close()
                    if (imageAcquired || !isCurrentlyCapturingImage) {
                        isCurrentlyCapturingImage = false
                        // Log.d(TAG, "Listener logic finished. isCurrentlyCapturingImage = false")
                    }
                }
            }
        }, currentCaptureHandler)
    }

    private fun releaseVirtualDisplayAndReader() {
        // Log.d(TAG, "Releasing VirtualDisplay and ImageReader...")
        try {
            virtualDisplay?.release()
        } catch (e: Exception) { Log.e(TAG, "Error releasing VirtualDisplay", e) }
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (e: Exception) { Log.e(TAG, "Error closing ImageReader", e) }
        imageReader = null
        // Log.d(TAG, "VirtualDisplay and ImageReader released.")
    }

    private fun releaseMediaProjection() {
        // Log.d(TAG, "Releasing MediaProjection...")
        mediaProjectionCallback?.let {
            try { mediaProjection?.unregisterCallback(it) }
            catch (e: Exception) { Log.e(TAG, "Error unregistering MP callback", e) }
            mediaProjectionCallback = null
        }
        try { mediaProjection?.stop() }
        catch (e: Exception) { Log.e(TAG, "Error stopping MediaProjection", e) }
        mediaProjection = null
        // Log.d(TAG, "MediaProjection released.")
    }

    private fun releaseAllCaptureResources() {
        Log.i(TAG, "Releasing all screen capture resources.")
        isCurrentlyCapturingImage = false
        captureHandler?.removeCallbacksAndMessages(null) // 핸들러 작업 취소
        releaseVirtualDisplayAndReader()
        releaseMediaProjection()
    }

    override fun stopCapture() {
        Log.i(TAG, "stopCapture called. Releasing resources and handler thread.")
        releaseAllCaptureResources()
        captureHandlerThread?.quitSafely()
        try {
            captureHandlerThread?.join(500)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted joining capture handler thread", e)
            Thread.currentThread().interrupt()
        }
        captureHandlerThread = null
        captureHandler = null
        isInitialized = false // 다음 사용 시 재초기화
        Log.d(TAG, "Capture HandlerThread stopped and manager reset.")
    }

    override fun isMediaProjectionReady(): Boolean {
        val mpReady = mediaProjection != null
        val vdReady = virtualDisplay != null
        val irReady = imageReader?.surface?.isValid ?: false // surface null 체크 추가
        return mpReady && vdReady && irReady
    }

    override fun isCapturing(): Boolean = isCurrentlyCapturingImage
}
