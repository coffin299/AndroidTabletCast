package com.androidtabletcast

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

data class TrustedPc(
    val pcId: ByteArray,
    val name: String,
    val key: ByteArray,
    val createdAt: Long,
    val macs: List<ByteArray> = emptyList(),
)

class TrustStore(context: Context) {
    private val file = File(context.filesDir, "trusted-pcs.json")
    private val identity = File(context.filesDir, "tablet-id.txt")
    private val random = SecureRandom()

    fun tabletId(): ByteArray {
        if (identity.isFile) {
            val saved = identity.readText().trim()
            if (saved.length == ID_BYTES * 2) {
                return hexToBytes(saved)
            }
        }
        val created = ByteArray(ID_BYTES)
        random.nextBytes(created)
        identity.writeText(created.toHex())
        return created
    }

    @Synchronized
    fun list(): List<TrustedPc> {
        return readAll()
    }

    @Synchronized
    fun grant(pcId: ByteArray, name: String, macs: List<ByteArray> = emptyList()): ByteArray {
        val token = ByteArray(TOKEN_BYTES)
        random.nextBytes(token)
        val record = TrustedPc(pcId.copyOf(), name, sha256(token), System.currentTimeMillis(), usableMacs(macs))
        val next = readAll().filterNot { it.pcId.contentEquals(pcId) } + record
        writeAll(next)
        return token
    }

    @Synchronized
    fun rememberMacs(pcId: ByteArray, macs: List<ByteArray>) {
        val adding = usableMacs(macs)
        if (adding.isEmpty()) {
            return
        }
        val current = readAll()
        val found = current.firstOrNull { it.pcId.contentEquals(pcId) } ?: return
        val merged = mergeMacs(found.macs, adding)
        if (merged.size == found.macs.size) {
            return
        }
        writeAll(current.map { if (it.pcId.contentEquals(pcId)) it.copy(macs = merged) else it })
    }

    @Synchronized
    fun matchesMac(macs: List<ByteArray>): Boolean {
        val wanted = usableMacs(macs)
        if (wanted.isEmpty()) {
            return false
        }
        return readAll().any { record ->
            record.macs.any { saved -> wanted.any { it.contentEquals(saved) } }
        }
    }

    @Synchronized
    fun revoke(pcId: ByteArray) {
        writeAll(readAll().filterNot { it.pcId.contentEquals(pcId) })
    }

    @Synchronized
    fun find(pcId: ByteArray): TrustedPc? {
        return readAll().firstOrNull { it.pcId.contentEquals(pcId) }
    }

    fun verify(pcId: ByteArray, nonce: ByteArray, hmac: ByteArray): Boolean {
        val record = find(pcId) ?: return false
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(record.key, "HmacSHA256"))
        return constantTimeEquals(mac.doFinal(nonce), hmac)
    }

    private fun readAll(): List<TrustedPc> {
        if (!file.isFile) {
            return emptyList()
        }
        val array = JSONArray(file.readText())
        val items = ArrayList<TrustedPc>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.getJSONObject(index)
            items.add(
                TrustedPc(
                    pcId = hexToBytes(obj.getString("pcId")),
                    name = obj.getString("name"),
                    key = hexToBytes(obj.getString("key")),
                    createdAt = obj.getLong("createdAt"),
                    macs = readMacs(obj),
                ),
            )
        }
        return items
    }

    private fun readMacs(obj: JSONObject): List<ByteArray> {
        val array = obj.optJSONArray("macs") ?: return emptyList()
        val macs = ArrayList<ByteArray>(array.length())
        for (index in 0 until array.length()) {
            val parsed = parseMac(array.optString(index))
            if (parsed != null) {
                macs.add(parsed)
            }
        }
        return macs
    }

    private fun usableMacs(macs: List<ByteArray>): List<ByteArray> {
        return mergeMacs(emptyList(), macs)
    }

    private fun mergeMacs(current: List<ByteArray>, adding: List<ByteArray>): List<ByteArray> {
        val merged = current.map { it.copyOf() }.toMutableList()
        for (mac in adding) {
            val normal = normalizeMac(mac) ?: continue
            if (merged.none { it.contentEquals(normal) }) {
                merged.add(normal)
            }
        }
        return merged
    }

    private fun writeAll(items: List<TrustedPc>) {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("pcId", item.pcId.toHex())
                    .put("name", item.name)
                    .put("key", item.key.toHex())
                    .put("createdAt", item.createdAt)
                    .put("macs", JSONArray().apply {
                        for (mac in item.macs) {
                            put(mac.toHex())
                        }
                    }),
            )
        }
        file.writeText(array.toString())
    }
}
