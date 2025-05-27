package com.example.overlaytranslator.ui.main

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
// import androidx.lifecycle.viewModelScope // 현재 사용되지 않음
import com.example.overlaytranslator.services.OverlayService
import dagger.hilt.android.lifecycle.HiltViewModel
// import kotlinx.coroutines.flow.SharingStarted // 현재 사용되지 않음
// import kotlinx.coroutines.flow.StateFlow // 현재 사용되지 않음
// import kotlinx.coroutines.flow.map // 현재 사용되지 않음
// import kotlinx.coroutines.flow.stateIn // 현재 사용되지 않음
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    // 서비스 실행 상태 LiveData, 초기값은 false (서비스 중지 상태)
    private val _isOverlayServiceRunning = MutableLiveData<Boolean>(false)
    val isOverlayServiceRunning: LiveData<Boolean> = _isOverlayServiceRunning

    private val _showPermissionRationaleOverlay = MutableLiveData<Boolean>(false)
    val showPermissionRationaleOverlay: LiveData<Boolean> = _showPermissionRationaleOverlay

    private val _showPermissionRationaleNotification = MutableLiveData<Boolean>(false)
    val showPermissionRationaleNotification: LiveData<Boolean> = _showPermissionRationaleNotification

    fun updateOverlayServiceStatus(isRunning: Boolean) {
        if (_isOverlayServiceRunning.value != isRunning) { // 상태가 실제로 변경될 때만 업데이트
            _isOverlayServiceRunning.value = isRunning
            Log.d(TAG, "Overlay service status updated in ViewModel: $isRunning")
        }
    }

    fun startOverlayService(context: Context, mediaProjectionResultCode: Int, mediaProjectionData: Intent?) {
        if (!canDrawOverlays(context)) {
            // _showPermissionRationaleOverlay.value = true // Rationale는 Activity에서 직접 트리거하도록 변경
            Log.w(TAG, "Overlay permission not granted. Cannot start service.")
            updateOverlayServiceStatus(false) // 서비스 시작 불가 상태 반영
            return
        }
        // 알림 권한은 선택적이므로 서비스 시작을 막지 않음
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !areNotificationsEnabled(context)) {
        //     Log.w(TAG, "Notification permission not granted. Service may not show notifications properly on API 33+.")
        // }

        Log.d(TAG, "Attempting to start OverlayService with MediaProjection results.")
        val intent = Intent(context, OverlayService::class.java).apply {
            putExtra("media_projection_result_code", mediaProjectionResultCode)
            putExtra("media_projection_data", mediaProjectionData)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            // 서비스 시작 요청 후 즉시 상태를 true로 변경하지 않음.
            // 실제 서비스 시작 및 바인딩 성공은 ServiceConnection에서 확인 후 updateOverlayServiceStatus(true) 호출.
            // 다만, 사용자 경험상 시작 버튼을 누르면 바로 UI가 반응하는 것처럼 보이게 하려면 여기서 true로 설정할 수도 있으나,
            // 실제 서비스 상태와 불일치할 수 있는 단점이 있음. 현재는 ServiceConnection에 의존.
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OverlayService", e)
            updateOverlayServiceStatus(false) // 서비스 시작 실패 시 상태 반영
        }
    }

    fun stopOverlayService(context: Context) {
        Log.d(TAG, "Attempting to stop OverlayService from ViewModel.")
        val intent = Intent(context, OverlayService::class.java)
        try {
            context.stopService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop OverlayService", e)
        }
        updateOverlayServiceStatus(false) // 서비스 중지 요청 시 즉시 ViewModel 상태 업데이트
    }

    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // API 23 미만은 항상 true
        }
    }

    // areNotificationsEnabled는 현재 직접 사용되지 않으므로 주석 처리 또는 삭제 가능
    // fun areNotificationsEnabled(context: Context): Boolean {
    //     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    //         val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    //         return notificationManager.areNotificationsEnabled()
    //     }
    //     return true
    // }

    fun getOverlayPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    // getNotificationPermissionIntent는 현재 직접 사용되지 않으므로 주석 처리 또는 삭제 가능
    // fun getNotificationPermissionIntent(context: Context): Intent {
    //     return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    //         .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    // }

    fun triggerOverlayPermissionRationale() {
        _showPermissionRationaleOverlay.value = true
    }

    fun onPermissionRationaleShownOverlay() {
        _showPermissionRationaleOverlay.value = false
    }

    fun triggerNotificationPermissionRationale() {
        _showPermissionRationaleNotification.value = true
    }
    fun onPermissionRationaleShownNotification() {
        _showPermissionRationaleNotification.value = false
    }
}

