// app/src/main/java/com/example/camerax_mlkit/security/WhitelistManager.kt
package com.example.camerax_mlkit.security

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object WhitelistManager {
    private const val TAG = "WhitelistManager"
    private const val FILE = "whitelist.json"

    data class BeaconEntry(
        val uuid: String,
        val major: Int,
        val minor: Int,
        val locationId: String?,
        val merchantId: String?,
        val pubkeyPem: String?
    )

    private val map = ConcurrentHashMap<String, BeaconEntry>()
    private val merchantKey = ConcurrentHashMap<String, String>()
    @Volatile private var loaded = false

    private fun key(uuid: String, major: Int, minor: Int) =
        "${uuid.uppercase()}|$major|$minor"

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        try {
            val text = ctx.assets.open(FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            val arr = root.optJSONArray("beacons") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uuid = o.getString("uuid")
                val major = o.getInt("major")
                val minor = o.getInt("minor")

                // ✅ camelCase & snake_case 모두 지원
                val locId = when {
                    o.has("locationId")   -> o.optString("locationId", null)
                    o.has("location_id")  -> o.optString("location_id", null)
                    else -> null
                }
                val merch = when {
                    o.has("merchantId")   -> o.optString("merchantId", null)
                    o.has("merchant_id")  -> o.optString("merchant_id", null)
                    else -> null
                }
                val pub   = o.optString("pubkey", null)

                val entry = BeaconEntry(uuid, major, minor, locId, merch, pub)
                map[key(uuid, major, minor)] = entry
                if (!merch.isNullOrBlank() && !pub.isNullOrBlank()) merchantKey[merch] = pub
            }
            loaded = true
            Log.d(TAG, "whitelist loaded: ${map.size} beacons, ${merchantKey.size} merchants")
        } catch (t: Throwable) {
            Log.e(TAG, "whitelist load failed", t)
        }
    }

    fun findBeacon(uuid: String, major: Int, minor: Int): BeaconEntry? =
        map[key(uuid, major, minor)]

    fun getMerchantPubKey(merchantId: String): String? =
        merchantKey[merchantId]
}
