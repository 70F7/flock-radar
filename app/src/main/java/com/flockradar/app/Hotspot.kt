package com.flockradar.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Starts an offline LocalOnlyHotspot (no internet needed) other devices join to
 * reach the dashboard. Returns the auto-generated SSID + passphrase to show the
 * user. Available to apps from API 26 without root.
 */
class Hotspot(private val ctx: Context) {

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile var ssid: String? = null
    @Volatile var passphrase: String? = null
    @Volatile var active = false

    @SuppressLint("MissingPermission")
    fun start(onReady: () -> Unit, onError: (String) -> Unit) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    if (Build.VERSION.SDK_INT >= 30) {
                        val c = res.softApConfiguration
                        ssid = c.ssid
                        passphrase = c.passphrase
                    } else {
                        @Suppress("DEPRECATION")
                        val c = res.wifiConfiguration
                        @Suppress("DEPRECATION")
                        run { ssid = c?.SSID; passphrase = c?.preSharedKey }
                    }
                    active = true
                    onReady()
                }
                override fun onStopped() { active = false }
                override fun onFailed(reason: Int) { active = false; onError("hotspot failed (code $reason)") }
            }, null)
        } catch (e: Exception) {
            onError(e.message ?: "hotspot error")
        }
    }

    fun stop() {
        try { reservation?.close() } catch (_: Exception) {}
        reservation = null; active = false; ssid = null; passphrase = null
    }
}
