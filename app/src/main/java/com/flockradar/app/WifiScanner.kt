package com.flockradar.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Passive WiFi AP scan. NOT monitor mode — this reads beaconing access points
 * (SSID + BSSID) via WifiManager and matches them against Flock signatures.
 * Catches WiFi-beaconing Flock devices; cannot see probe-request-only cameras
 * (those need root + monitor mode -> the optional detector.py bridge).
 */
class WifiScanner(private val ctx: Context) {

    private val wifi = ctx.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val seen = HashSet<String>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = process()
    }

    @Suppress("DEPRECATION")
    private fun process() {
        val results = try { wifi.scanResults } catch (_: Exception) { return }
        for (r in results) {
            val bssid = r.BSSID ?: continue
            if (seen.contains(bssid)) continue
            val ssid = r.SSID
            val mName = Signatures.matchName(ssid)
            val mMac = Signatures.matchMac(bssid)
            if (mName != null || mMac != null) {
                seen.add(bssid)
                val match = JSONObject()
                    .put("ssid", mName ?: JSONObject.NULL)
                    .put("mac", mMac ?: JSONObject.NULL)
                Store.addDetection(
                    "wifi", Signatures.classifyBrand(ssid), ssid, bssid,
                    r.level, match, Store.gpsLat, Store.gpsLon
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        if (!registered) {
            ContextCompat.registerReceiver(ctx, receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        kick()
    }

    /** Trigger a scan; Android throttles this, so the service also re-kicks it. */
    @Suppress("DEPRECATION")
    fun kick() { try { wifi.startScan() } catch (_: Exception) { } }

    fun stop() {
        if (registered) { try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}; registered = false }
    }
}
