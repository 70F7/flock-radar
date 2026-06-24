package com.flockradar.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Passive BLE advertisement scan -> Flock/Raven signature match. No root needed. */
class BleScanner(private val ctx: Context) {

    private var scanner: BluetoothLeScanner? = null
    private val seen = HashSet<String>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handle(it) }
        }
    }

    private fun handle(result: ScanResult) {
        val mac = result.device.address ?: return
        if (seen.contains(mac)) return
        val rec = result.scanRecord
        val name = rec?.deviceName
        val uuids = rec?.serviceUuids?.map { it.uuid.toString().lowercase() }

        val mName = Signatures.matchName(name)
        val mMac = Signatures.matchMac(mac)
        val mUuid = Signatures.matchUuids(uuids)
        if (mName != null || mMac != null || mUuid.isNotEmpty()) {
            seen.add(mac)
            val match = JSONObject()
                .put("name", mName ?: JSONObject.NULL)
                .put("mac", mMac ?: JSONObject.NULL)
                .put("uuids", mUuid.joinToString(","))
            Store.addDetection(
                "ble", Signatures.classifyBrand(name, uuids), name, mac,
                result.rssi, match, Store.gpsLat, Store.gpsLon
            )
        }
    }

    private fun granted(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 31)
            Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!granted()) return
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        scanner = mgr.adapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner?.startScan(null, settings, callback)
        } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!granted()) return
        try { scanner?.stopScan(callback) } catch (_: Exception) { }
    }
}
