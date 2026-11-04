package com.androidtabletcast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class ProtocolTest {
    @Test
    fun fixtureRoundTrip() {
        val bytes = fixture("packets.bin")
        val reader = PacketReader(ByteArrayInputStream(bytes))
        val packets = ArrayList<Packet>()
        val encoded = java.io.ByteArrayOutputStream()
        while (true) {
            val packet = try {
                reader.read()
            } catch (error: ProtocolException) {
                if (error.message == "接続が途中で切れました") {
                    break
                }
                throw error
            }
            packets.add(packet)
            encoded.write(Protocol.encode(packet))
        }
        assertEquals(21, packets.size)
        assertArrayEquals(bytes, encoded.toByteArray())

        val hello = packets[0] as Packet.Hello
        assertEquals(1, hello.version)
        assertTrue(hello.trustMode)
        assertEquals(1920, hello.videoWidth)
        assertEquals(1080, hello.videoHeight)
        assertEquals(2560, hello.deviceWidth)
        assertEquals(1600, hello.deviceHeight)
        assertEquals("Tab", hello.name)
        assertEquals("123456", (packets[1] as Packet.AuthPin).pin)

        val trust = packets[2] as Packet.AuthTrust
        val token = ByteArray(32) { index -> (index + 1).toByte() }
        assertArrayEquals(trustHmac(token, hello.nonce), trust.hmac)
        assertEquals(AUTH_OK, (packets[3] as Packet.AuthResult).code)
        assertEquals(AUTH_BAD_PIN, (packets[4] as Packet.AuthResult).code)
        assertEquals(AUTH_UNTRUSTED, (packets[5] as Packet.AuthResult).code)
        assertEquals(AUTH_TRUST_MODE_OFF, (packets[6] as Packet.AuthResult).code)
        assertEquals(AUTH_LOCKED, (packets[7] as Packet.AuthResult).code)
        assertEquals("Desk", (packets[8] as Packet.TrustRequest).name)
        assertEquals(32, (packets[9] as Packet.TrustGrant).token.size)
        assertEquals(1920, (packets[10] as Packet.VideoConfig).width)
        val video = packets[11] as Packet.Video
        assertEquals(1000L, video.ptsUs)
        assertTrue(video.keyframe)
        assertEquals(48000, (packets[12] as Packet.AudioConfig).sampleRate)
        assertEquals(2000L, (packets[13] as Packet.Audio).ptsUs)
        val touch = packets[14] as Packet.InputTouch
        assertEquals(TOUCH_DOWN, touch.action)
        assertEquals(32768, touch.xNorm)
        assertEquals(100, touch.yNorm)
        assertEquals(29, (packets[15] as Packet.InputKey).keyCode)
        assertEquals(-120, (packets[16] as Packet.InputScroll).delta)
        assertEquals(PRESET_HIGH_QUALITY, (packets[17] as Packet.StreamConfig).preset)
        assertTrue(packets[18] is Packet.RequestKeyframe)
        assertEquals(123456789L, (packets[19] as Packet.Ping).clientUnixMs)
        val pong = packets[20] as Packet.Pong
        assertEquals(123456789L, pong.clientUnixMs)
        assertEquals(123456800L, pong.serverUnixMs)
    }

    @Test
    fun beaconMatchesFixture() {
        val bytes = fixture("beacon.bin")
        val decoded = Beacon.decode(bytes)
        assertTrue(decoded != null)
        assertEquals(TCP_PORT, decoded!!.first)
        assertEquals("Tab", decoded.third)
        assertArrayEquals(bytes, Beacon.encode(decoded.first, decoded.second, decoded.third))
    }

    @Test
    fun scaleLongSideAndAlignment() {
        assertEquals(FrameSize(1920, 1200), scaledSize(2560, 1600, 1920))
        assertEquals(FrameSize(2000, 992), scaledSize(2000, 1000, 2560))
    }

    @Test
    fun lockoutExpires() {
        val lockout = AuthLockout(limit = 5, lockMillis = 30_000)
        repeat(4) { lockout.recordFailure("10.0.0.2", 1_000) }
        assertFalse(lockout.isLocked("10.0.0.2", 1_000))
        lockout.recordFailure("10.0.0.2", 1_000)
        assertTrue(lockout.isLocked("10.0.0.2", 1_000))
        assertFalse(lockout.isLocked("10.0.0.2", 31_001))
    }

    @Test
    fun truncatesWithoutSplittingEmoji() {
        val text = "あ" + "😀" + "い"
        val truncated = utf8Trunc(text, 3)
        assertEquals("あ", truncated)
    }

    @Test
    fun keyCharacterMapping() {
        assertEquals('a', characterForKey(29, 0))
        assertEquals('A', characterForKey(29, 1))
        assertEquals(null, characterForKey(29, 0x1000))
    }

    @Test
    fun lengthPrefixedNalBecomesAnnexB() {
        val avcc = byteArrayOf(0, 0, 0, 1, 0x65)
        val converted = toAnnexB(avcc)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65), converted)
        val raw = byteArrayOf(0, 0, 0, 2, 0x65, 0x01)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x01), toAnnexB(raw))
    }

    private fun fixture(name: String): ByteArray {
        val start = System.getProperty("user.dir") ?: "."
        var dir: File? = File(start)
        repeat(8) {
            val current = dir ?: return@repeat
            val candidate = File(current, "protocol/fixtures/$name")
            if (candidate.isFile) {
                return candidate.readBytes()
            }
            dir = current.parentFile
        }
        throw IllegalStateException("fixture が見つかりません: $name")
    }
}
