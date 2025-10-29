// app/src/main/java/com/example/camerax_mlkit/BeaconForegroundService.kt
package com.example.camerax_mlkit

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.camerax_mlkit.security.WhitelistManager
import java.util.UUID

/**
 * 비콘 스캔을 포그라운드에서 유지하고, 광고 프레임을 받을 때마다
 * TriggerGate.setBeaconMeta(...) 로 상태를 업데이트한다.
 * (화이트리스트 매칭, 근접 타임아웃, Heads-up/PaymentPrompt 노출은 TriggerGate가 담당)
 */
class BeaconForegroundService : Service() {

    companion object {
        private const val CH_SCAN = "beacon_scan"        // 진행중(LOW)
        private const val CH_FULL = "beacon_fullscreen"  // 풀스크린(HIGH) - 현재는 미사용
        private const val FG_ID = 1000

        fun start(ctx: Context) {
            val i = Intent(ctx, BeaconForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BeaconForegroundService::class.java))
        }
    }

    private lateinit var monitor: BeaconMonitor

    // 블루투스 ON 이벤트에서 모니터 재시작
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED == intent.action) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    try {
                        if (::monitor.isInitialized) {
                            monitor.stop()
                            monitor.start()
                        }
                    } catch (_: Exception) { /* ignore */ }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        startForeground(FG_ID, buildOngoingNotification())

        // 화이트리스트 로드 (assets/whitelist.json)
        WhitelistManager.load(applicationContext)

        // BT 상태 리시버 등록
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        // BeaconMonitor 초기화
        monitor = BeaconMonitor(
            context = this,
            targetNamePrefix = null,
            ibeaconUuid = UUID.fromString("74278BDA-B644-4520-8F0C-720EAF059935"),
            rssiThreshold = -90,
            minTriggerIntervalMs = 5_000L,
            exitTimeoutMs = 5_000L,
            onNear = {
                // 🔹 별도 처리 불필요.
                // TriggerGate.setBeaconMeta(...) 가 호출될 때 내부에서 자동 팝업 판정(maybeShow)까지 수행.
            },
            onFar = {
                // 🔹 광고가 끊긴 경우: TriggerGate가 타임아웃으로 near=false 처리하므로
                // 여기서는 알림만 닫아준다(중복 방지).
                TriggerGate.cancelHeadsUp(applicationContext)
            },
            onFrame = { uuid, major, minor, nonce, rssi ->
                // ★ 광고 프레임마다 화이트리스트 매칭 + 근접 유지/팝업은 TriggerGate에 위임
                TriggerGate.setBeaconMeta(
                    ctx = applicationContext,
                    uuid = uuid,
                    major = major,
                    minor = minor,
                    nonce = nonce,
                    rssi = rssi
                )

                val hit = WhitelistManager.findBeacon(uuid, major, minor) != null
                android.util.Log.d(
                    "BeaconFS",
                    "frame uuid=$uuid major=$major minor=$minor rssi=$rssi wl=$hit"
                )
            }
        )

        monitor.start()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(btReceiver)
        } catch (_: Throwable) { /* already unregistered */ }

        if (::monitor.isInitialized) monitor.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 진행중 알림(LOW)
            nm.createNotificationChannel(
                NotificationChannel(CH_SCAN, "Beacon scanning", NotificationManager.IMPORTANCE_LOW)
            )
            // 풀스크린 알림(HIGH) – 현재는 사용하지 않지만 채널은 만들어 둔다
            nm.createNotificationChannel(
                NotificationChannel(CH_FULL, "Payment prompt", NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun buildOngoingNotification(): Notification {
        val contentPI = PendingIntent.getActivity(
            this, 1,
            Intent(this, PaymentPromptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_SCAN)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("비콘 스캔 중")
            .setContentText("매장 근접 여부를 감지하고 있습니다.")
            .setOngoing(true)
            .setContentIntent(contentPI)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun safeNotify(n: Notification) {
        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT < 33 || nm.areNotificationsEnabled()) {
            try { nm.notify(2001, n) } catch (_: SecurityException) {}
        }
    }
}
