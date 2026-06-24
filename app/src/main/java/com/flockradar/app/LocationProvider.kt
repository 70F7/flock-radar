package com.flockradar.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationProvider(private val ctx: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(ctx)
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { Store.gpsLat = it.latitude; Store.gpsLon = it.longitude }
        }
    }

    private fun granted() = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (!granted()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L).build()
        try { client.requestLocationUpdates(req, callback, ctx.mainLooper) } catch (_: Exception) {}
    }

    fun stop() { try { client.removeLocationUpdates(callback) } catch (_: Exception) {} }
}
