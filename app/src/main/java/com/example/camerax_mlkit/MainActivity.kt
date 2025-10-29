package com.example.camerax_mlkit

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camerax_mlkit.databinding.ActivityMainBinding
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.ContentValues
import android.annotation.SuppressLint

class MainActivity : AppCompatActivity() {

    // ───────── Camera / ML Kit ─────────
    private lateinit var viewBinding: ActivityMainBinding
    private var cameraExecutor: ExecutorService? = null
    private var barcodeScanner: BarcodeScanner? = null
    private var cameraController: LifecycleCameraController? = null
    private var scannerOnlyMode = false

    // ───────── Geofence ─────────
    private lateinit var geofencingClient: GeofencingClient
    private lateinit var settingsClient: SettingsClient

    // 내부 브로드캐스트(TriggerGate → PaymentPromptActivity)
    private val payPromptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TriggerGate.ACTION_PAY_PROMPT) {
                if (!TriggerGate.allowedForQr()) {
                    Log.d(TAG, "skip pay prompt: wifi not allowed")
                    return
                }
                Log.d(TAG, "ACTION_PAY_PROMPT → PaymentPromptActivity")
                val reason = intent.getStringExtra("reason")
                val geo    = intent.getBooleanExtra("geo", false)
                val beacon = intent.getBooleanExtra("beacon", false)
                val wifi   = intent.getBooleanExtra("wifi", false)
                startActivity(
                    Intent(this@MainActivity, PaymentPromptActivity::class.java).apply {
                        putExtra(PaymentPromptActivity.EXTRA_TITLE,   "결제 안내")
                        putExtra(PaymentPromptActivity.EXTRA_MESSAGE, "안전한 QR 결제를 진행하세요.")
                        putExtra(PaymentPromptActivity.EXTRA_TRIGGER, reason ?: "prompt")
                        putExtra("geo", geo); putExtra("beacon", beacon); putExtra("wifi", wifi)
                    }
                )
            }
        }
    }

    // 지오펜스 PendingIntent (항상 같은 action/reqCode/flags)
    private fun geofencePendingIntent(): PendingIntent {
        val intent = Intent(GEOFENCE_ACTION).setClass(
            this,
            com.example.camerax_mlkit.geofence.GeofenceBroadcastReceiver::class.java
        )
        val flags = if (Build.VERSION.SDK_INT >= 31)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(this, GEOFENCE_REQ_CODE, intent, flags)
    }

    // BLE 권한 런처
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val scan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (result[Manifest.permission.BLUETOOTH_SCAN] ?: false) else true
        val connect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (result[Manifest.permission.BLUETOOTH_CONNECT] ?: false) else true
        if (fine && scan && connect) {
            BeaconForegroundService.start(this)
        } else {
            Toast.makeText(this, "BLE 권한 거부(비콘 감지 비활성화)", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }
    }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignore */ }

    // ───────── Lifecycle ─────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 신뢰 Wi-Fi 감시
        WifiTrigger.start(this)
        ensurePostNotificationsPermission()

        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // ✅ PaymentPromptActivity → openInAppScanner()에서 넘긴 플래그 읽기
        scannerOnlyMode = intent.getBooleanExtra("openScanner", false)

        viewBinding.cameraCaptureButton.setOnClickListener { takePhoto() }

        if (allPermissionsGranted()) startCameraSafely()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        // Geofencing init
        geofencingClient = LocationServices.getGeofencingClient(this)
        settingsClient   = LocationServices.getSettingsClient(this)

        // 위치 권한 후 → 지오펜스 등록
        ensureLocationPermission {
            ensureLocationSettings {
                addOrUpdateDuksungGeofence()
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 비콘 권한/시작
        ensureBlePermissions()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // PaymentPromptActivity가 SINGLE_TOP으로 MainActivity를 다시 띄울 때 처리
        val newScannerOnly = intent?.getBooleanExtra("openScanner", false) ?: false
        if (newScannerOnly && !scannerOnlyMode) {
            scannerOnlyMode = true
            Toast.makeText(this, "인앱 스캐너 모드로 전환되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TriggerGate.ACTION_PAY_PROMPT)
        ContextCompat.registerReceiver(
            this,
            payPromptReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        TriggerGate.onAppResumed(applicationContext)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(payPromptReceiver) } catch (_: IllegalArgumentException) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { cameraExecutor?.shutdown() } catch (_: Throwable) {}
        barcodeScanner?.close()
        barcodeScanner = null
        cameraController = null
    }

    // ───────── Camera / QR ─────────
    private fun startCameraSafely() {
        if (cameraController != null && barcodeScanner != null) return

        val controller = LifecycleCameraController(baseContext)
        val previewView: PreviewView = viewBinding.viewFinder

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = BarcodeScanning.getClient(options)

        controller.setImageAnalysisAnalyzer(
            ContextCompat.getMainExecutor(this),
            MlKitAnalyzer(
                listOf(scanner),
                COORDINATE_SYSTEM_VIEW_REFERENCED,
                ContextCompat.getMainExecutor(this)
            ) { result: MlKitAnalyzer.Result? ->
                val barcodeResults = result?.getValue(scanner)
                if (barcodeResults.isNullOrEmpty() || barcodeResults.first() == null) {
                    previewView.overlay?.clear()
                    return@MlKitAnalyzer
                }

                // 신뢰 Wi-Fi가 아닐 때는 결제 플로우로 가지 않음
                if (!scannerOnlyMode && !TriggerGate.allowedForQr()) return@MlKitAnalyzer

                val raw = barcodeResults[0].rawValue ?: return@MlKitAnalyzer

                if (scannerOnlyMode && isUrl(raw)) {
                    // 인앱 스캐너 모드 + URL → 바로 브라우저
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw)))
                    } catch (_: Exception) {
                        Toast.makeText(this, "URL 열기 실패", Toast.LENGTH_SHORT).show()
                    }
                    try { controller.clearImageAnalysisAnalyzer() } catch (_: Exception) {}
                    finish()
                    return@MlKitAnalyzer
                }

                // 기본 동작: 결제 선택창
                startPaymentPrompt(raw)
            }
        )
        controller.bindToLifecycle(this)
        previewView.controller = controller

        cameraController = controller
        barcodeScanner = scanner
    }

    private fun startPaymentPrompt(qrCode: String) {
        startActivity(
            Intent(this, PaymentPromptActivity::class.java)
                .putExtra(PaymentPromptActivity.EXTRA_QR_CODE, qrCode)
                .putExtra(PaymentPromptActivity.EXTRA_TRIGGER, "USER")
        )
    }

    private fun isUrl(s: String): Boolean =
        s.startsWith("http://", true) || s.startsWith("https://", true)

    private fun takePhoto() {
        val controller = cameraController ?: return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        controller.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "사진 촬영 실패: ${exc.message}", exc)
                    Toast.makeText(baseContext, "사진 촬영 실패", Toast.LENGTH_SHORT).show()
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "사진 저장 성공: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    // ───────── Geofence helpers ─────────
    private fun ensureLocationSettings(onReady: () -> Unit) {
        val req = LocationSettingsRequest.Builder()
            .addLocationRequest(
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L)
                    .build()
            )
            .build()

        settingsClient.checkLocationSettings(req)
            .addOnSuccessListener { onReady() }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    try { e.startResolutionForResult(this, RC_RESOLVE_LOCATION) }
                    catch (t: Throwable) {
                        Log.e(TAG, "Location settings resolution 실패", t)
                        Toast.makeText(this, "위치 설정을 켜주세요.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Log.e(TAG, "Location settings check 실패", e)
                    Toast.makeText(this, "위치 설정을 켜주세요.", Toast.LENGTH_LONG).show()
                }
            }
    }

    /** ✅ 덕성여대 시연: store_duksung_a + store_duksung_b 지점 등록 */
    @SuppressLint("MissingPermission") // 아래 호출은 hasLocationPermission() 가드 뒤에 실행됨
    private fun addOrUpdateDuksungGeofence() {
        // 1) 권한 가드
        if (!hasLocationPermission()) {
            Log.w(TAG, "지오펜스 등록 스킵: 위치 권한 미승인")
            return
        }

        // 2) 지오펜스 구성 (좌표/반경은 시연 장소로 조정하세요)
        val geofences = listOf(
            buildGeofence(
                id = "store_duksung_a",
                lat = 37.65326,  // TODO: 실제 좌표
                lng = 127.01640,
                radius = 120f    // TODO: 필요 시 반경 조정
            ),
            buildGeofence(
                id = "store_duksung_b",
                lat = 37.65390,  // TODO: 실제 좌표
                lng = 127.01690,
                radius = 120f
            )
        )

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(
                GeofencingRequest.INITIAL_TRIGGER_ENTER or
                        GeofencingRequest.INITIAL_TRIGGER_DWELL
            )
            .addGeofences(geofences)
            .build()

        // 3) 동일 PendingIntent 기준으로 기존 등록 제거 후 재등록(안정성)
        geofencingClient.removeGeofences(geofencePendingIntent()).addOnCompleteListener {
            geofencingClient.addGeofences(request, geofencePendingIntent())
                .addOnSuccessListener {
                    Log.i(TAG, "✅ 지오펜스 등록 완료: $geofences")
                    Toast.makeText(this, "지오펜스 등록 완료!", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    val code = (e as? ApiException)?.statusCode
                    Log.e(TAG, "❌ 지오펜스 등록 실패 code=$code", e)
                    if (e is SecurityException) {
                        Log.e(TAG, "권한 문제: 위치/백그라운드 위치 확인 필요")
                    }
                    Toast.makeText(this, "지오펜스 실패: ${code ?: e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun buildGeofence(id: String, lat: Double, lng: Double, radius: Float): Geofence =
        Geofence.Builder()
            .setRequestId(id.lowercase()) // ✅ whitelist(locationId)와 동일
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                        Geofence.GEOFENCE_TRANSITION_EXIT or
                        Geofence.GEOFENCE_TRANSITION_DWELL
            )
            .setLoiteringDelay(6_000)
            .build()

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ───────── Permissions ─────────
    private fun ensureBlePermissions() {
        val needS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION).apply {
            if (needS) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        val missing = required.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) blePermissionLauncher.launch(required.toTypedArray())
        else BeaconForegroundService.start(this)
    }

    private fun ensurePostNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val p = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(p)
            }
        }
    }

    private fun ensureLocationPermission(onGranted: () -> Unit = {}) {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted || !coarseGranted) {
            ActivityCompat.requestPermissions(this, LOCATION_PERMISSIONS, REQUEST_CODE_LOCATION)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!bgGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Toast.makeText(this, "백그라운드 위치 허용이 필요하면 설정에서 ‘항상 허용’을 선택하세요.", Toast.LENGTH_LONG).show()
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        REQUEST_CODE_BACKGROUND_LOCATION
                    )
                    return
                }
            }
        }
        onGranted()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_PERMISSIONS -> {
                if (allPermissionsGranted()) startCameraSafely()
                else { Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show(); finish() }
            }
            REQUEST_CODE_LOCATION -> {
                val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (granted) ensureLocationPermission { ensureLocationSettings { addOrUpdateDuksungGeofence() } }
                else Toast.makeText(this, "위치 권한이 필요합니다(지오펜싱).", Toast.LENGTH_LONG).show()
            }
            REQUEST_CODE_BACKGROUND_LOCATION -> {
                val bgGranted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (!bgGranted) Toast.makeText(this, "백그라운드 위치 권한이 거부되었습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ───────── Const ─────────
    companion object {
        private const val TAG = "CameraX-MLKit"

        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_CODE_LOCATION = 11
        private const val REQUEST_CODE_BACKGROUND_LOCATION = 12
        private const val RC_RESOLVE_LOCATION = 2001

        private val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        private const val GEOFENCE_ACTION = "com.example.camerax_mlkit.GEOFENCE_EVENT"
        private const val GEOFENCE_REQ_CODE = 1001
    }
}
