package com.example.camerax_mlkit

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.camerax_mlkit.geofence.GeofenceRegistrar
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // --- 카메라 관련 ---
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView

    // --- 위치 관련 ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest
    private lateinit var geofenceRegistrar: GeofenceRegistrar // 🔥 GeofenceRegistrar 추가

    // --- 기타 UI ---
    private lateinit var scanResultTextView: TextView
    private lateinit var accountQrButton: Button

    // --- 상태 변수 ---
    private var isQrOnlyMode = false // 인앱 스캐너 모드 플래그

    // --- 퍼미션 요청 런처 ---
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            handlePermissionsResult(permissions)
        }

    // --- TriggerGate 연동 ---
    private val triggerGateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TriggerGate.ACTION_PAY_PROMPT) {
                // 앱이 포그라운드일 때 수신 -> 현재 QR 스캔 모드가 아니면 바로 팝업
                if (!isQrOnlyMode) {
                    val reason = intent.getStringExtra("reason") ?: "unknown"
                    showPaymentPrompt("TriggerGate 알림", "Reason: $reason")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        scanResultTextView = findViewById(R.id.scanResultTextView)
        accountQrButton = findViewById(R.id.accountQrButton)

        // 🔥 GeofenceRegistrar 인스턴스화
        geofenceRegistrar = GeofenceRegistrar(this)

        // 위치 클라이언트 초기화
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createLocationRequest()
        createLocationCallback()

        // 필수 권한 확인 및 요청
        checkAndRequestPermissions()

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 계좌 QR 버튼 리스너
        accountQrButton.setOnClickListener {
            startActivity(Intent(this, AccountQrActivity::class.java))
        }

        // 인앱 스캐너 모드 확인 (PaymentPromptActivity 에서 호출 시)
        isQrOnlyMode = intent.getBooleanExtra(EXTRA_QR_ONLY_MODE, false)
        if (isQrOnlyMode) {
            scanResultTextView.text = "URL 또는 앱 링크를 스캔하세요."
            accountQrButton.isEnabled = false // 스캐너 모드에서는 계좌 QR 버튼 비활성화
            Log.d(TAG, "인앱 스캐너 모드로 실행됨")
        }

        // TriggerGate 브로드캐스트 리시버 등록
        registerReceiver(triggerGateReceiver, IntentFilter(TriggerGate.ACTION_PAY_PROMPT), RECEIVER_EXPORTED)

    }

    override fun onResume() {
        super.onResume()
        // 앱이 다시 활성화될 때 TriggerGate 상태 재평가 요청
        TriggerGate.onAppResumed(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        stopLocationUpdates()
        unregisterReceiver(triggerGateReceiver)
    }

    // --- 권한 처리 ---
    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        // Android 10 (Q) 이상에서는 백그라운드 위치 권한 필요
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        // Android 13 (Tiramisu) 이상에서는 알림 권한 필요
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Android 12 (S) 이상에서는 블루투스 스캔 권한 필요
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        } else {
            // 모든 권한이 있으면 서비스 시작
            startServicesAndCamera()
        }
    }

    private fun handlePermissionsResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "모든 권한이 허용되었습니다.", Toast.LENGTH_SHORT).show()
            startServicesAndCamera()
        } else {
            Toast.makeText(this, "앱 기능 사용을 위해 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            // 필요한 경우 사용자에게 설정으로 이동하도록 안내
            // finish() // 또는 앱 종료
        }
    }

    // --- 서비스 시작 및 카메라 설정 ---
    @SuppressLint("MissingPermission") // 권한 체크는 이미 수행됨
    private fun startServicesAndCamera() {
        // 비콘 스캔 서비스 시작 (백그라운드 실행을 위해 포그라운드 서비스 사용)
        startBeaconService()
        // 위치 업데이트 시작
        startLocationUpdates()
        // 🔥 Geofence 등록 호출
        setupGeofence()
        // 카메라 시작
        startCamera()
    }

    // 🔥 Geofence 등록 함수 추가
    @SuppressLint("MissingPermission") // 권한 체크는 이미 수행됨
    private fun setupGeofence() {
        // GeofenceRegistrar를 사용하여 기본 지오펜스 등록
        geofenceRegistrar.registerDefaultFences()
        Log.d(TAG, "Default geofences registration requested.")
    }

    private fun startBeaconService() {
        val serviceIntent = Intent(this, BeaconForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        Log.d(TAG, "BeaconForegroundService started")
    }

    // --- 카메라 설정 및 QR 스캔 ---
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QrCodeAnalyzer { qrCodeValue ->
                        runOnUiThread {
                            processQrCode(qrCodeValue)
                        }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e(TAG, "카메라 바인딩 실패", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processQrCode(qrValue: String) {
        scanResultTextView.text = "스캔 결과: $qrValue"
        Log.d(TAG, "QR Code detected: $qrValue")

        // 인앱 스캐너 모드 처리
        if (isQrOnlyMode) {
            try {
                // URL 또는 앱 스킴을 열려고 시도
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(qrValue))
                startActivity(intent)
                finish() // 스캔 후 액티비티 종료
            } catch (e: Exception) {
                Toast.makeText(this, "열 수 없는 QR 코드입니다: $qrValue", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Failed to open QR code content", e)
            }
            return // 스캐너 모드에서는 아래 결제 로직 실행 안 함
        }

        // 일반 모드: TriggerGate 정책 확인 후 결제 팝업 표시
        if (TriggerGate.allowedForQr()) {
            Log.d(TAG, "TriggerGate 정책 통과. 결제 팝업 표시 시도.")
            showPaymentPrompt("QR 스캔됨", qrValue)
        } else {
            Log.d(TAG, "TriggerGate 정책 실패. 팝업 표시 안 함. (State: geo=${TriggerGate.inGeofence}, beacon=${TriggerGate.nearBeacon}, wifi=${TriggerGate.onTrustedWifi}, fenceId=${TriggerGate.lastFenceId}, beaconLoc=${TriggerGate.getCurrentBeacon()?.locationId})")
            Toast.makeText(this, "결제 허용 조건 미충족", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPaymentPrompt(title: String, message: String) {
        // 이미 떠있는 팝업이 있는지 확인 (중복 방지)
        if (supportFragmentManager.findFragmentByTag("PaymentPromptDialog") != null) {
            Log.d(TAG, "PaymentPromptDialog is already shown.")
            return
        }
        PaymentPromptActivity.showAsDialog(this, title, message, "QR_SCAN")
    }

    // --- 위치 업데이트 관련 ---
    private fun createLocationRequest() {
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000) // 10초 간격
            .setMinUpdateIntervalMillis(5000) // 최소 5초 간격
            .build()
    }

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d(TAG, "Location Update: Lat=${location.latitude}, Lng=${location.longitude}")
                    // 필요 시 위치 정보를 다른 곳에 전달하거나 UI 업데이트
                }
            }
        }
    }

    @SuppressLint("MissingPermission") // 권한 체크는 이미 수행됨
    private fun startLocationUpdates() {
        Log.i(TAG, "Starting location updates")
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

// 🔥 =================== [최종 수정 시작] =================== 🔥
        // Geofence 초기 트리거 실패 시 상태를 복구하는 로직 추가
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val currentLat = location.latitude
                val currentLng = location.longitude
                Log.d(TAG, "Checking last location for Geofence state recovery: Lat=$currentLat, Lng=$currentLng")

                // 🚨 수정: GeofenceRegistrar 클래스 인스턴스(geofenceRegistrar)를 통해 isInside 호출
                val isInA = geofenceRegistrar.isInside(currentLat, currentLng, GeofenceRegistrar.FENCE_A_ID)
                val isInB = geofenceRegistrar.isInside(currentLat, currentLng, GeofenceRegistrar.FENCE_B_ID)

                val inZone = isInA || isInB // 둘 중 하나라도 안에 있으면 true
                val fenceId = when {
                    isInA -> GeofenceRegistrar.FENCE_A_ID
                    isInB -> GeofenceRegistrar.FENCE_B_ID
                    else -> null
                }

                // 현재 TriggerGate의 상태가 실제 위치와 다를 경우에만 업데이트 (불필요한 호출 방지)
                if (TriggerGate.inGeofence != inZone || TriggerGate.lastFenceId != fenceId) {
                    TriggerGate.onGeofenceChanged(this, inZone, fenceId)
                    Log.d(TAG, "Geofence State Restored based on last location: inside=$inZone, fence=$fenceId")
                } else {
                    Log.d(TAG, "Geofence State is already consistent with last location.")
                }

            } else {
                Log.w(TAG, "Last location is null, cannot restore geofence state using last location.")
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to get last location for Geofence state recovery", e)
        }
        // 🔥 =================== [최종 수정 끝] =================== 🔥
    }



    private fun stopLocationUpdates() {
        Log.i(TAG, "Stopping location updates")
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    // --- QR 코드 분석기 클래스 ---
    private class QrCodeAnalyzer(private val listener: (String) -> Unit) : ImageAnalysis.Analyzer {
        companion object {
            private const val TAG = "QrCodeAnalyzer"
        }

        private val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        private val scanner = BarcodeScanning.getClient(options)

        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            barcode.rawValue?.let {
                                listener(it)
                                // 첫 번째 QR 코드만 처리하고 종료 (필요 시 수정)
                                return@addOnSuccessListener
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Barcode scanning failed", e)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close() // 이미지가 null이면 바로 닫기
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_QR_ONLY_MODE = "qr_only_mode" // 인텐트 Extra 키
    }
}