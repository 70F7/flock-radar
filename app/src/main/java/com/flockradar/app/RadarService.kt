package com.flockradar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class RadarService : Service() {

    private var server: WebServer? = null
    private var ble: BleScanner? = null
    private var wifi: WifiScanner? = null
    private var loc: LocationProvider? = null
    private val handler = Handler(Looper.getMainLooper())
    private val wifiKick = object : Runnable {
        override fun run() { wifi?.kick(); handler.postDelayed(this, 30_000) }
    }

    companion object {
        @Volatile var hotspot: Hotspot? = null
        const val PORT = 8080
        const val CHANNEL = "flock_radar"
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())

        thread {
            try { Store.loadCamerasCsv(assets.open("cameras.csv").bufferedReader()) } catch (_: Exception) {}
        }
        server = WebServer(applicationContext, PORT).also {
            try { it.start(NanoConstants.SOCKET_TIMEOUT, false) } catch (_: Exception) {}
        }
        ble = BleScanner(applicationContext).also { it.start() }
        wifi = WifiScanner(applicationContext).also { it.start() }
        loc = LocationProvider(applicationContext).also { it.start() }
        handler.postDelayed(wifiKick, 30_000)
        hotspot = Hotspot(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(wifiKick)
        ble?.stop(); wifi?.stop(); loc?.stop()
        try { server?.stop() } catch (_: Exception) {}
        hotspot?.stop(); hotspot = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Flock Radar", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Flock Radar running")
            .setContentText("Scanning BLE + WiFi · dashboard on :$PORT")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }
}

/** NanoHTTPD's default socket read timeout. */
private object NanoConstants { const val SOCKET_TIMEOUT = 10_000 }
