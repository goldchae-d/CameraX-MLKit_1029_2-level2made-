// app/src/main/java/com/example/camerax_mlkit/PaymentPromptActivity.kt
package com.example.camerax_mlkit

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.example.camerax_mlkit.crypto.RetrofitProvider
import com.example.camerax_mlkit.crypto.SessionKeyManager
import com.example.camerax_mlkit.security.QrToken
import com.example.camerax_mlkit.security.SignatureVerifier
import com.example.camerax_mlkit.security.WhitelistManager
import com.example.camerax_mlkit.security.SecureQr
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch

class PaymentPromptActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QR_CODE = "extra_qr_code"
        const val EXTRA_TITLE   = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_TRIGGER = "extra_trigger"
        private const val TAG   = "PaymentPromptActivity"
    }

    private var dialog: BottomSheetDialog? = null
    private var sheetView: View? = null
    private var latestQrText: String? = null

    // 지오펜스 ID(선택) — GeofenceBroadcastReceiver가 넣어주면 활용
    private var fenceId: String = "unknown"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 화이트리스트 1회 로드(메모리 캐시)
        WhitelistManager.load(this)

        // 트리거/컨텍스트 정보
        val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "UNKNOWN"
        val geo     = intent.getBooleanExtra("geo", false)
        val beacon  = intent.getBooleanExtra("beacon", false)
        val wifiOk  = TriggerGate.allowedForQr()
        fenceId     = intent.getStringExtra("fenceId") ?: "unknown"

        // 정책: (지오∧비콘) OR (신뢰 Wi-Fi) OR (USER)
        val allow = ((geo && beacon) || wifiOk || trigger == "USER")
        if (!allow) {
            Log.d(TAG, "blocked: need (geo AND beacon) OR trusted Wi-Fi (trigger=$trigger, geo=$geo, beacon=$beacon, wifi=$wifiOk)")
            finish(); return
        }

        // (선택 강화) 지오펜스/비콘 불일치 차단: 둘 다 있을 때만 체크
        if (geo && beacon) {
            val meta = TriggerGate.getCurrentBeacon()
            val loc  = meta?.locationId
            if (loc != null && fenceId != "unknown" && loc != fenceId) {
                Log.w(TAG, "Geofence/Beacon mismatch → deny (beaconLoc=$loc, fenceId=$fenceId)")
                Toast.makeText(this, "지점 불일치: 결제를 진행할 수 없습니다.", Toast.LENGTH_LONG).show()
                finish(); return
            }
        }

        // 잠금화면에서도 표시
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val title   = intent.getStringExtra(EXTRA_TITLE)   ?: getString(R.string.title_pay)
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: getString(R.string.subtitle_pay)
        Log.d(TAG, "title=$title, message=$message, trigger=$trigger, geo=$geo, beacon=$beacon, wifi=$wifiOk, fenceId=$fenceId")

        showOrExpandPayChooser(title, message)

        // 세션키/토큰 생성 (비콘 메타 기반 location_id/merchant_id 포함, 없으면 fenceId/unknown)
        lifecycleScope.launch {
            try {
                val (kid, sk) = SessionKeyManager.ensureKey(this@PaymentPromptActivity, RetrofitProvider.keyApi)
                val sid = SessionIdProvider.get(this@PaymentPromptActivity)

                val meta = TriggerGate.getCurrentBeacon()
                val entry = meta?.let { WhitelistManager.findBeacon(it.uuid, it.major, it.minor) }

                val merchantId = entry?.merchantId ?: "merchant_unknown"
                val locId      = entry?.locationId ?: fenceId

                val qrText = SecureQr.buildEncryptedToken(
                    kid = kid,
                    sessionKey = sk,
                    sessionId = sid,
                    merchantId = merchantId,
                    amount = null,
                    extra = mapOf(
                        "type"        to "account",
                        "location_id" to locId,         // 레벨2: 지점 고정
                        "fence_id"    to fenceId        // (로그/추적용)
                    )
                )
                latestQrText = qrText
                setTokenTextIfPresent(qrText)
                Log.d(TAG, "Secure QR generated (merchant=$merchantId, loc=$locId, fenceId=$fenceId)")
            } catch (t: Throwable) {
                Log.e(TAG, "QR 토큰 생성 실패", t)
                latestQrText = null
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        val trigger = intent?.getStringExtra(EXTRA_TRIGGER) ?: "UNKNOWN"
        val geo     = intent?.getBooleanExtra("geo", false) ?: false
        val beacon  = intent?.getBooleanExtra("beacon", false) ?: false
        val wifiOk  = TriggerGate.allowedForQr()
        fenceId     = intent?.getStringExtra("fenceId") ?: fenceId

        val allow = ((geo && beacon) || wifiOk || trigger == "USER")
        if (!allow) {
            Log.d(TAG, "blocked(newIntent): need (geo AND beacon) OR trusted Wi-Fi (trigger=$trigger)")
            finish(); return
        }

        // (선택 강화) 새 intent에도 불일치 차단
        if (geo && beacon) {
            val meta = TriggerGate.getCurrentBeacon()
            val loc  = meta?.locationId
            if (loc != null && fenceId != "unknown" && loc != fenceId) {
                Log.w(TAG, "Geofence/Beacon mismatch(newIntent) → deny (beaconLoc=$loc, fenceId=$fenceId)")
                Toast.makeText(this, "지점 불일치: 결제를 진행할 수 없습니다.", Toast.LENGTH_LONG).show()
                finish(); return
            }
        }

        val title   = intent?.getStringExtra(EXTRA_TITLE)   ?: getString(R.string.title_pay)
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: getString(R.string.subtitle_pay)
        showOrExpandPayChooser(title, message)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 보안 QR 검증(시연): 현재 비콘 메타와 토큰(payload)을 대조
    // payload = { merchant_id, location_id, nonce, expiry, ... }
    // ─────────────────────────────────────────────────────────────────────────────
    private fun verifyQrAgainstContext(rawQr: String): Boolean {
        val parsed = QrToken.parse(rawQr) ?: run {
            Log.w(TAG, "QR parse failed"); return false
        }
        val (payload, sig) = parsed

        val meta = TriggerGate.getCurrentBeacon() ?: run {
            Log.w(TAG, "No beacon meta; deny"); return false
        }

        val pubPem = WhitelistManager.getMerchantPubKey(payload.merchantId) ?: run {
            Log.w(TAG, "No pubkey for merchant=${payload.merchantId}; deny"); return false
        }

        val msg = QrToken.normalizedMessageForSign(payload)
        if (!SignatureVerifier.verifyEcdsaP256(pubPem, msg, sig)) {
            Log.w(TAG, "Signature invalid; deny"); return false
        }

        // 위치/nonce/만료 확인
        val beaconLoc = meta.locationId ?: ""
        if (payload.locationId != beaconLoc) {
            Log.w(TAG, "Location mismatch qr=${payload.locationId} beacon=$beaconLoc"); return false
        }
        // (선택 강화) fenceId도 알면 교차 확인
        if (fenceId != "unknown" && payload.locationId != fenceId) {
            Log.w(TAG, "Fence mismatch qrLoc=${payload.locationId} fenceId=$fenceId"); return false
        }

        val beaconNonce = meta.nonce ?: ""
        if (payload.nonce != beaconNonce) {
            Log.w(TAG, "Nonce mismatch qr=${payload.nonce} beacon=$beaconNonce"); return false
        }
        val nowSec = System.currentTimeMillis() / 1000
        if (payload.expiry < nowSec) {
            Log.w(TAG, "Expired token; deny"); return false
        }

        Log.d(TAG, "QR verify OK (merchant=${payload.merchantId}, loc=${payload.locationId}, fence=$fenceId)")
        return true
    }

    // ── 바텀시트 (결제 선택) ─────────────────────────────────────────
    private fun showOrExpandPayChooser(title: String, message: String) {
        dialog?.let { existing ->
            existing.findViewById<TextView>(R.id.tvTitle)?.text = title
            existing.findViewById<TextView>(R.id.tvSubtitle)?.text = message
            val sheet = existing.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let { BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED }
            sheetView = sheet
            latestQrText?.let { setTokenTextIfPresent(it) }
            return
        }

        val d = BottomSheetDialog(this)
        d.setContentView(R.layout.dialog_pay_chooser)
        d.setDismissWithAnimation(true)

        d.setOnShowListener {
            val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let { BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED }
            sheetView = sheet

            d.findViewById<TextView>(R.id.tvTitle)?.text = title
            d.findViewById<TextView>(R.id.tvSubtitle)?.text = message
            latestQrText?.let { setTokenTextIfPresent(it) }

            d.findViewById<View>(R.id.btnKakao)?.setOnClickListener {
                d.dismiss(); showKakaoPreview()
            }
            d.findViewById<View>(R.id.btnNaver)?.setOnClickListener {
                d.dismiss(); showNaverPreview()
            }
            // Toss 버튼 → 따릉이 앱 유도
            d.findViewById<View>(R.id.btnToss)?.setOnClickListener {
                d.dismiss(); openTtareungi(); finish()
            }
            d.findViewById<View>(R.id.btnInApp)?.setOnClickListener {
                d.dismiss(); openInAppScanner(); finish()
            }
            d.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
                d.dismiss(); finish()
            }
        }

        d.setOnCancelListener { finish() }
        d.show()
        dialog = d
    }

    // ── QR 이미지(리소스) 길게 눌러 디코딩/검증 ─────────────────────────
    private fun decodeQrFromDrawable(
        @DrawableRes resId: Int,
        onDone: (String?) -> Unit
    ) {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeResource(resources, resId, opts) ?: run { onDone(null); return }
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_AZTEC, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_PDF417)
            .build()
        val scanner = BarcodeScanning.getClient(options)
        val image = InputImage.fromBitmap(bmp, 0)

        scanner.process(image)
            .addOnSuccessListener { list -> onDone(list.firstOrNull()?.rawValue) }
            .addOnFailureListener { onDone(null) }
    }

    private fun openUrlPreferBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) { Log.w(TAG, "openUrlPreferBrowser failed: $url") }
    }

    /** 레이아웃에 tvToken id가 있을 때만 표시 */
    private fun setTokenTextIfPresent(text: String) {
        val root = sheetView ?: return
        val tv = root.findViewById<TextView?>(R.id.tvToken) ?: return
        tv.text = text
        tv.visibility = View.VISIBLE
    }

    private fun showPreview(
        @DrawableRes imgRes: Int,
        onClick: () -> Unit
    ) {
        val dialog = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_qr_preview, null, false)
        dialog.setContentView(v)

        val img = v.findViewById<ImageView>(R.id.imgPreview)
        img.setImageResource(imgRes)
        img.setOnClickListener { onClick() }

        img.setOnLongClickListener { view ->
            view.alpha = 0.4f
            Toast.makeText(this, "QR을 분석 중입니다…", Toast.LENGTH_SHORT).show()
            decodeQrFromDrawable(imgRes) { raw ->
                runOnUiThread {
                    view.alpha = 1.0f
                    if (raw == null) {
                        Toast.makeText(this, "QR을 인식하지 못했습니다.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    val isSecureCandidate = raw.contains(".")
                    val verified = if (isSecureCandidate) verifyQrAgainstContext(raw) else false
                    when {
                        verified -> {
                            dialog.dismiss()
                            Toast.makeText(this, "검증 통과: 안전한 결제 QR", Toast.LENGTH_SHORT).show()
                            showOrExpandPayChooser(getString(R.string.title_pay), getString(R.string.subtitle_pay))
                        }
                        raw.startsWith("http://") || raw.startsWith("https://") -> {
                            dialog.dismiss()
                            Toast.makeText(this, "일반 QR: 웹으로 이동합니다", Toast.LENGTH_SHORT).show()
                            openUrlPreferBrowser(raw)
                        }
                        else -> Toast.makeText(this, "검증 실패 또는 지원하지 않는 QR입니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            true
        }
        dialog.setOnCancelListener { /* no-op */ }
        dialog.show()
    }

    private fun showKakaoPreview() = showPreview(
        R.drawable.kakao_qr,
        onClick = { /* 시연은 길게 눌러 검증 */ }
    )

    private fun showNaverPreview() = showPreview(
        R.drawable.naver_qr,
        onClick = { /* 시연은 길게 눌러 검증 */ }
    )

    private fun openAccountQr() {
        val token = latestQrText.orEmpty()
        startActivity(Intent(this, AccountQrActivity::class.java).putExtra("qr_token", token))
    }

    private fun openInAppScanner() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("openScanner", true)
        )
    }

    private fun openKakaoPay() {
        val pkgKakaoPay = "com.kakaopay.app"
        val pkgKakaoTalk = "com.kakao.talk"
        val tryUris = listOf("kakaotalk://kakaopay/qr", "kakaotalk://kakaopay/home")
        for (u in tryUris) {
            try { startActivity(Intent(Intent.ACTION_VIEW, u.toUri())); return } catch (_: Exception) {}
        }
        if (launchPackage(pkgKakaoPay)) return
        if (launchPackage(pkgKakaoTalk)) return
        openStoreOrWeb(pkgKakaoPay, "카카오페이")
    }

    private fun openNaverPay() {
        val qrScheme = "naversearchapp://search?qmenu=qrcode&version=1"
        try { startActivity(Intent(Intent.ACTION_VIEW, qrScheme.toUri())); return } catch (_: Exception) {}
        val naverQrHome = "https%3A%2F%2Fm.pay.naver.com%2Fqr%2Fhome"
        val inApp = "naversearchapp://inappbrowser?url=$naverQrHome&target=inpage&version=6"
        try { startActivity(Intent(Intent.ACTION_VIEW, inApp.toUri())); return } catch (_: Exception) {}
        val pkgNaver = "com.nhn.android.search"
        if (launchPackage(pkgNaver)) return
        openStoreOrWeb(pkgNaver, "네이버")
    }

    /** Toss 버튼을 눌렀을 때 따릉이 앱으로 유도 */
    private fun openTtareungi() {
        val pkg = "com.dki.spb_android" // 서울자전거(따릉이)
        if (launchPackage(pkg)) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri()))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$pkg".toUri()))
        }
    }

    private fun openTossPay() {
        val tryUris = listOf(
            "supertoss://toss/pay",
            "supertoss://toss/home",
            "supertoss://scan",
            "toss://scan"
        )
        for (u in tryUris) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, u.toUri())
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setPackage("viva.republica.toss")
                startActivity(intent)
                return
            } catch (_: Exception) { /* try next */ }
        }
        val pkgToss = "viva.republica.toss"
        if (launchPackage(pkgToss)) return
        openStoreOrWeb(pkgToss, "토스")
    }

    private fun launchPackage(packageName: String): Boolean = try {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            true
        } else false
    } catch (_: Exception) { false }

    private fun openStoreOrWeb(packageName: String, storeQuery: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri()))
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, "market://search?q=$storeQuery".toUri()))
            }
        }
    }

    override fun onDestroy() {
        dialog?.setOnShowListener(null)
        dialog?.setOnCancelListener(null)
        dialog = null
        sheetView = null
        super.onDestroy()
    }
}
