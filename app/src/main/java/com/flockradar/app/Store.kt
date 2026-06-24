package com.flockradar.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.CopyOnWriteArrayList

/** Singleton holding bundled cameras, live detections, and last GPS fix. */
object Store {

    // bundled cameras as parallel arrays (memory-lean for 110k points)
    private var camLat = DoubleArray(0)
    private var camLon = DoubleArray(0)
    private var camFlock = BooleanArray(0)
    @Volatile var loaded = false; private set

    val detections = CopyOnWriteArrayList<JSONObject>()

    @Volatile var gpsLat: Double? = null
    @Volatile var gpsLon: Double? = null

    fun loadCamerasCsv(reader: BufferedReader) {
        if (loaded) return
        val lat = ArrayList<Double>(120000)
        val lon = ArrayList<Double>(120000)
        val flk = ArrayList<Boolean>(120000)
        reader.useLines { lines ->
            var first = true
            for (line in lines) {
                if (first) { first = false; continue }   // header: id,lat,lon,flock
                val p = line.split(",")
                if (p.size < 4) continue
                val la = p[1].toDoubleOrNull() ?: continue
                val lo = p[2].toDoubleOrNull() ?: continue
                lat.add(la); lon.add(lo); flk.add(p[3].trim() == "1")
            }
        }
        camLat = lat.toDoubleArray()
        camLon = lon.toDoubleArray()
        camFlock = BooleanArray(flk.size) { flk[it] }
        loaded = true
    }

    /** GeoJSON of cameras inside the bbox, capped to [limit] features. */
    fun camerasInBbox(s: Double, w: Double, n: Double, e: Double, limit: Int = 4000): String {
        val feats = JSONArray()
        var count = 0
        for (i in camLat.indices) {
            val la = camLat[i]; val lo = camLon[i]
            if (la in s..n && lo in w..e) {
                val geom = JSONObject().put("type", "Point")
                    .put("coordinates", JSONArray().put(lo).put(la))
                val props = JSONObject().put("flock", if (camFlock[i]) 1 else 0)
                feats.put(JSONObject().put("type", "Feature")
                    .put("geometry", geom).put("properties", props))
                if (++count >= limit) break
            }
        }
        return JSONObject().put("type", "FeatureCollection").put("features", feats).toString()
    }

    /** Nearest Flock camera distance in meters from a point, or null. */
    fun nearestFlockM(lat: Double, lon: Double): Double? {
        var best = Double.MAX_VALUE
        for (i in camLat.indices) {
            if (!camFlock[i]) continue
            // cheap pre-filter: ~0.02 deg ~ 2km box before haversine
            if (kotlin.math.abs(camLat[i] - lat) > 0.02) continue
            if (kotlin.math.abs(camLon[i] - lon) > 0.02) continue
            val d = Signatures.haversineM(lat, lon, camLat[i], camLon[i])
            if (d < best) best = d
        }
        return if (best == Double.MAX_VALUE) null else best
    }

    fun addDetection(source: String, brand: String, name: String?, mac: String?,
                     rssi: Int?, match: JSONObject, lat: Double?, lon: Double?) {
        val located = lat != null && lon != null
        val geom = if (located)
            JSONObject().put("type", "Point")
                .put("coordinates", JSONArray().put(lon).put(lat))
        else JSONObject.NULL
        val props = JSONObject()
            .put("source", source).put("brand", brand)
            .put("name", name ?: JSONObject.NULL).put("mac", mac ?: JSONObject.NULL)
            .put("rssi", rssi ?: JSONObject.NULL).put("match", match)
            .put("located", located).put("live", true)
            .put("ts", System.currentTimeMillis())
        val feature = JSONObject().put("type", "Feature")
            .put("geometry", geom).put("properties", props)
        detections.add(feature)
    }

    fun detectionsGeoJson(): String {
        val arr = JSONArray()
        for (d in detections) arr.put(d)
        return JSONObject().put("type", "FeatureCollection").put("features", arr).toString()
    }
}
