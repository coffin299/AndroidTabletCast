package com.androidtabletcast

import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

/** Android が隠すプレースホルダ。これと全ゼロは機器の識別に使わない。 */
private val HIDDEN_MAC = byteArrayOf(0x02, 0, 0, 0, 0, 0)

fun normalizeMac(mac: ByteArray?): ByteArray? {
    if (mac == null || mac.size != 6) {
        return null
    }
    if (mac.all { it == 0.toByte() } || mac.contentEquals(HIDDEN_MAC)) {
        return null
    }
    return mac.copyOf()
}

fun formatMac(mac: ByteArray): String {
    return mac.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
}

fun parseMac(text: String): ByteArray? {
    val hex = text.trim().replace(":", "").replace("-", "")
    if (hex.length != 12) {
        return null
    }
    return try {
        normalizeMac(hexToBytes(hex))
    } catch (_: Exception) {
        null
    }
}

/** 同じ LAN の相手 MAC を ARP から取る。Wi-Fi のランダム MAC でも、そのネットワークでは固定になる。 */
fun arpMac(address: InetAddress): ByteArray? {
    val host = (address as? Inet4Address)?.hostAddress ?: return null
    val file = File("/proc/net/arp")
    if (!file.isFile) {
        return null
    }
    val lines = try {
        file.readLines()
    } catch (_: Exception) {
        return null
    }
    for (line in lines.drop(1)) {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 4 || parts[0] != host) {
            continue
        }
        val flags = parts[2].removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: continue
        // 0x2 が立っていない行は、まだ解決途中の ARP なので使わない。
        if (flags and 0x2 == 0) {
            continue
        }
        return parseMac(parts[3])
    }
    return null
}

fun waitForArpMac(address: InetAddress): ByteArray? {
    repeat(5) {
        arpMac(address)?.let { return it }
        try {
            Thread.sleep(40)
        } catch (_: InterruptedException) {
            return arpMac(address)
        }
    }
    return arpMac(address)
}
