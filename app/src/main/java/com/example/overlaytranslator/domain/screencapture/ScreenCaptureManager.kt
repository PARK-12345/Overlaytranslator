package com.example.overlaytranslator.domain.screencapture

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjection

interface ScreenCaptureManager {
    /**
     * ScreenCaptureManager를 초기화합니다. (DisplayMetrics 등 설정)
     */
    fun initialize()

    /**
     * 화면 캡처 권한 요청 인텐트를 생성하여 Activity에서 실행하도록 요청합니다.
     * @param activity 권한 요청을 시작할 Activity.
     * @param requestCode 권한 요청 코드.
     */
    fun requestScreenCapturePermission(activity: Activity, requestCode: Int)

    /**
     * MediaProjection 권한 요청 결과를 처리하고 MediaProjection 객체를 설정합니다.
     * @param resultCode Activity.RESULT_OK 또는 Activity.RESULT_CANCELED.
     * @param data MediaProjection 권한 요청 결과 Intent.
     * @return MediaProjection 설정 성공 여부.
     */
    fun handleMediaProjectionResult(resultCode: Int, data: Intent?): Boolean

    /**
     * 현재 설정된 MediaProjection을 사용하여 화면을 캡처합니다.
     * @param callback 캡처된 Bitmap 또는 null을 전달받는 콜백.
     */
    fun captureScreen(callback: (Bitmap?) -> Unit)

    /**
     * 화면 캡처를 중지하고 관련된 모든 리소스를 해제합니다.
     */
    fun stopCapture()

    /**
     * 현재 MediaProjection이 설정되어 있고 사용 가능한지 확인합니다.
     * @return MediaProjection 사용 가능 여부.
     */
    fun isMediaProjectionReady(): Boolean

    /**
     * 현재 화면 _캡처 작업_이 진행 중인지 확인합니다.
     * @return 캡처 작업 진행 여부.
     */
    fun isCapturing(): Boolean

    /**
     * 화면 매개변수(크기, DPI) 변경 시 호출되어 내부 상태를 업데이트합니다.
     * @param newWidth 새로운 화면 너비 (픽셀 단위).
     * @param newHeight 새로운 화면 높이 (픽셀 단위).
     * @param newDpi 새로운 화면 DPI.
     */
    fun updateScreenParameters(newWidth: Int, newHeight: Int, newDpi: Int) // ✨ 새 메서드 추가
}