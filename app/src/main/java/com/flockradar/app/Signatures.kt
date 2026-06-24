package com.flockradar.app

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detection signatures, ported from NSM-Barii/flock-back. PASSIVE matching only:
 * we read names/MACs/UUIDs that devices broadcast. Nothing transmits or attacks.
 */
object Signatures {

    private val SSID = listOf(
        "flock", "fs ext battery", "penguin", "pigvision"
    )
    private val BLE_NAMES = listOf(
        "fs ext battery", "penguin", "flock", "pigvision"
    )
    private val MAC_PREFIXES = listOf(
        "58:8e:81", "cc:cc:cc", "ec:1b:bd", "90:35:ea", "04:0d:84",
        "f0:82:c0", "1c:34:f1", "38:5b:44", "94:34:69", "b4:e3:f9",
        "70:c9:4e", "3c:91:80", "d8:f3:bc", "80:30:49", "14:5a:fc",
        "74:4c:a1", "08:3a:88", "9c:2f:9d", "94:08:53", "e4:aa:ea"
    )
    val RAVEN_UUIDS = setOf(
        "0000180a-0000-1000-8000-00805f9b34fb",
        "00003100-0000-1000-8000-00805f9b34fb",
        "00003200-0000-1000-8000-00805f9b34fb",
        "00003300-0000-1000-8000-00805f9b34fb",
        "00003400-0000-1000-8000-00805f9b34fb",
        "00003500-0000-1000-8000-00805f9b34fb",
        "00001809-0000-1000-8000-00805f9b34fb",
        "00001819-0000-1000-8000-00805f9b34fb"
    )

    fun matchName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val n = name.lowercase()
        for (p in BLE_NAMES + SSID) if (n.contains(p)) return name
        return null
    }

    fun matchMac(mac: String?): String? {
        if (mac.isNullOrBlank()) return null
        val m = mac.lowercase().replace("-", ":")
        for (p in MAC_PREFIXES) if (m.startsWith(p)) return p
        return null
    }

    fun matchUuids(uuids: List<String>?): List<String> {
        if (uuids.isNullOrEmpty()) return emptyList()
        return uuids.map { it.lowercase() }.filter { RAVEN_UUIDS.contains(it) }
    }

    fun classifyBrand(name: String?, uuids: List<String>? = null): String {
        val n = (name ?: "").lowercase()
        if (matchUuids(uuids).isNotEmpty()) return "Raven"
        if (n.contains("penguin")) return "Penguin"
        if (n.contains("pigvision")) return "PigVision"
        if (n.contains("flock") || n.contains("fs ext battery")) return "Flock Safety"
        return "ALPR (unknown)"
    }

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a))
    }
}
