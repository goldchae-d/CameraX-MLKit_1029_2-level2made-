package com.example.camerax_mlkit.geofence

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.RequiresPermission
// 🔥 [오류 수정 1/4] GeofenceBroadcastReceiver 클래스를 임포트해야 합니다.
import com.example.camerax_mlkit.geofence.GeofenceBroadcastReceiver
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * 지오펜스 등록/해제 전담 모듈.
 * - requestId 는 화이트리스트의 locationId 와 일치해야 함.
 * 예) "store_duksung_a", "store_duksung_b"
 */
class GeofenceRegistrar(private val context: Context) {

    companion object {
        private const val TAG = "GeofenceRegistrar"

        // ✅ 반드시 화이트리스트와 동일 (소문자 권장)
        const val FENCE_A_ID = "store_duksung_a"
        const val FENCE_B_ID = "store_duksung_b"

        // ✅ Level 2 - 덕성여대 차미리사관 기반 두 지점
        private const val A_LAT = 37.65257    // 차미리사관
        private const val A_LNG = 127.01468
        private const val B_LAT = 37.65311    // 학생회관 방향(차관 계단 윗편)
        private const val B_LNG = 127.01527

        private const val RADIUS_M = 200f

        // 🔥 [수정 2/4] isInside 함수는 companion object 안에 위치해야 합니다. (정상 위치)
        /**
         * 등록된 지오펜스 내부에 해당 좌표가 존재하는지 대략적으로 판단하는 보조 함수.
         */
        fun isInside(currentLat: Double, currentLng: Double, fenceId: String): Boolean {
            // 등록된 지오펜스 A와 B의 중심 좌표를 가져옵니다.
            val (lat, lng) = when (fenceId.lowercase()) {
                FENCE_A_ID -> Pair(A_LAT, A_LNG)
                FENCE_B_ID -> Pair(B_LAT, B_LNG)
                else -> return false // 등록되지 않은 ID
            }

            // 여기서는 위도/경도의 대략적인 거리를 이용한 근사치 계산을 사용합니다.
            val RADIUS_DEGREE_APPROX = 0.0018 // 200m 근사치

            val dx = currentLng - lng
            val dy = currentLat - lat

            // 유클리드 거리 제곱이 반경 제곱보다 작으면 안에 있다고 간주
            return (dx * dx + dy * dy) < (RADIUS_DEGREE_APPROX * RADIUS_DEGREE_APPROX)
        }
    } // 👈 companion object 끝

    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(context.applicationContext)

    // 🔥 [수정 3/4] 아래 함수들이 클래스 바로 아래에 정의되도록 들여쓰기를 수정합니다.
    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
            .setAction("com.example.camerax_mlkit.GEOFENCE_EVENT") // ✅ Manifest/Receiver 와 동일
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            // 🔥 [오류 수정 4/4] PendingIdenT -> PendingIntent 오타 수정
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildGeofence(id: String, lat: Double, lng: Double, radius: Float): Geofence =
        Geofence.Builder()
            .setRequestId(id) // ✅ whitelist.locationId 와 동일해야 TriggerGate 매칭 통과
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                        Geofence.GEOFENCE_TRANSITION_EXIT or
                        Geofence.GEOFENCE_TRANSITION_DWELL
            )
            .setLoiteringDelay(10_000) // DWELL 판정 지연(10s, 필요시 조정)
            .build()

    private fun buildRequest(geofences: List<Geofence>): GeofencingRequest =
        GeofencingRequest.Builder()
            .setInitialTrigger(
                GeofencingRequest.INITIAL_TRIGGER_ENTER or
                        GeofencingRequest.INITIAL_TRIGGER_DWELL
            )
            .addGeofences(geofences)
            .build()
    // 🔥 [수정 3/4] 여기까지 함수들이 클래스 멤버인지 확인 (unregisterAll 안에 있으면 안 됩니다)

    /**
     * 권한 전제:
     * - ACCESS_FINE_LOCATION
     * - (백그라운드 필요 시) ACCESS_BACKGROUND_LOCATION
     */
    @RequiresPermission(anyOf = [
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION
    ])
    fun registerDefaultFences() {
        val list = listOf(
            buildGeofence(FENCE_A_ID, A_LAT, A_LNG, RADIUS_M),
            buildGeofence(FENCE_B_ID, B_LAT, B_LNG, RADIUS_M),
        )
        val request = buildRequest(list)

        // 기존 것 정리 후 재등록(시연 안정성)
        geofencingClient.removeGeofences(pendingIntent()).addOnCompleteListener {
            geofencingClient.addGeofences(request, pendingIntent())
                .addOnSuccessListener { Log.i(TAG, "Geofences registered ✅: $list") }
                .addOnFailureListener { e -> Log.e(TAG, "Register failed", e) }
        }
    }

    fun unregisterAll() {
        geofencingClient.removeGeofences(pendingIntent())
            .addOnSuccessListener { Log.i(TAG, "Geofences unregistered") }
            .addOnFailureListener { e -> Log.e(TAG, "Unregister failed", e) }

        // isInside 함수는 companion object로 이동했으므로 여기서는 삭제해야 합니다.
    }
}