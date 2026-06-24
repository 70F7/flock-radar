package com.flockradar.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.net.NetworkInterface

/**
 * Hotspot control.
 *  - system LocalOnlyHotspot: no root, RANDOM ssid/password.
 *  - root soft AP: uses `su` to start an AP with a CUSTOM ssid/password.
 */
class Hotspot(private val ctx: Context) {

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile var ssid: String? = null
    @Volatile var passphrase: String? = null
    @Volatile var active = false
    @Volatile var rootMode = false

    @SuppressLint("MissingPermission")
    fun start(onReady: () -> Unit, onError: (String) -> Unit) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    if (Build.VERSION.SDK_INT >= 30) {
                        val c = res.softApConfiguration; ssid = c.ssid; passphrase = c.passphrase
                    } else {
                        @Suppress("DEPRECATION") val c = res.wifiConfiguration
                        @Suppress("DEPRECATION") run { ssid = c?.SSID; passphrase = c?.preSharedKey }
                    }
                    rootMode = false; active = true; onReady()
                }
                override fun onStopped() { active = false }
                override fun onFailed(reason: Int) { active = false; onError("hotspot failed (code $reason)") }
            }, null)
        } catch (e: Exception) { onError(e.message ?: "hotspot error") }
    }

    // ---- root soft AP with custom SSID + password ----
    fun startRoot(wantSsid: String, wantPass: String, onReady: () -> Unit, onError: (String) -> Unit) {
        Thread {
            // free the radio: stop any existing AP, turn client wifi off, wait
            runRoot("cmd wifi stop-softap")
            runRoot("svc wifi disable")
            Thread.sleep(2500)

            // Android 14 accepts a few forms; let the system pick the band first.
            val attempts = listOf(
                "cmd wifi start-softap \"$wantSsid\" wpa2 \"$wantPass\"",
                "cmd wifi start-softap \"$wantSsid\" wpa2 \"$wantPass\" -b 2",
                "cmd wifi start-softap \"$wantSsid\" wpa2 \"$wantPass\" -b 5",
                "cmd wifi start-softap \"$wantSsid\" wpa3_transition \"$wantPass\" -b 2"
            )
            var lastOut = ""
            for (cmd in attempts) {
                val (code, out) = runRoot(cmd)
                lastOut = out
                if (code == -1 && out.contains("not found", true)) {
                    runRoot("svc wifi enable"); onError("no root: su not available"); return@Thread
                }
                // give the AP up to ~8s to come up before judging this attempt
                repeat(8) {
                    Thread.sleep(1000)
                    val ip = apIp()
                    if (ip != null && ip.endsWith(".1")) {
                        ssid = wantSsid; passphrase = wantPass; rootMode = true; active = true
                        onReady(); return@Thread
                    }
                }
                runRoot("cmd wifi stop-softap")
                Thread.sleep(1200)
            }
            runRoot("svc wifi enable")
            onError(lastOut.trim().take(140).ifBlank { "Soft AP failed (mState=13)" })
        }.start()
    }

    fun stop() {
        if (rootMode) {
            runRoot("cmd wifi stop-softap"); runRoot("svc wifi enable")
        } else {
            try { reservation?.close() } catch (_: Exception) {}
            reservation = null
        }
        active = false; rootMode = false; ssid = null; passphrase = null
    }

    private fun runRoot(cmd: String): Pair<Int, String> = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        p.waitFor()
        Pair(p.exitValue(), out)
    } catch (e: Exception) { Pair(-1, e.message ?: "su error") }

    fun apIp(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    val ip = addr.hostAddress ?: continue
                    if (addr.isLoopbackAddress || ip.contains(":")) continue
                    if (ip.startsWith("192.168.")) {
                        if (ip.endsWith(".1")) return ip
                        fallback = fallback ?: ip
                    }
                }
            }
            return fallback
        } catch (_: Exception) { return null }
    }
}
