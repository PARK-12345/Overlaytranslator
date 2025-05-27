package com.example.overlaytranslator.domain.screencapture

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
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
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
        private const val VIRTUAL_DISPLAY_NAME = "ScreenCaptureVirtualDisplay"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var windowManager: WindowManager? = null
    private var displayMetrics: DisplayMetrics = DisplayMetrics() // Non-null, initialized in init

    private var isInitialized = false
    private var isCurrentlyCapturingImage = false
    private val handler = Handler(Looper.getMainLooper())

    // Storing last projection result is not typically needed if MP is managed carefully
    // private var lastResultCode: Int = Activity.RESULT_CANCELED
    // private var lastData: Intent? = null

    private var mediaProjectionCallback: MediaProjection.Callback? = null

    override fun initialize() {
        if (isInitialized) {
            Log.d(TAG, "Already initialized. Screen: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels} DPI: ${displayMetrics.densityDpi}")
            return
        }
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Initialize displayMetrics with current values
        updateDisplayMetrics()

        Log.d(TAG, "ScreenCaptureManager initialized. Screen: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels} DPI: ${displayMetrics.densityDpi}")
        isInitialized = true
    }

    private fun updateDisplayMetrics() {
        val wm = windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { windowManager = it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val currentMetrics = wm.currentWindowMetrics
            displayMetrics.widthPixels = currentMetrics.bounds.width()
            displayMetrics.heightPixels = currentMetrics.bounds.height()
            // For densityDpi, it's better to rely on configuration or resources.displayMetrics
            // as WindowMetrics might not provide it directly in all scenarios or might reflect raw display DPI.
            // Using resources.configuration.densityDpi ensures it matches app's view rendering.
            displayMetrics.densityDpi = context.resources.configuration.densityDpi
            displayMetrics.density = displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealMetrics(displayMetrics) // Use getRealMetrics for more accuracy
        }

        if (displayMetrics.widthPixels == 0 || displayMetrics.heightPixels == 0) {
            Log.e(TAG, "Failed to get valid display metrics from WindowManager. Using resource displayMetrics as fallback.")
            val resourceMetrics = context.resources.displayMetrics
            displayMetrics.widthPixels = resourceMetrics.widthPixels
            displayMetrics.heightPixels = resourceMetrics.heightPixels
            displayMetrics.density = resourceMetrics.density
            displayMetrics.densityDpi = resourceMetrics.densityDpi
        }
    }


    override fun requestScreenCapturePermission(activity: Activity, requestCode: Int) {
        if (!isInitialized) initialize()
        activity.startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), requestCode)
        Log.d(TAG, "MediaProjection permission request sent via Activity.")
    }

    override fun handleMediaProjectionResult(resultCode: Int, data: Intent?): Boolean {
        Log.d(TAG, "handleMediaProjectionResult called. ResultCode: $resultCode, Data: ${data != null}")
        if (!isInitialized) initialize() // Ensure metrics are up-to-date

        if (resultCode == Activity.RESULT_OK && data != null) {
            releaseMediaProjection() // Release any existing projection first

            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                Log.e(TAG, "Failed to get MediaProjection from result data.")
                return false
            }
            Log.i(TAG, "New MediaProjection obtained successfully: ${mediaProjection.hashCode()}")

            mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system or user (Callback). MP: ${mediaProjection.hashCode()}")
                    releaseVirtualDisplayAndReader() // VD and IR depend on MP
                    mediaProjection = null // Nullify after stopping and releasing dependents
                    // No need to unregister callback from itself, but good practice to nullify the reference
                    mediaProjectionCallback = null
                }
            }
            mediaProjection?.registerCallback(mediaProjectionCallback!!, handler)

            return setupVirtualDisplayAndReader()
        } else {
            Log.w(TAG, "MediaProjection request denied or failed. ResultCode: $resultCode")
            releaseAllCaptureResources() // Ensure everything is clean
            return false
        }
    }

    // ✨ 새 메서드 구현 ✨
    override fun updateScreenParameters(newWidth: Int, newHeight: Int, newDpi: Int) {
        Log.d(TAG, "updateScreenParameters called with: Width=$newWidth, Height=$newHeight, DPI=$newDpi")
        if (newWidth == 0 || newHeight == 0 || newDpi == 0) {
            Log.e(TAG, "Invalid screen parameters received: $newWidth, $newHeight, $newDpi. Aborting update.")
            return
        }

        if (!isInitialized) {
            Log.w(TAG, "Manager not initialized during updateScreenParameters. Initializing first.")
            initialize()
        }

        // Update the stored displayMetrics
        displayMetrics.widthPixels = newWidth
        displayMetrics.heightPixels = newHeight
        displayMetrics.densityDpi = newDpi
        displayMetrics.density = newDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT // Recalculate density

        Log.i(TAG, "Updated displayMetrics to: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels} DPI: ${displayMetrics.densityDpi}")

        // If MediaProjection is active, we need to update the VirtualDisplay and ImageReader
        val currentMediaProjection = mediaProjection
        if (currentMediaProjection != null) {
            if (virtualDisplay != null) {
                Log.d(TAG, "MediaProjection and VirtualDisplay exist. Attempting resize and surface update.")
                // Close existing ImageReader before creating a new one
                imageReader?.close()
                try {
                    imageReader = ImageReader.newInstance(newWidth, newHeight, PixelFormat.RGBA_8888, 2)
                    Log.d(TAG, "New ImageReader created for update: ${imageReader.hashCode()} with new dimensions ${newWidth}x${newHeight}")

                    virtualDisplay?.resize(newWidth, newHeight, newDpi)
                    Log.d(TAG, "VirtualDisplay resized to ${newWidth}x${newHeight} DPI ${newDpi}.")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // setSurface is API 28+
                        imageReader?.surface?.let { newSurface ->
                            virtualDisplay?.surface = newSurface
                            Log.d(TAG, "New ImageReader surface set on VirtualDisplay.")
                        } ?: Log.e(TAG, "New ImageReader surface is null after creation for update.")
                    } else {
                        Log.w(TAG, "setSurface for VirtualDisplay not available below API 28. Full recreation might be needed if issues persist after resize.")
                        // Fallback: Full re-setup if only resize is not enough.
                        // This might lead to issues on API 34+ if createVirtualDisplay is called again on the same token.
                        // However, our handleMediaProjectionResult always gets a fresh token.
                        // If a single MP token is meant to live across rotations, this path is risky.
                        // For now, assuming resize() is sufficient, or subsequent capture will fail and re-setup.
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating existing VirtualDisplay/ImageReader components. Attempting full setup.", e)
                    setupVirtualDisplayAndReader() // Fallback to full setup
                }
            } else {
                // MediaProjection exists, but VirtualDisplay doesn't. This implies a need for initial setup.
                Log.d(TAG, "MediaProjection exists, but VirtualDisplay is null. Calling setupVirtualDisplayAndReader.")
                setupVirtualDisplayAndReader()
            }
        } else {
            Log.w(TAG, "MediaProjection not available during updateScreenParameters. New parameters are stored and will be used when MediaProjection is next set up.")
        }
    }


    private fun setupVirtualDisplayAndReader(): Boolean {
        val currentMediaProjection = mediaProjection
        if (currentMediaProjection == null) {
            Log.e(TAG, "Cannot setup VirtualDisplay: MediaProjection is null.")
            return false
        }
        if (!isInitialized) {
            Log.e(TAG, "Cannot setup VirtualDisplay: Manager not initialized (displayMetrics not ready).")
            return false
        }

        releaseVirtualDisplayAndReader() // Clean up previous instances first

        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        if (width <= 0 || height <= 0) {
            Log.e(TAG, "Invalid dimensions for VirtualDisplay setup: ${width}x${height}. Attempting to update metrics again.")
            updateDisplayMetrics() // Try to get fresh metrics
            if (displayMetrics.widthPixels <= 0 || displayMetrics.heightPixels <= 0) {
                Log.e(TAG, "Still invalid dimensions after metrics update. Aborting VD setup.")
                return false
            }
            // Use the freshly updated metrics
            val newWidth = displayMetrics.widthPixels
            val newHeight = displayMetrics.heightPixels
            Log.w(TAG, "Retrying VD setup with re-fetched dimensions: ${newWidth}x${newHeight}")
        }


        Log.d(TAG, "Setting up VirtualDisplay with dimensions: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels} DPI: ${displayMetrics.densityDpi}")

        try {
            imageReader = ImageReader.newInstance(displayMetrics.widthPixels, displayMetrics.heightPixels, PixelFormat.RGBA_8888, 2 /* maxImages */)
            Log.d(TAG, "ImageReader created: ${imageReader.hashCode()} for MediaProjection ${currentMediaProjection.hashCode()}")

            virtualDisplay = currentMediaProjection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, // imageReader is guaranteed non-null here
                null, handler // Using a handler for callbacks can be useful
            )
            if (virtualDisplay == null) {
                Log.e(TAG, "Failed to create VirtualDisplay (returned null).")
                imageReader?.close(); imageReader = null
                return false
            }
            Log.i(TAG, "VirtualDisplay (${virtualDisplay.hashCode()}) and ImageReader created successfully.")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while creating VirtualDisplay. This might happen if MediaProjection token is reused incorrectly (Android 14+).", e)
            releaseVirtualDisplayAndReader()
            // Consider stopping the MediaProjection itself as it might be in an invalid state for reuse.
            // mediaProjection?.stop(); mediaProjection = null; // This would require re-requesting permission.
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Exception while creating VirtualDisplay or ImageReader", e)
            releaseVirtualDisplayAndReader()
            return false
        }
    }

    override fun captureScreen(callback: (Bitmap?) -> Unit) {
        if (!isInitialized) {
            Log.e(TAG, "captureScreen: Not initialized."); initialize()
            if (!isInitialized) { callback(null); return }
        }
        if (mediaProjection == null) {
            Log.e(TAG, "captureScreen: MediaProjection is null."); callback(null); return
        }
        if (virtualDisplay == null || imageReader == null) {
            Log.w(TAG, "captureScreen: VirtualDisplay or ImageReader not ready. Attempting setup.")
            if (!setupVirtualDisplayAndReader()) {
                Log.e(TAG, "captureScreen: Failed to setup VD/IR for capture."); callback(null); return
            }
            // After setup, check again.
            if (virtualDisplay == null || imageReader == null) {
                Log.e(TAG, "captureScreen: VD/IR still null after setup attempt."); callback(null); return
            }
        }

        if (isCurrentlyCapturingImage) {
            Log.w(TAG, "captureScreen: Already capturing. Ignoring subsequent request."); return
        }
        isCurrentlyCapturingImage = true
        Log.d(TAG, "Attempting capture. VD: ${virtualDisplay?.hashCode()}, IR: ${imageReader?.hashCode()}")

        val currentImageReader = imageReader ?: run {
            Log.e(TAG, "captureScreen: ImageReader became null unexpectedly before setting listener.");
            isCurrentlyCapturingImage = false; callback(null); return
        }
        Log.d(TAG, "Preparing ImageReader for capture: ${currentImageReader.hashCode()}. Surface valid: ${currentImageReader.surface?.isValid}")

        try {
            // Clear any stale images from the reader's queue before setting the new listener.
            // This is important if images might have been produced between captures or during setup.
            var imageToClear: Image?
            var clearedCount = 0
            do {
                imageToClear = currentImageReader.acquireNextImage() // Drains queue one by one
                if (imageToClear != null) {
                    imageToClear.close()
                    clearedCount++
                }
            } while (imageToClear != null)

            if (clearedCount > 0) {
                Log.d(TAG, "Cleared $clearedCount pre-existing image(s) from ImageReader before capture.")
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException while trying to clear ImageReader queue before capture. It might be closed.", e)
            isCurrentlyCapturingImage = false; callback(null); return
        } catch (e: Exception) {
            Log.w(TAG, "Exception while trying to clear ImageReader queue before capture", e)
        }

        currentImageReader.setOnImageAvailableListener(object : ImageReader.OnImageAvailableListener {
            private var imageProcessed = false // Ensure callback is invoked only once per capture request

            override fun onImageAvailable(reader: ImageReader) {
                if (imageProcessed) { // If already processed an image for this capture, ignore further availability
                    try { reader.acquireLatestImage()?.close() } catch (e: Exception) { /* ignore */ }
                    return
                }
                var image: Image? = null
                var bitmap: Bitmap? = null
                try {
                    image = reader.acquireLatestImage() // Get the latest image
                    if (image != null) {
                        imageProcessed = true // Mark as processed for this specific capture call
                        Log.d(TAG, "Image acquired from IR: ${reader.hashCode()}. Timestamp: ${image.timestamp}, WxH: ${image.width}x${image.height}")
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * image.width

                        // Create bitmap with correct width, then copy from buffer
                        val tempBitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
                        tempBitmap.copyPixelsFromBuffer(buffer)

                        // If there was row padding, crop the bitmap to the actual image width
                        bitmap = if (rowPadding == 0 && tempBitmap.width == image.width && tempBitmap.height == image.height) {
                            tempBitmap
                        } else {
                            Bitmap.createBitmap(tempBitmap, 0, 0, image.width, image.height).also {
                                if (tempBitmap != it) tempBitmap.recycle() // recycle tempBitmap if a new one was created
                            }
                        }
                        Log.d(TAG, "Bitmap created successfully for callback.");
                        callback(bitmap)
                    } else {
                        Log.w(TAG, "acquireLatestImage returned null in listener despite onImageAvailable being called.");
                        // Only call null callback if not already processed.
                        // This state (onImageAvailable but acquireLatestImage is null) should be rare.
                        if (!imageProcessed) callback(null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image in onImageAvailable listener", e)
                    bitmap?.recycle() // Clean up potentially created bitmap on error
                    if (!imageProcessed) callback(null) // Ensure callback if error before success
                } finally {
                    image?.close() // Must close the image
                    // Crucially, remove the listener to prevent it from firing again for this capture session or leaking.
                    reader.setOnImageAvailableListener(null, handler) // Use the same handler it was registered with, or null for main
                    isCurrentlyCapturingImage = false // Reset capture flag
                    Log.d(TAG, "Image processing finished in listener. Listener removed. isCurrentlyCapturingImage = false")
                }
            }
        }, handler)

        // Optional: Timeout for the capture operation
        handler.postDelayed({
            if (isCurrentlyCapturingImage) { // If still true, means onImageAvailable wasn't successfully processed
                Log.w(TAG, "Capture timeout occurred. isCurrentlyCapturingImage is still true.")
                currentImageReader.setOnImageAvailableListener(null, handler) // Clean up listener
                isCurrentlyCapturingImage = false
                callback(null) // Notify callback of failure
            }
        }, 3000) // 3-second timeout
    }


    private fun releaseVirtualDisplayAndReader() {
        virtualDisplay?.release()
        virtualDisplay = null
        Log.d(TAG, "VirtualDisplay released.")

        imageReader?.setOnImageAvailableListener(null, null) // Important to remove listener first
        imageReader?.close()
        imageReader = null
        Log.d(TAG, "ImageReader closed and released.")
    }

    private fun releaseMediaProjection() {
        mediaProjectionCallback?.let { cb ->
            mediaProjection?.unregisterCallback(cb)
            mediaProjectionCallback = null // Clear the reference
            Log.d(TAG, "MediaProjection.Callback unregistered.")
        }
        mediaProjection?.stop()
        mediaProjection = null
        Log.d(TAG, "MediaProjection stopped and released.")
    }

    private fun releaseAllCaptureResources() {
        Log.d(TAG, "Releasing all screen capture resources.")
        isCurrentlyCapturingImage = false // Stop any ongoing capture flag
        releaseVirtualDisplayAndReader()
        releaseMediaProjection()
        // isInitialized = false; // Do not reset isInitialized unless explicitly needed. displayMetrics might still be valid.
    }

    override fun stopCapture() {
        Log.i(TAG, "stopCapture called externally. Releasing all resources.")
        releaseAllCaptureResources()
    }

    override fun isMediaProjectionReady(): Boolean {
        return mediaProjection != null && virtualDisplay != null && imageReader != null && imageReader?.surface != null && imageReader!!.surface!!.isValid
    }

    override fun isCapturing(): Boolean {
        return isCurrentlyCapturingImage
    }
}