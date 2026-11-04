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
    fun grant(pcId: ByteArray, name: String): ByteArray {
        val token = ByteArray(TOKEN_BYTES)
        random.nextBytes(token)
        val record = TrustedPc(pcId.copyOf(), name, sha256(token), System.currentTimeMillis())
        val next = readAll().filterNot { it.pcId.contentEquals(pcId) } + record
        writeAll(next)
        return token
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
                ),
            )
        }
        return items
    }

    private fun writeAll(items: List<TrustedPc>) {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("pcId", item.pcId.toHex())
                    .put("name", item.name)
                    .put("key", item.key.toHex())
                    .put("createdAt", item.createdAt),
            )
        }
        file.writeText(array.toString())
    }
}
