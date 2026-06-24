package com.flockradar.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var info: TextView
    private lateinit var hotspotBtn: Button
    private val prefs by lazy { getSharedPreferences("fr", Context.MODE_PRIVATE) }

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
        val settingsBtn = Button(this).apply {
            text = "Wi-Fi"
            setOnClickListener { showSettings() }
        }
        hotspotBtn = Button(this).apply {
            text = "Start hotspot"
            setOnClickListener { toggleHotspot() }
        }
        bar.addView(info); bar.addView(settingsBtn); bar.addView(hotspotBtn)

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

    private fun loadDashboardWhenReady(attempt: Int) {
        web.loadUrl("http://127.0.0.1:${RadarService.PORT}/")
        info.text = "Scanning BLE + WiFi"
        if (attempt < 4) {
            Handler(Looper.getMainLooper()).postDelayed(
                { web.reload() }, 1500L * (attempt + 1))
        }
    }

    // ---- Wi-Fi settings dialog ----
    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val ssidIn = EditText(this).apply {
            hint = "Network name (SSID)"
            setText(prefs.getString("ssid", "FlockRadar"))
        }
        val passIn = EditText(this).apply {
            hint = "Password (min 8 chars)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(prefs.getString("pass", ""))
        }
        val rootChk = CheckBox(this).apply {
            text = "Use my name/password (needs root)"
            isChecked = prefs.getBoolean("useRoot", true)
        }
        box.addView(TextView(this).apply {
            text = "Custom name/password needs root (your NetHunter phone has it). " +
                   "Unchecked = system hotspot with a random password."
            textSize = 12f
        })
        box.addView(ssidIn); box.addView(passIn); box.addView(rootChk)

        AlertDialog.Builder(this)
            .setTitle("Hotspot Wi-Fi")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("ssid", ssidIn.text.toString().trim())
                    .putString("pass", passIn.text.toString())
                    .putBoolean("useRoot", rootChk.isChecked)
                    .apply()
                info.text = "Saved. Tap Start hotspot."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleHotspot() {
        val hs = RadarService.hotspot ?: run { info.text = "service not ready"; return }
        if (hs.active) {
            hs.stop(); hotspotBtn.text = "Start hotspot"
            info.text = "Scanning BLE + WiFi"
            info.setTextColor(Color.parseColor("#9fb0c0"))
            return
        }
        val useRoot = prefs.getBoolean("useRoot", true)
        val ssid = prefs.getString("ssid", "FlockRadar") ?: "FlockRadar"
        val pass = prefs.getString("pass", "") ?: ""

        if (useRoot) {
            if (pass.length < 8) { info.text = "Set a password (8+ chars) in Wi-Fi settings first"; return }
            info.text = "Starting hotspot (root)…"
            hs.startRoot(ssid, pass,
                onReady = { runOnUiThread { showConnected(hs) } },
                onError = { msg -> runOnUiThread {
                    info.text = "Root hotspot failed: $msg — using system hotspot instead"
                    hs.start({ runOnUiThread { showConnected(hs) } },
                             { m -> runOnUiThread { info.text = m } })
                } })
        } else {
            info.text = "Starting hotspot…"
            hs.start(
                onReady = { runOnUiThread { showConnected(hs) } },
                onError = { msg -> runOnUiThread { info.text = msg } })
        }
    }

    private fun showConnected(hs: Hotspot) {
        hotspotBtn.text = "Stop hotspot"
        val ip = hs.apIp() ?: "192.168.49.1"
        val s = hs.ssid ?: "?"; val p = hs.passphrase ?: "?"
        info.text = "Wi-Fi: $s  ·  pass: $p\nOpen  http://$ip:${RadarService.PORT}"
        info.setTextColor(Color.parseColor("#39d98a"))
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
