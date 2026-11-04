package com.androidtabletcast

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** TCP 上のフレームは type 1 バイト + 長さ 4 バイト（ビッグエンディアン）+ payload。 */
const val PROTOCOL_VERSION = 1
const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
const val MAX_NAME_BYTES = 64
const val TCP_PORT = 39721
const val UDP_BEACON_PORT = 39720
const val ID_BYTES = 16
const val TOKEN_BYTES = 32
const val NONCE_BYTES = 16
const val HMAC_BYTES = 32

const val TYPE_HELLO = 1
const val TYPE_AUTH = 2
const val TYPE_AUTH_RESULT = 3
const val TYPE_TRUST_GRANT = 4
const val TYPE_TRUST_REQUEST = 5
const val TYPE_VIDEO_CONFIG = 16
const val TYPE_VIDEO = 17
const val TYPE_AUDIO_CONFIG = 18
const val TYPE_AUDIO = 19
const val TYPE_INPUT_TOUCH = 32
const val TYPE_INPUT_KEY = 33
const val TYPE_INPUT_SCROLL = 34
const val TYPE_STREAM_CONFIG = 48
const val TYPE_REQUEST_KEYFRAME = 49
const val TYPE_PING = 126
const val TYPE_PONG = 127

const val AUTH_OK = 0
const val AUTH_BAD_PIN = 1
const val AUTH_UNTRUSTED = 2
const val AUTH_TRUST_MODE_OFF = 3
const val AUTH_LOCKED = 4

const val AUTH_METHOD_PIN = 0
const val AUTH_METHOD_TRUST = 1

const val TOUCH_DOWN = 0
const val TOUCH_MOVE = 1
const val TOUCH_UP = 2
const val TOUCH_CANCEL = 3

const val PRESET_LOW_LATENCY = 0
const val PRESET_BALANCED = 1
const val PRESET_HIGH_QUALITY = 2

sealed class Packet {
    abstract val type: Int

    data class Hello(
        val version: Int,
        val tabletId: ByteArray,
        val trustMode: Boolean,
        val nonce: ByteArray,
        val videoWidth: Int,
        val videoHeight: Int,
        val deviceWidth: Int,
        val deviceHeight: Int,
        val name: String,
    ) : Packet() {
        override val type: Int = TYPE_HELLO
    }

    data class AuthPin(val pin: String) : Packet() {
        override val type: Int = TYPE_AUTH
    }

    data class AuthTrust(val pcId: ByteArray, val hmac: ByteArray) : Packet() {
        override val type: Int = TYPE_AUTH
    }

    data class AuthResult(val code: Int) : Packet() {
        override val type: Int = TYPE_AUTH_RESULT
    }

    data class TrustGrant(val tabletId: ByteArray, val token: ByteArray) : Packet() {
        override val type: Int = TYPE_TRUST_GRANT
    }

    data class TrustRequest(val pcId: ByteArray, val name: String) : Packet() {
        override val type: Int = TYPE_TRUST_REQUEST
    }

    data class VideoConfig(val width: Int, val height: Int, val annexB: ByteArray) : Packet() {
        override val type: Int = TYPE_VIDEO_CONFIG
    }

    data class Video(val ptsUs: Long, val keyframe: Boolean, val annexB: ByteArray) : Packet() {
        override val type: Int = TYPE_VIDEO
    }

    data class AudioConfig(val sampleRate: Int, val channels: Int, val specificConfig: ByteArray) : Packet() {
        override val type: Int = TYPE_AUDIO_CONFIG
    }

    data class Audio(val ptsUs: Long, val aac: ByteArray) : Packet() {
        override val type: Int = TYPE_AUDIO
    }

    data class InputTouch(val action: Int, val xNorm: Int, val yNorm: Int) : Packet() {
        override val type: Int = TYPE_INPUT_TOUCH
    }

    data class InputKey(val action: Int, val keyCode: Int, val meta: Int) : Packet() {
        override val type: Int = TYPE_INPUT_KEY
    }

    data class InputScroll(val xNorm: Int, val yNorm: Int, val delta: Int) : Packet() {
        override val type: Int = TYPE_INPUT_SCROLL
    }

    data class StreamConfig(val preset: Int) : Packet() {
        override val type: Int = TYPE_STREAM_CONFIG
    }

    data object RequestKeyframe : Packet() {
        override val type: Int = TYPE_REQUEST_KEYFRAME
    }

    data class Ping(val clientUnixMs: Long) : Packet() {
        override val type: Int = TYPE_PING
    }

    data class Pong(val clientUnixMs: Long, val serverUnixMs: Long) : Packet() {
        override val type: Int = TYPE_PONG
    }
}

class ProtocolException(message: String) : Exception(message)

class ByteWriter {
    private val out = ByteArrayOutputStream()

    fun u8(value: Int) {
        out.write(value and 0xFF)
    }

    fun u16(value: Int) {
        // 受信側がビッグエンディアンで読むので、上位バイトから書く。
        u8(value ushr 8)
        u8(value)
    }

    fun u32(value: Long) {
        u8((value ushr 24).toInt())
        u8((value ushr 16).toInt())
        u8((value ushr 8).toInt())
        u8(value.toInt())
    }

    fun u64(value: Long) {
        u32(value ushr 32)
        u32(value and 0xFFFFFFFFL)
    }

    fun bytes(value: ByteArray) {
        out.write(value)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

class ByteReader(private val data: ByteArray) {
    private var offset = 0

    fun remaining(): Int = data.size - offset

    fun u8(): Int {
        if (offset >= data.size) {
            throw ProtocolException("payload が途中で終わっています")
        }
        return data[offset++].toInt() and 0xFF
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun i16(): Int {
        val value = u16()
        // 符号付き 16bit に戻す。スクロール量は負になり得る。
        return if (value and 0x8000 != 0) value - 0x10000 else value
    }

    fun u32(): Long {
        val value = (u8().toLong() shl 24) or
            (u8().toLong() shl 16) or
            (u8().toLong() shl 8) or
            u8().toLong()
        return value and 0xFFFFFFFFL
    }

    fun u64(): Long = (u32() shl 32) or u32()

    fun bytes(length: Int): ByteArray {
        if (length < 0 || remaining() < length) {
            throw ProtocolException("payload の長さが足りません")
        }
        val slice = data.copyOfRange(offset, offset + length)
        offset += length
        return slice
    }

    fun rest(): ByteArray = bytes(remaining())

    fun expectEnded() {
        if (remaining() != 0) {
            throw ProtocolException("payload に余りがあります")
        }
    }
}

object Protocol {
    fun encode(packet: Packet): ByteArray {
        val payload = payloadOf(packet)
        if (payload.size > MAX_PAYLOAD_BYTES) {
            throw ProtocolException("payload が上限を超えています")
        }
        val writer = ByteWriter()
        writer.u8(packet.type)
        writer.u32(payload.size.toLong())
        writer.bytes(payload)
        return writer.toByteArray()
    }

    fun decode(frame: ByteArray): Packet {
        if (frame.size < 5) {
            throw ProtocolException("ヘッダが足りません")
        }
        val type = frame[0].toInt() and 0xFF
        val length = ((frame[1].toLong() and 0xFF) shl 24) or
            ((frame[2].toLong() and 0xFF) shl 16) or
            ((frame[3].toLong() and 0xFF) shl 8) or
            (frame[4].toLong() and 0xFF)
        if (length > MAX_PAYLOAD_BYTES) {
            throw ProtocolException("payload が上限を超えています")
        }
        if (frame.size != 5 + length.toInt()) {
            throw ProtocolException("フレーム長が一致しません")
        }
        val payload = frame.copyOfRange(5, frame.size)
        return decodePayload(type, payload)
    }

    fun decodePayload(type: Int, payload: ByteArray): Packet {
        val reader = ByteReader(payload)
        val packet = when (type) {
            TYPE_HELLO -> readHello(reader)
            TYPE_AUTH -> readAuth(reader)
            TYPE_AUTH_RESULT -> Packet.AuthResult(reader.u8())
            TYPE_TRUST_GRANT -> Packet.TrustGrant(reader.bytes(ID_BYTES), reader.bytes(TOKEN_BYTES))
            TYPE_TRUST_REQUEST -> readTrustRequest(reader)
            TYPE_VIDEO_CONFIG -> Packet.VideoConfig(reader.u16(), reader.u16(), reader.rest())
            TYPE_VIDEO -> Packet.Video(reader.u64(), reader.u8() != 0, reader.rest())
            TYPE_AUDIO_CONFIG -> Packet.AudioConfig(reader.u32().toInt(), reader.u8(), reader.rest())
            TYPE_AUDIO -> Packet.Audio(reader.u64(), reader.rest())
            TYPE_INPUT_TOUCH -> Packet.InputTouch(reader.u8(), reader.u16(), reader.u16())
            TYPE_INPUT_KEY -> Packet.InputKey(reader.u8(), reader.u16(), reader.u32().toInt())
            TYPE_INPUT_SCROLL -> Packet.InputScroll(reader.u16(), reader.u16(), reader.i16())
            TYPE_STREAM_CONFIG -> Packet.StreamConfig(reader.u8())
            TYPE_REQUEST_KEYFRAME -> Packet.RequestKeyframe
            TYPE_PING -> Packet.Ping(reader.u64())
            TYPE_PONG -> Packet.Pong(reader.u64(), reader.u64())
            else -> throw ProtocolException("未知の type: $type")
        }
        if (type != TYPE_VIDEO_CONFIG && type != TYPE_VIDEO && type != TYPE_AUDIO_CONFIG && type != TYPE_AUDIO) {
            reader.expectEnded()
        }
        return packet
    }

    private fun readHello(reader: ByteReader): Packet.Hello {
        val version = reader.u16()
        if (version != PROTOCOL_VERSION) {
            throw ProtocolException("未対応のプロトコル版です")
        }
        return Packet.Hello(
            version = version,
            tabletId = reader.bytes(ID_BYTES),
            trustMode = reader.u8() != 0,
            nonce = reader.bytes(NONCE_BYTES),
            videoWidth = reader.u16(),
            videoHeight = reader.u16(),
            deviceWidth = reader.u16(),
            deviceHeight = reader.u16(),
            name = readName(reader),
        )
    }

    private fun readAuth(reader: ByteReader): Packet {
        return when (val method = reader.u8()) {
            AUTH_METHOD_PIN -> Packet.AuthPin(readName(reader))
            AUTH_METHOD_TRUST -> Packet.AuthTrust(reader.bytes(ID_BYTES), reader.bytes(HMAC_BYTES))
            else -> throw ProtocolException("未知の認証方式です: $method")
        }
    }

    private fun readTrustRequest(reader: ByteReader): Packet.TrustRequest {
        return Packet.TrustRequest(reader.bytes(ID_BYTES), readName(reader))
    }

    private fun readName(reader: ByteReader): String {
        val length = reader.u8()
        if (length > MAX_NAME_BYTES) {
            throw ProtocolException("名前が長すぎます")
        }
        return String(reader.bytes(length), StandardCharsets.UTF_8)
    }

    private fun payloadOf(packet: Packet): ByteArray {
        val writer = ByteWriter()
        when (packet) {
            is Packet.Hello -> {
                writer.u16(packet.version)
                requireFixed(packet.tabletId, ID_BYTES, "tabletId")
                writer.bytes(packet.tabletId)
                writer.u8(if (packet.trustMode) 1 else 0)
                requireFixed(packet.nonce, NONCE_BYTES, "nonce")
                writer.bytes(packet.nonce)
                writer.u16(packet.videoWidth)
                writer.u16(packet.videoHeight)
                writer.u16(packet.deviceWidth)
                writer.u16(packet.deviceHeight)
                writeName(writer, packet.name)
            }
            is Packet.AuthPin -> {
                writer.u8(AUTH_METHOD_PIN)
                writeName(writer, packet.pin)
            }
            is Packet.AuthTrust -> {
                writer.u8(AUTH_METHOD_TRUST)
                requireFixed(packet.pcId, ID_BYTES, "pcId")
                writer.bytes(packet.pcId)
                requireFixed(packet.hmac, HMAC_BYTES, "hmac")
                writer.bytes(packet.hmac)
            }
            is Packet.AuthResult -> writer.u8(packet.code)
            is Packet.TrustGrant -> {
                requireFixed(packet.tabletId, ID_BYTES, "tabletId")
                writer.bytes(packet.tabletId)
                requireFixed(packet.token, TOKEN_BYTES, "token")
                writer.bytes(packet.token)
            }
            is Packet.TrustRequest -> {
                requireFixed(packet.pcId, ID_BYTES, "pcId")
                writer.bytes(packet.pcId)
                writeName(writer, packet.name)
            }
            is Packet.VideoConfig -> {
                writer.u16(packet.width)
                writer.u16(packet.height)
                writer.bytes(packet.annexB)
            }
            is Packet.Video -> {
                writer.u64(packet.ptsUs)
                writer.u8(if (packet.keyframe) 1 else 0)
                writer.bytes(packet.annexB)
            }
            is Packet.AudioConfig -> {
                writer.u32(packet.sampleRate.toLong())
                writer.u8(packet.channels)
                writer.bytes(packet.specificConfig)
            }
            is Packet.Audio -> {
                writer.u64(packet.ptsUs)
                writer.bytes(packet.aac)
            }
            is Packet.InputTouch -> {
                writer.u8(packet.action)
                writer.u16(packet.xNorm)
                writer.u16(packet.yNorm)
            }
            is Packet.InputKey -> {
                writer.u8(packet.action)
                writer.u16(packet.keyCode)
                writer.u32(packet.meta.toLong() and 0xFFFFFFFFL)
            }
            is Packet.InputScroll -> {
                writer.u16(packet.xNorm)
                writer.u16(packet.yNorm)
                writer.u16(packet.delta)
            }
            is Packet.StreamConfig -> writer.u8(packet.preset)
            Packet.RequestKeyframe -> Unit
            is Packet.Ping -> writer.u64(packet.clientUnixMs)
            is Packet.Pong -> {
                writer.u64(packet.clientUnixMs)
                writer.u64(packet.serverUnixMs)
            }
        }
        return writer.toByteArray()
    }

    private fun writeName(writer: ByteWriter, value: String) {
        val bytes = utf8Trunc(value, MAX_NAME_BYTES).toByteArray(StandardCharsets.UTF_8)
        writer.u8(bytes.size)
        writer.bytes(bytes)
    }

    private fun requireFixed(value: ByteArray, size: Int, label: String) {
        if (value.size != size) {
            throw ProtocolException("$label は $size バイトである必要があります")
        }
    }
}

class PacketReader(private val input: InputStream) {
    fun read(): Packet {
        val header = readExact(5)
        val length = ((header[1].toLong() and 0xFF) shl 24) or
            ((header[2].toLong() and 0xFF) shl 16) or
            ((header[3].toLong() and 0xFF) shl 8) or
            (header[4].toLong() and 0xFF)
        if (length > MAX_PAYLOAD_BYTES) {
            throw ProtocolException("payload が上限を超えています")
        }
        val payload = if (length == 0L) ByteArray(0) else readExact(length.toInt())
        val frame = ByteArray(5 + payload.size)
        header.copyInto(frame)
        payload.copyInto(frame, 5)
        return Protocol.decode(frame)
    }

    private fun readExact(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(buffer, offset, length - offset)
            if (count < 0) {
                throw ProtocolException("接続が途中で切れました")
            }
            offset += count
        }
        return buffer
    }
}

fun utf8Trunc(value: String, maxBytes: Int): String {
    var used = 0
    val out = StringBuilder()
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val piece = String(Character.toChars(codePoint))
        val size = piece.toByteArray(StandardCharsets.UTF_8).size
        // 上限をまたぐ文字は落とす。途中のバイトで切ると文字化けする。
        if (used + size > maxBytes) {
            break
        }
        out.append(piece)
        used += size
        index += Character.charCount(codePoint)
    }
    return out.toString()
}

fun sha256(value: ByteArray): ByteArray {
    return java.security.MessageDigest.getInstance("SHA-256").digest(value)
}

/** 再接続でトークン自体は送らず、SHA-256(token) を鍵にした HMAC だけを送る。 */
fun trustHmac(token: ByteArray, nonce: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(sha256(token), "HmacSHA256"))
    return mac.doFinal(nonce)
}

fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
    if (left.size != right.size) {
        return false
    }
    var diff = 0
    for (index in left.indices) {
        diff = diff or (left[index].toInt() xor right[index].toInt())
    }
    return diff == 0
}

data class FrameSize(val width: Int, val height: Int)

fun scaledSize(deviceWidth: Int, deviceHeight: Int, longSideCap: Int): FrameSize {
    val longSide = maxOf(deviceWidth, deviceHeight).coerceAtLeast(1)
    val cap = longSideCap.coerceAtLeast(16)
    val scale = minOf(1.0, cap.toDouble() / longSide.toDouble())
    // エンコーダは 16 の倍数を要求することが多いので、端数は切り捨てる。
    var width = (deviceWidth * scale).toInt() / 16 * 16
    var height = (deviceHeight * scale).toInt() / 16 * 16
    if (width < 16) {
        width = 16
    }
    if (height < 16) {
        height = 16
    }
    return FrameSize(width, height)
}

data class StreamPreset(val id: Int, val longSide: Int, val fps: Int, val bitrate: Int)

fun presetOf(id: Int): StreamPreset {
    return when (id) {
        PRESET_LOW_LATENCY -> StreamPreset(PRESET_LOW_LATENCY, 1280, 30, 4_000_000)
        PRESET_BALANCED -> StreamPreset(PRESET_BALANCED, 1920, 60, 10_000_000)
        else -> StreamPreset(PRESET_HIGH_QUALITY, 2560, 60, 20_000_000)
    }
}

class AuthLockout(
    private val limit: Int = 5,
    private val lockMillis: Long = 30_000,
) {
    private data class Fail(var count: Int, var lockedUntil: Long)

    private val fails = HashMap<String, Fail>()

    fun isLocked(ip: String, now: Long): Boolean {
        val fail = fails[ip] ?: return false
        if (fail.lockedUntil > now) {
            return true
        }
        if (fail.lockedUntil != 0L) {
            // ロック期限が過ぎたら回数も戻す。
            fails.remove(ip)
        }
        return false
    }

    fun recordFailure(ip: String, now: Long) {
        val fail = fails.getOrPut(ip) { Fail(0, 0) }
        fail.count += 1
        if (fail.count >= limit) {
            fail.lockedUntil = now + lockMillis
        }
    }

    fun clear(ip: String) {
        fails.remove(ip)
    }
}

fun toAnnexB(data: ByteArray): ByteArray {
    if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
        (data[2] == 1.toByte() || (data.size >= 4 && data[2] == 0.toByte() && data[3] == 1.toByte()))
    ) {
        return data
    }
    val out = ByteArrayOutputStream()
    var index = 0
    val start = byteArrayOf(0, 0, 0, 1)
    while (index + 4 <= data.size) {
        val length = ((data[index].toLong() and 0xFF) shl 24) or
            ((data[index + 1].toLong() and 0xFF) shl 16) or
            ((data[index + 2].toLong() and 0xFF) shl 8) or
            (data[index + 3].toLong() and 0xFF)
        index += 4
        if (length <= 0 || index + length > data.size) {
            // 長さ付きとみなせない場合は、そのまま渡してデコーダに判断させる。
            return data
        }
        out.write(start)
        out.write(data, index, length.toInt())
        index += length.toInt()
    }
    val converted = out.toByteArray()
    return if (converted.isEmpty()) data else converted
}

object Beacon {
    val MAGIC = byteArrayOf('A'.code.toByte(), 'T'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())

    fun encode(tcpPort: Int, tabletId: ByteArray, name: String): ByteArray {
        val writer = ByteWriter()
        writer.bytes(MAGIC)
        writer.u16(tcpPort)
        writer.bytes(tabletId.copyOf(ID_BYTES))
        val nameBytes = utf8Trunc(name, MAX_NAME_BYTES).toByteArray(StandardCharsets.UTF_8)
        writer.u8(nameBytes.size)
        writer.bytes(nameBytes)
        return writer.toByteArray()
    }

    fun decode(packet: ByteArray): Triple<Int, ByteArray, String>? {
        if (packet.size < 4 + 2 + ID_BYTES + 1) {
            return null
        }
        for (index in MAGIC.indices) {
            if (packet[index] != MAGIC[index]) {
                return null
            }
        }
        val reader = ByteReader(packet.copyOfRange(4, packet.size))
        val port = reader.u16()
        val tabletId = reader.bytes(ID_BYTES)
        val nameLength = reader.u8()
        if (reader.remaining() < nameLength) {
            return null
        }
        val name = String(reader.bytes(nameLength), StandardCharsets.UTF_8)
        return Triple(port, tabletId, name)
    }
}

fun characterForKey(keyCode: Int, meta: Int): Char? {
    val ctrlOn = meta and 0x1000 != 0
    val altOn = meta and 0x02 != 0
    if (ctrlOn || altOn) {
        return null
    }
    val shift = meta and 0x1 != 0
    if (keyCode in 29..54) {
        val base = 'a' + (keyCode - 29)
        return if (shift) base.uppercaseChar() else base
    }
    if (keyCode in 7..16) {
        val digit = if (keyCode == 7) '0' else ('1' + (keyCode - 8))
        return digit
    }
    if (keyCode == 62) {
        return ' '
    }
    return null
}
