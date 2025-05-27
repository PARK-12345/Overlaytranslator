package com.example.overlaytranslator.ui.main

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.overlaytranslator.R
import com.example.overlaytranslator.databinding.ActivityMainBinding
import com.example.overlaytranslator.services.OverlayService
import com.example.overlaytranslator.ui.settings.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint
// import javax.inject.Inject // ScreenCaptureManager 직접 주입은 현재 사용되지 않음

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // @Inject // ScreenCaptureManager는 ViewModel 또는 Service를 통해 관리하는 것이 좋음
    // lateinit var screenCaptureManager: ScreenCaptureManager

    private var overlayService: OverlayService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as OverlayService.OverlayBinder
            overlayService = binder.getService()
            isServiceBound = true
            viewModel.updateOverlayServiceStatus(true) // 서비스 연결 시 ViewModel 상태 업데이트
            Log.d(TAG, "OverlayService connected.")
            // updateUIBasedOnServiceState() // LiveData 옵저버가 처리하므로 중복 호출 불필요
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            overlayService = null
            isServiceBound = false
            viewModel.updateOverlayServiceStatus(false) // 서비스 연결 해제 시 ViewModel 상태 업데이트
            Log.d(TAG, "OverlayService disconnected.")
            // updateUIBasedOnServiceState() // LiveData 옵저버가 처리하므로 중복 호출 불필요
        }
    }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (viewModel.canDrawOverlays(this)) {
                Log.d(TAG, "Overlay permission granted after request.")
                checkAndRequestNotificationPermission()
            } else {
                Log.w(TAG, "Overlay permission denied after request.")
                // --- 오류 수정: R.string.permission_denied_overlay -> R.string.error_permission_draw_overlay ---
                Toast.makeText(this, R.string.error_permission_draw_overlay, Toast.LENGTH_LONG).show()
                updateUIBasedOnServiceState() // 권한 실패 시 UI 업데이트
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d(TAG, "Notification permission granted.")
            } else {
                Log.w(TAG, "Notification permission denied.")
                Toast.makeText(this, R.string.permission_denied_notification, Toast.LENGTH_SHORT).show()
            }
            requestMediaProjectionPermission() // 알림 권한 결과와 관계없이 다음으로 진행
        }

    private val mediaProjectionPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d(TAG, "MediaProjection permission result received. ResultCode: ${result.resultCode}")
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.i(TAG, "MediaProjection permission GRANTED by user.")
                viewModel.startOverlayService(this, result.resultCode, result.data)
                bindToOverlayService() // 서비스 시작 후 바인딩
            } else {
                Log.w(TAG, "MediaProjection permission DENIED by user or data is null.")
                // --- 오류 가능성 확인: strings.xml에 해당 문자열이 있는지 확인 필요 ---
                // 현재 strings.xml에는 "화면 캡처 권한이 거부되었거나 문제가 발생했습니다."에 대한 직접적인 ID가 없음.
                // error_media_projection_permission_denied 사용 권장
                Toast.makeText(this, R.string.error_media_projection_permission_denied, Toast.LENGTH_LONG).show()
                viewModel.updateOverlayServiceStatus(false) // 권한 실패 시 ViewModel 상태 업데이트
                // updateUIBasedOnServiceState() // LiveData 옵저버가 처리
            }
        }

    private val mediaProjectionRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OverlayService.ACTION_REQUEST_MEDIA_PROJECTION) {
                Log.d(TAG, "Received request for MediaProjection permission from service.")
                if (viewModel.canDrawOverlays(this@MainActivity)) {
                    Log.d(TAG, "Requesting MediaProjection permission again for the running service.")
                    val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjectionPermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                } else {
                    Log.w(TAG, "Cannot request MediaProjection because overlay permission is missing.")
                    Toast.makeText(this@MainActivity, R.string.error_permission_draw_overlay, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "MainActivity onCreate.")

        setupClickListeners()
        observeViewModel()

        if (OverlayService.isRunning) {
            bindToOverlayService()
        } else {
            viewModel.updateOverlayServiceStatus(false)
        }

        val intentFilter = IntentFilter(OverlayService.ACTION_REQUEST_MEDIA_PROJECTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaProjectionRequestReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaProjectionRequestReceiver, intentFilter)
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "MainActivity onStart.")
        if (OverlayService.isRunning && !isServiceBound) {
            bindToOverlayService()
        } else if (!OverlayService.isRunning && isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Service not registered or already unbound in onStart: $e")
            }
            isServiceBound = false
            viewModel.updateOverlayServiceStatus(false)
        } else {
            viewModel.isOverlayServiceRunning.value?.let { updateUIBasedOnServiceState(it) }
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "MainActivity onStop.")
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Service not registered or already unbound in onStop: $e")
            }
            isServiceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy.")
        unregisterReceiver(mediaProjectionRequestReceiver)
    }

    private fun bindToOverlayService() {
        Log.d(TAG, "Attempting to bind to OverlayService.")
        Intent(this, OverlayService::class.java).also { intent ->
            try {
                if (!bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                    Log.w(TAG, "bindService returned false. Service could not be bound or started.")
                    viewModel.updateOverlayServiceStatus(false)
                } else {
                    Log.d(TAG, "bindService call initiated. Waiting for onServiceConnected.")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Error binding to service - SecurityException.", e)
                viewModel.updateOverlayServiceStatus(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error binding to service", e)
                viewModel.updateOverlayServiceStatus(false)
            }
        }
    }

    private fun setupClickListeners() {
        binding.buttonStartOverlay.setOnClickListener {
            Log.d(TAG, "Start Overlay button clicked.")
            checkAndRequestOverlayPermission()
        }
        binding.buttonStopOverlay.setOnClickListener {
            Log.d(TAG, "Stop Overlay button clicked.")
            viewModel.stopOverlayService(this)
            if (isServiceBound) {
                try {
                    unbindService(serviceConnection)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Service not registered or already unbound on stop button: $e")
                }
                isServiceBound = false
            }
        }
        binding.buttonSettings.setOnClickListener {
            Log.d(TAG, "Settings button clicked.")
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun observeViewModel() {
        viewModel.isOverlayServiceRunning.observe(this) { isRunning ->
            Log.d(TAG, "Observed isOverlayServiceRunning: $isRunning")
            updateUIBasedOnServiceState(isRunning)
        }
        viewModel.showPermissionRationaleOverlay.observe(this) { show ->
            if (show) { showOverlayPermissionRationale(); viewModel.onPermissionRationaleShownOverlay() }
        }
        viewModel.showPermissionRationaleNotification.observe(this) { show ->
            if (show) { showNotificationPermissionRationale(); viewModel.onPermissionRationaleShownNotification() }
        }
    }

    private fun updateUIBasedOnServiceState(isRunning: Boolean? = null) {
        val currentRunningState = isRunning ?: viewModel.isOverlayServiceRunning.value ?: OverlayService.isRunning
        Log.d(TAG, "Updating UI based on service state: $currentRunningState")

        binding.buttonStartOverlay.isEnabled = !currentRunningState
        binding.buttonStopOverlay.isEnabled = currentRunningState
        binding.textViewStatus.text = if (currentRunningState) "오버레이 서비스 상태: 실행 중" else "오버레이 서비스 상태: 중지됨"
    }

    private fun checkAndRequestOverlayPermission() {
        if (viewModel.canDrawOverlays(this)) {
            Log.d(TAG, "Overlay permission already granted.")
            checkAndRequestNotificationPermission()
        } else {
            Log.d(TAG, "Overlay permission not granted. Requesting...")
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) {
                viewModel.triggerOverlayPermissionRationale()
            } else {
                overlayPermissionLauncher.launch(viewModel.getOverlayPermissionIntent(this))
            }
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Notification permission already granted.")
                    requestMediaProjectionPermission()
                }
                ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS) -> {
                    viewModel.triggerNotificationPermissionRationale()
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            Log.d(TAG, "Notification permission not required for this API level.")
            requestMediaProjectionPermission()
        }
    }

    private fun requestMediaProjectionPermission() {
        Log.d(TAG, "Requesting MediaProjection permission.")
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionPermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun showOverlayPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("권한 필요")
            .setMessage("오버레이 기능을 사용하려면 '다른 앱 위에 표시' 권한이 필요합니다. 설정 화면으로 이동하여 권한을 허용해주세요.")
            .setPositiveButton("설정으로 이동") { _, _ ->
                overlayPermissionLauncher.launch(viewModel.getOverlayPermissionIntent(this))
            }
            .setNegativeButton("취소") { _, _ ->
                // --- 오류 수정: R.string.permission_denied_overlay -> R.string.error_permission_draw_overlay ---
                Toast.makeText(this, R.string.error_permission_draw_overlay, Toast.LENGTH_LONG).show()
                updateUIBasedOnServiceState() // 권한 취소 시 UI 업데이트
            }
            .show()
    }

    private fun showNotificationPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("권한 필요")
            .setMessage("앱 알림을 표시하려면 알림 권한이 필요합니다. 서비스 상태 등을 알림으로 받으려면 권한을 허용해주세요. (선택 사항)")
            .setPositiveButton("권한 요청") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            .setNegativeButton("취소하고 계속") { _, _ ->
                Toast.makeText(this, "알림 없이 다음 단계로 진행합니다.", Toast.LENGTH_SHORT).show()
                requestMediaProjectionPermission() // 알림 권한 거부해도 다음 단계로
            }
            .show()
    }
}
