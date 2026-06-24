package com.flockradar.app

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/** Serves the dashboard + APIs to the phone's WebView and any LAN/hotspot client. */
class WebServer(private val ctx: Context, port: Int = 8080) : NanoHTTPD(port) {

    private val tilesDir = File(ctx.filesDir, "tiles_sat").apply { mkdirs() }
    private val ua = "flock-radar-app/1.0 (offline ALPR awareness)"
    @Volatile var dlMsg = "idle"
    @Volatile var dlRunning = false
    @Volatile var dlTiles = 0

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                uri == "/" || uri == "/index.html" -> asset("web/index.html", "text/html")
                uri == "/api/cameras"     -> cameras(session)
                uri == "/api/detections"  -> json(Store.detectionsGeoJson())
                uri == "/api/status"      -> status()
                uri == "/api/detect"      -> ingest(session)
                uri == "/api/download-area" -> downloadArea(session)
                uri.startsWith("/tiles/") -> tile(uri)
                else -> staticAsset(uri)
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "err: ${e.message}")
        }
    }

    // --- cameras (bbox-filtered) ---
    private fun cameras(s: IHTTPSession): Response {
        val b = s.parameters["bbox"]?.firstOrNull()?.split(",")?.mapNotNull { it.toDoubleOrNull() }
        if (b == null || b.size != 4) return json("""{"type":"FeatureCollection","features":[]}""")
        // bbox = south,west,north,east
        return json(Store.camerasInBbox(b[0], b[1], b[2], b[3]))
    }

    private fun status(): Response {
        val hasTiles = tilesDir.walkTopDown().any { it.isFile }
        val o = JSONObject()
            .put("cameras", if (Store.loaded) 1 else 0)
            .put("detections", Store.detections.size)
            .put("offline_tiles", hasTiles)
            .put("download", JSONObject().put("running", dlRunning).put("msg", dlMsg).put("tiles", dlTiles))
        return json(o.toString())
    }

    private fun ingest(s: IHTTPSession): Response {
        val body = HashMap<String, String>()
        s.parseBody(body)
        val d = JSONObject(body["postData"] ?: "{}")
        val match = d.optJSONObject("match") ?: JSONObject()
        Store.addDetection(
            d.optString("source", "rf"),
            d.optString("brand", "ALPR (unknown)"),
            d.optString("name", null), d.optString("mac", null),
            if (d.has("rssi") && !d.isNull("rssi")) d.optInt("rssi") else null,
            match,
            if (d.has("lat") && !d.isNull("lat")) d.optDouble("lat") else null,
            if (d.has("lon") && !d.isNull("lon")) d.optDouble("lon") else null
        )
        return json("""{"ok":true}""")
    }

    // --- offline tiles ---
    private fun tile(uri: String): Response {
        val parts = uri.removePrefix("/tiles/").removeSuffix(".png").split("/")
        if (parts.size != 3) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        val (z, x, y) = parts
        val f = File(tilesDir, "$z/$x/$y.png")
        val bytes = if (f.isFile) f.readBytes() else fetchTile(z.toInt(), x.toInt(), y.toInt())?.also {
            f.parentFile?.mkdirs(); f.writeBytes(it)
        }
        return if (bytes != null)
            newFixedLengthResponse(Response.Status.OK, "image/png", bytes.inputStream(), bytes.size.toLong())
        else newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
    }

    private fun fetchTile(z: Int, x: Int, y: Int): ByteArray? = try {
        // Esri World Imagery (satellite). Note tile order is z/y/x, not z/x/y.
        val url = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
        val c = (URL(url).openConnection() as HttpURLConnection)
        c.setRequestProperty("User-Agent", ua); c.connectTimeout = 8000; c.readTimeout = 12000
        if (c.responseCode == 200) {
            val bytes = c.inputStream.readBytes()
            if (bytes.size > 200) bytes else null   // skip tiny error/placeholder bodies
        } else null
    } catch (_: Exception) { null }

    // --- prefetch tiles for a bbox (cameras are bundled, so tiles only) ---
    private fun downloadArea(s: IHTTPSession): Response {
        if (dlRunning) return json("""{"ok":false,"msg":"busy"}""")
        val body = HashMap<String, String>(); s.parseBody(body)
        val p = JSONObject(body["postData"] ?: "{}")
        val south = p.optDouble("south"); val west = p.optDouble("west")
        val north = p.optDouble("north"); val east = p.optDouble("east")
        val zmin = p.optInt("zoom_min", 11); val zmax = p.optInt("zoom_max", 16)
        thread {
            dlRunning = true; dlTiles = 0; dlMsg = "caching tiles"
            try {
                for (z in zmin..zmax) {
                    val (x0, y0) = deg2tile(north, west, z)
                    val (x1, y1) = deg2tile(south, east, z)
                    for (xx in minOf(x0, x1)..maxOf(x0, x1)) for (yy in minOf(y0, y1)..maxOf(y0, y1)) {
                        val f = File(tilesDir, "$z/$xx/$yy.png")
                        if (!f.isFile) {
                            fetchTile(z, xx, yy)?.let { f.parentFile?.mkdirs(); f.writeBytes(it); Thread.sleep(120) }
                        }
                        dlTiles++
                    }
                }
                dlMsg = "done"
            } catch (e: Exception) { dlMsg = "error: ${e.message}" } finally { dlRunning = false }
        }
        return json("""{"ok":true,"msg":"started"}""")
    }

    private fun deg2tile(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
        val n = Math.pow(2.0, z.toDouble())
        val x = ((lon + 180.0) / 360.0 * n).toInt()
        val latR = Math.toRadians(lat)
        val y = ((1.0 - ln(tan(latR) + 1 / kotlin.math.cos(latR)) / PI) / 2.0 * n).toInt()
        return Pair(x, y)
    }

    // --- assets ---
    private fun staticAsset(uri: String): Response {
        val path = "web" + uri
        val mime = when {
            uri.endsWith(".js") -> "text/javascript"
            uri.endsWith(".css") -> "text/css"
            uri.endsWith(".webmanifest") || uri.endsWith(".json") -> "application/json"
            uri.endsWith(".png") -> "image/png"
            uri.endsWith(".svg") -> "image/svg+xml"
            else -> "text/plain"
        }
        return asset(path, mime)
    }

    private fun asset(path: String, mime: String): Response = try {
        val out = ByteArrayOutputStream()
        ctx.assets.open(path).use { it.copyTo(out) }
        val bytes = out.toByteArray()
        val r = newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
        if (path.endsWith("sw.js")) r.addHeader("Service-Worker-Allowed", "/")
        r
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
    }

    private fun json(s: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", s)
}
