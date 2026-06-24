package com.flockradar.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var info: TextView

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { startService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0c0f"))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
        }
        info = TextView(this).apply {
            text = "Starting…"
            setTextColor(Color.parseColor("#9fb0c0"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hotspotBtn = Button(this).apply {
            text = "Start hotspot"
            setOnClickListener { toggleHotspot(this) }
        }
        bar.addView(info); bar.addView(hotspotBtn)

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setGeolocationEnabled(true)
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?, cb: GeolocationPermissions.Callback?) {
                    cb?.invoke(origin, true, false)
                }
            }
        }

        root.addView(bar); root.addView(web)
        setContentView(root)

        requestPerms()
        loadDashboardWhenReady(0)
    }

    private fun requestPerms() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private fun startService() {
        val i = Intent(this, RadarService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    /** Service binds the port a moment after launch; retry the load until it's up. */
    private fun loadDashboardWhenReady(attempt: Int) {
        web.loadUrl("http://127.0.0.1:${RadarService.PORT}/")
        info.text = "Scanning BLE + WiFi"
        if (attempt < 4) {
            Handler(Looper.getMainLooper()).postDelayed(
                { web.reload() }, 1500L * (attempt + 1))
        }
    }

    private fun toggleHotspot(btn: Button) {
        val hs = RadarService.hotspot ?: run { info.text = "service not ready"; return }
        if (hs.active) {
            hs.stop(); btn.text = "Start hotspot"; info.text = "Scanning BLE + WiFi"
            return
        }
        info.text = "Starting hotspot…"
        hs.start(
            onReady = {
                runOnUiThread {
                    btn.text = "Stop hotspot"
                    val ssid = hs.ssid ?: "?"; val pass = hs.passphrase ?: "?"
                    info.text = "Wi-Fi: $ssid  ·  pass: $pass\nThen open  http://192.168.49.1:${RadarService.PORT}"
                    info.setTextColor(Color.parseColor("#39d98a"))
                }
            },
            onError = { msg -> runOnUiThread { info.text = msg } }
        )
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
