package com.example.camerax_mlkit

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.camerax_mlkit.security.WhitelistManager
import java.util.concurrent.atomic.AtomicReference

/**
 * 결제 안내 노출의 단일 진입 게이트.
 * - 상태 소스: 지오펜스, 비콘, 신뢰 Wi-Fi
 * - 정책: (지오펜스 AND 비콘 AND 같은 locationId) OR 신뢰 Wi-Fi
 * - 쿨다운: 짧은 시간 내 중복 노출 방지
 */
object TriggerGate {

    private const val TAG = "TriggerGate"

    /** 앱 내 브로드캐스트: 포그라운드일 때 PaymentPromptActivity 없이도 화면(프래그먼트 등)에게 알릴 수 있다. */
    const val ACTION_PAY_PROMPT = "com.example.camerax_mlkit.ACTION_PAY_PROMPT"

    // --- 알림 채널 --- (오류 발생 방지를 위해 상위로 이동)
    private const val CH_PAY_PROMPT = "pay_prompt"
    private const val NOTI_ID = 2025

    // --- 러닝 상태 (멀티스레드 접근 가능 값들은 volatile/atomic 사용) ---
    @Volatile private var onTrustedWifi: Boolean = false
    @Volatile private var inGeofence: Boolean = false
    @Volatile private var nearBeacon: Boolean = false
    @Volatile private var lastFenceId: String? = null // 가장 최근 진입/체류/이탈을 발생시킨 지오펜스 ID

    data class BeaconMeta(
        val uuid: String,
        val major: Int,
        val minor: Int,
        val locationId: String?, // 화이트리스트의 장소 ID
        val merchantId: String?, // 결제 가맹점 ID(시연/확장용)
        val nonce: String?,      // 추후 비콘-QR 토큰 대조 시 활용
        val rssi: Int
    )
    private val currentBeaconRef = AtomicReference<BeaconMeta?>(null)

    // --- 노출 제어 ---
    private var lastShownAt = 0L
    private const val COOLDOWN_MS = 3000L // 변경 시 중복 노출 빈도 조절
    private const val BEACON_NEAR_TIMEOUT_MS = 15000L
    private var beaconNearUntil = 0L


    /**
     * 계좌 QR(일반 카메라) 경로에서 허용 여부를 판단한다.
     * 수정: 신뢰 Wi-Fi뿐만 아니라 (지오펜스+비콘) 조건도 함께 확인하도록 변경.
     */
    fun allowedForQr(): Boolean {
        return evaluatePolicy().first // 정책 평가 함수의 결과(allow 여부)를 바로 반환
    }

    //region 지오펜스 상태 갱신 ------------------------------------------------------

    /**
     * 지오펜스 상태를 갱신한다. fenceId는 requestId(lowercase 권장).
     */
    fun onGeofenceChanged(ctx: Context, inZone: Boolean, fenceId: String?) {
        inGeofence = inZone
        lastFenceId = fenceId?.lowercase() // 비교 일관성을 위해 소문자화

        val beaconLoc = currentBeaconRef.get()?.locationId?.lowercase()
        Log.d(
            TAG,
            "Geofence → in=$inGeofence fenceId=$lastFenceId " +
                    "beaconNear=$nearBeacon beaconLoc=$beaconLoc wifi=$onTrustedWifi"
        )

        maybeShow(ctx, reason = "GEOFENCE")
        if (!inZone) cancelHeadsUp(ctx)
    }

    /** 하위호환용 오버로드(예전 코드에서 fenceId 전달이 없을 때). */
    fun onGeofenceChanged(ctx: Context, inZone: Boolean) =
        onGeofenceChanged(ctx, inZone, null)

    //endregion --------------------------------------------------------------------

    //region 비콘 상태 갱신 ---------------------------------------------------------

    /**
     * 스캔된 비콘을 화이트리스트와 대조해 메타를 설정한다.
     * - 매칭 시 nearBeacon=true, locationId/merchantId/nonce 저장
     * - 매칭 실패 시 nearBeacon=false, 알림 취소
     */
    fun setBeaconMeta(
        ctx: Context,
        uuid: String,
        major: Int,
        minor: Int,
        nonce: String?,
        rssi: Int
    ) {
        val entry = WhitelistManager.findBeacon(uuid, major, minor)
        nearBeacon = entry != null

        if (entry != null) {
            currentBeaconRef.set(
                BeaconMeta(
                    uuid = uuid,
                    major = major,
                    minor = minor,
                    locationId = entry.locationId,
                    merchantId = entry.merchantId,
                    nonce = nonce,
                    rssi = rssi
                )
            )
            markBeaconNearForAWhile(ctx)
        } else {
            currentBeaconRef.set(null)
            nearBeacon = false
            cancelHeadsUp(ctx)
        }

        val fenceLoc = lastFenceId?.lowercase()
        val beaconLoc = entry?.locationId?.lowercase()
        Log.d(
            TAG,
            "Beacon → near=$nearBeacon uuid=$uuid major=$major minor=$minor rssi=$rssi " +
                    "beaconLoc=$beaconLoc fenceLoc=$fenceLoc"
        )
    }

    /** 비콘이 가까운 상태를 일정 시간 유지해 UI가 안정적으로 반응하도록 한다. */
    private fun markBeaconNearForAWhile(ctx: Context) {
        beaconNearUntil = System.currentTimeMillis() + BEACON_NEAR_TIMEOUT_MS
        maybeShow(ctx, reason = "BEACON")

        Handler(Looper.getMainLooper()).postDelayed({
            if (System.currentTimeMillis() >= beaconNearUntil) {
                nearBeacon = false
                currentBeaconRef.set(null)
                cancelHeadsUp(ctx)
                Log.d(TAG, "Beacon near timeout → near=false")
            }
        }, BEACON_NEAR_TIMEOUT_MS)
    }

    //endregion --------------------------------------------------------------------

    //region Wi-Fi 상태 갱신 --------------------------------------------------------

    /** 신뢰 Wi-Fi 상태를 갱신한다. true이면 즉시 노출 후보가 된다. */
    fun setTrustedWifi(ok: Boolean, ctx: Context) {
        onTrustedWifi = ok
        if (!ok) {
            cancelHeadsUp(ctx)
        } else {
            maybeShow(ctx, reason = "WIFI")
        }
        Log.d(TAG, "TrustedWiFi → $onTrustedWifi")
    }

    //endregion --------------------------------------------------------------------

    /** 앱이 다시 전면으로 올 때도 정책을 재평가한다. */
    fun onAppResumed(ctx: Context) {maybeShow(ctx, reason = "RESUME")
    }

    /** 현재 매칭된 비콘 메타를 읽는다(디버깅/표시용). */
    fun getCurrentBeacon(): BeaconMeta? = currentBeaconRef.get()

    /** 가장 최근 지오펜스 ID를 읽는다(디버깅/표시용). */
    fun getLastFenceId(): String? = lastFenceId

    //region 정책 평가 및 노출 -------------------------------------------------------
    /**
     * 현재 컨텍스트(지오펜스+비콘 또는 신뢰Wi-Fi)가 결제를 허용하는 상태인지 확인한다.
     * 정책: (지오펜스 AND 비콘 AND locationId 일치) OR 신뢰 Wi-Fi
     * @return Triple(허용 여부, 현재 비콘 위치, 현재 펜스 위치)
     */
    fun evaluatePolicy(): Triple<Boolean, String?, String?> {
        val beaconLoc = currentBeaconRef.get()?.locationId?.lowercase()
        val fenceLoc = lastFenceId?.lowercase()
        val locMatch = beaconLoc != null && fenceLoc != null && beaconLoc == fenceLoc

        // ✅ 핵심 정책 로직: 이 로직을 maybeShow()와 allowedForQr()이 공유하게 됩니다.
        val allow = nearBeacon

        return Triple(allow, beaconLoc, fenceLoc)
    }

    /**
     * 현재 상태를 평가해 결제 안내를 노출한다.
     * 정책: (지오펜스 AND 비콘 AND locationId 일치) OR 신뢰 Wi-Fi
     */
    @Synchronized
    private fun maybeShow(ctx: Context, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShownAt <= COOLDOWN_MS) return

        // ❌ [오류 해결] 여기에서 evaluatePolicy()를 호출하여 allow 변수를 설정합니다.
        val (allow, beaconLoc, fenceLoc) = evaluatePolicy()
        val locMatch = (beaconLoc != null && fenceLoc != null && beaconLoc == fenceLoc) // 디버그 로그 출력용

        // ❌ [오류 해결] 중복된 allow 선언과 정책 계산 로직을 제거합니다.
        /*
        val allow =
            (inGeofence && nearBeacon && locMatch) ||  // 같은 장소일 때만 허용
                    onTrustedWifi                               // 또는 신뢰 Wi-Fi
        */

        if (!allow) { // evaluatePolicy()에서 계산된 allow 변수 사용
            Log.d(
                TAG,
                "Popup BLOCK → geo=$inGeofence beacon=$nearBeacon wifi=$onTrustedWifi " +
                        "beaconLoc=$beaconLoc fenceLoc=$fenceLoc locMatch=$locMatch"
            )
            return
        }

        lastShownAt = now

        // 사용자에게 보여줄 메시지를 트리거별로 다르게 구성한다.
        val message = when (reason) {
            "WIFI"     -> "신뢰 가능한 Wi-Fi에 연결되었습니다."
            "GEOFENCE" -> "매장 반경에 진입했습니다."
            "BEACON"   -> "정상 매장 비콘이 감지되었습니다."
            else       -> "결제 안내"
        }

        postHeadsUp(ctx, title = "결제 안내", message = message, reason = reason)

        // 앱이 포그라운드면 내부 브로드캐스트로도 알린다.
        if (isAppForeground()) {
            ctx.sendBroadcast(Intent(ACTION_PAY_PROMPT).apply {
                putExtra("reason", reason)
                putExtra("geo", inGeofence)
                putExtra("beacon", nearBeacon)
                putExtra("wifi", onTrustedWifi)
                putExtra("fenceId", fenceLoc ?: "unknown")
            })
        }
    }

    //endregion --------------------------------------------------------------------

    //region 알림/채널 유틸 ----------------------------------------------------------

    /** Heads-up 알림을 게시해 사용자가 즉시 확인할 수 있도록 한다. */
    private fun postHeadsUp(ctx: Context, title: String, message: String, reason: String) {
        ensureHighChannel(ctx)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; skip notification")
            return
        }

        val intent = Intent(ctx, PaymentPromptActivity::class.java).apply {
            putExtra(PaymentPromptActivity.EXTRA_TITLE, title)
            putExtra(PaymentPromptActivity.EXTRA_MESSAGE, message)
            putExtra(PaymentPromptActivity.EXTRA_TRIGGER, reason)
            putExtra("geo", inGeofence)
            putExtra("beacon", nearBeacon)
            putExtra("wifi", onTrustedWifi)
            putExtra("fenceId", lastFenceId ?: "unknown")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        NotificationCompat.Builder(ctx, CH_PAY_PROMPT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
            .also { NotificationManagerCompat.from(ctx).notify(NOTI_ID, it) }
    }

    /** 게시된 Heads-up 알림을 취소한다. */
    fun cancelHeadsUp(ctx: Context) =
        NotificationManagerCompat.from(ctx).cancel(NOTI_ID)

    /** 중요 채널을 보장한다. */
    private fun ensureHighChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_PAY_PROMPT,
                    "결제 안내",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    /** 앱이 포그라운드 상태인지 확인한다. */
    private fun isAppForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    //endregion --------------------------------------------------------------------
}
