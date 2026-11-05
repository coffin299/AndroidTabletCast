package com.androidtabletcast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface

class VideoPipeline(
    private val projection: MediaProjection,
    private val deviceWidth: Int,
    private val deviceHeight: Int,
    private val densityDpi: Int,
    private val codecPreference: Int,
    private val send: (Packet) -> Unit,
) {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    var preset: StreamPreset = presetOf(PRESET_HIGH_QUALITY)
        private set

    @Volatile
    private var lastConfig: Packet.VideoConfig? = null

    private var activeWidth = 0
    private var activeHeight = 0
    private var codecId = CODEC_AVC

    fun start(presetId: Int): Boolean {
        synchronized(lock) {
            stopLocked()
            return startEncoderLocked(presetId)
        }
    }

    fun replayConfig() {
        val config = lastConfig
        if (config != null) {
            send(config)
        }
        val current = display ?: return
        val input = surface ?: return
        if (activeWidth <= 0 || activeHeight <= 0) {
            return
        }
        try {
            // 先に作った仮想ディスプレイが黒いまま固まる端末があるので、接続時に面を付け直す。
            current.setSurface(input)
            current.resize(activeWidth, activeHeight, densityDpi.coerceAtLeast(1))
        } catch (_: Exception) {
        }
    }

    fun requestKeyframe() {
        val encoder = codec ?: return
        val params = Bundle()
        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        try {
            encoder.setParameters(params)
        } catch (_: Exception) {
            // 機種によっては同期フレーム要求を拒否する。次の定期 IDR に任せる。
        }
    }

    fun changePreset(presetId: Int): Boolean {
        // 接続直後の同じ画質指定で仮想ディスプレイを作り直すと、端末によってはプロセスごと落ちる。
        if (running && preset.id == presetId) {
            return true
        }
        synchronized(lock) {
            // Android 14 以降は同じ MediaProjection で VirtualDisplay を作り直せない。
            // 画面投影は維持し、エンコーダの Surface だけを差し替える。
            val previousPreset = preset.id
            stopEncoderLocked()
            if (startEncoderLocked(presetId)) {
                return true
            }
            Log.w(TAG, "preset $presetId failed; restoring preset $previousPreset")
            if (!startEncoderLocked(previousPreset)) {
                Log.e(TAG, "failed to restore preset $previousPreset")
            }
            return false
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        stopEncoderLocked()
        try {
            display?.release()
        } catch (_: Exception) {
        }
        display = null
    }

    private fun stopEncoderLocked() {
        running = false
        activeWidth = 0
        activeHeight = 0
        val worker = thread
        thread = null
        val oldCodec = codec
        codec = null
        try {
            worker?.join(250)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            oldCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            worker?.join(250)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            display?.setSurface(null)
        } catch (_: Exception) {
        }
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        surface = null
        try {
            oldCodec?.release()
        } catch (_: Exception) {
        }
        lastConfig = null
    }

    private fun startEncoderLocked(presetId: Int): Boolean {
        val chosen = presetOf(presetId)
        preset = chosen
        val requested = scaledSize(deviceWidth, deviceHeight, chosen.longSide)
        val rates = listOf(chosen.bitrate, chosen.bitrate / 2).distinct()
        for (rate in rates) {
            val attempt = chosen.copy(bitrate = rate)
            if (tryStart(requested, attempt)) {
                preset = attempt
                return true
            }
        }
        val sizes = listOf(
            scaledSize(deviceWidth, deviceHeight, 1280),
            scaledSize(deviceWidth, deviceHeight, 960),
        ).distinctBy { size -> size.width to size.height }
        for (size in sizes) {
            if (size.width == requested.width && size.height == requested.height) {
                continue
            }
            if (tryStart(size, chosen)) {
                return true
            }
        }
        running = false
        return false
    }

    private fun tryStart(size: FrameSize, chosen: StreamPreset): Boolean {
        val opened = openEncoder(size, chosen) ?: return false
        val encoder = opened.first
        val fitted = opened.second
        var input: Surface? = null
        var createdDisplay: android.hardware.display.VirtualDisplay? = null
        try {
            input = encoder.createInputSurface()
            encoder.start()
            val dpi = densityDpi.coerceAtLeast(1)
            val mirror = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            val currentDisplay = display
            val targetDisplay = if (currentDisplay != null) {
                currentDisplay.resize(fitted.width, fitted.height, dpi)
                currentDisplay.setSurface(input)
                currentDisplay
            } else {
                val created = projection.createVirtualDisplay(
                    "atcast",
                    fitted.width,
                    fitted.height,
                    dpi,
                    mirror,
                    input,
                    null,
                    null,
                ) ?: throw IllegalStateException("仮想ディスプレイを作成できません")
                createdDisplay = created
                created
            }
            targetDisplay.setSurface(input)
            codec = encoder
            activeWidth = fitted.width
            activeHeight = fitted.height
            surface = input
            display = targetDisplay
            running = true
            val worker = Thread({ drainLoop(encoder, fitted) }, "atcast-video")
            worker.setUncaughtExceptionHandler { _, error ->
                running = false
                Log.e(TAG, "video drain failed", error)
            }
            thread = worker
            worker.start()
            return true
        } catch (error: Exception) {
            Log.e(TAG, "video start failed ${fitted.width}x${fitted.height}", error)
            running = false
            activeWidth = 0
            activeHeight = 0
            try {
                display?.setSurface(null)
            } catch (_: Exception) {
            }
            if (codec === encoder) {
                codec = null
            }
            if (surface === input) {
                surface = null
            }
            if (createdDisplay != null && display === createdDisplay) {
                display = null
            }
            try {
                createdDisplay?.release()
            } catch (_: Exception) {
            }
            try {
                encoder.stop()
            } catch (_: Exception) {
            }
            try {
                input?.release()
            } catch (_: Exception) {
            }
            try {
                encoder.release()
            } catch (_: Exception) {
            }
            return false
        }
    }

    private fun openEncoder(size: FrameSize, preset: StreamPreset): Pair<MediaCodec, FrameSize>? {
        val preferred = when (codecPreference) {
            VIDEO_ENCODER_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            VIDEO_ENCODER_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> if (hardwareSupports(MediaFormat.MIMETYPE_VIDEO_HEVC, size)) {
                MediaFormat.MIMETYPE_VIDEO_HEVC
            } else {
                MediaFormat.MIMETYPE_VIDEO_AVC
            }
        }
        val opened = configureMime(preferred, size, preset)
        if (opened != null) {
            codecId = if (preferred == MediaFormat.MIMETYPE_VIDEO_HEVC) CODEC_HEVC else CODEC_AVC
            return opened
        }
        if (preferred != MediaFormat.MIMETYPE_VIDEO_AVC) {
            val fallback = configureMime(MediaFormat.MIMETYPE_VIDEO_AVC, size, preset)
            if (fallback != null) {
                codecId = CODEC_AVC
                return fallback
            }
        }
        return null
    }

    private fun configureMime(mime: String, size: FrameSize, preset: StreamPreset): Pair<MediaCodec, FrameSize>? {
        val aggressive = createConfigured(mime, size, preset, aggressive = true)
        if (aggressive != null) {
            return aggressive
        }
        // 低遅延オプションを拒否する機種は、色とビットレートだけの設定で作り直す。
        return createConfigured(mime, size, preset, aggressive = false)
    }

    private fun hardwareSupports(mime: String, size: FrameSize): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder || !info.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }) {
                continue
            }
            val software = info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
            if (Build.VERSION.SDK_INT >= 29) {
                if (!info.isHardwareAccelerated || software) {
                    continue
                }
            } else if (software) {
                continue
            }
            val video = try {
                info.getCapabilitiesForType(mime).videoCapabilities
            } catch (_: Exception) {
                null
            } ?: continue
            if (video.isSizeSupported(size.width, size.height)) {
                return true
            }
            if (size.width >= video.supportedWidths.lower && size.height >= video.supportedHeights.lower) {
                return true
            }
        }
        return false
    }

    private fun createConfigured(
        mime: String,
        size: FrameSize,
        preset: StreamPreset,
        aggressive: Boolean,
    ): Pair<MediaCodec, FrameSize>? {
        val encoder = try {
            MediaCodec.createEncoderByType(mime)
        } catch (error: Exception) {
            Log.e(TAG, "encoder missing $mime", error)
            return null
        }
        val fitted = fit(encoder, mime, size)
        try {
            encoder.configure(
                baseFormat(mime, fitted, preset, aggressive),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            return encoder to fitted
        } catch (error: Exception) {
            Log.e(TAG, "encoder configure failed $mime aggressive=$aggressive", error)
            encoder.release()
            return null
        }
    }

    private fun fit(encoder: MediaCodec, mime: String, requested: FrameSize): FrameSize {
        val width16 = (requested.width / 16) * 16
        val height16 = (requested.height / 16) * 16
        val aligned = FrameSize(width16.coerceAtLeast(16), height16.coerceAtLeast(16))
        val caps = try {
            encoder.codecInfo.getCapabilitiesForType(mime).videoCapabilities
        } catch (_: Exception) {
            null
        } ?: return aligned
        return try {
            var width = aligned.width.coerceIn(caps.supportedWidths.lower, caps.supportedWidths.upper)
            val alignW = caps.widthAlignment.coerceAtLeast(2)
            width = width / alignW * alignW
            if (width < alignW) {
                width = alignW
            }
            val heights = caps.getSupportedHeightsFor(width)
            var height = aligned.height.coerceIn(heights.lower, heights.upper)
            val alignH = caps.heightAlignment.coerceAtLeast(2)
            height = height / alignH * alignH
            if (height < alignH) {
                height = alignH
            }
            FrameSize(width.coerceAtLeast(16), height.coerceAtLeast(16))
        } catch (_: Exception) {
            aligned
        }
    }

    private fun drainLoop(encoder: MediaCodec, size: FrameSize) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                encoder.dequeueOutputBuffer(info, 10_000)
            } catch (error: Exception) {
                Log.e(TAG, "video output failed", error)
                break
            }
            try {
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sendCodecConfig(encoder.outputFormat, size)
                    }
                    index >= 0 -> {
                        val buffer = encoder.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(bytes)
                            val annex = ensureAnnexB(bytes)
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            if (config) {
                                publishConfig(Packet.VideoConfig(codecId, size.width, size.height, annex))
                            } else {
                                send(Packet.Video(info.presentationTimeUs, key, annex))
                            }
                        }
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun sendCodecConfig(format: MediaFormat, size: FrameSize) {
        val parts = ArrayList<ByteArray>()
        // HEVC は VPS が csd-2 に分かれる機種がある。VPS、SPS、PPS の順でつなぐ。
        for (key in arrayOf("csd-2", "csd-0", "csd-1")) {
            val buffer = try {
                format.getByteBuffer(key)
            } catch (_: Exception) {
                null
            } ?: continue
            if (buffer.remaining() > 0) {
                parts.add(nalBytes(buffer))
            }
        }
        if (parts.isEmpty()) {
            return
        }
        var total = 0
        for (part in parts) {
            total += part.size
        }
        val merged = ByteArray(total)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, merged, offset, part.size)
            offset += part.size
        }
        publishConfig(Packet.VideoConfig(codecId, size.width, size.height, merged))
    }

    private fun nalBytes(buffer: java.nio.ByteBuffer): ByteArray {
        val raw = ByteArray(buffer.remaining())
        buffer.get(raw)
        return ensureAnnexB(raw)
    }

    private fun publishConfig(config: Packet.VideoConfig) {
        lastConfig = config
        send(config)
    }

    private fun baseFormat(mime: String, size: FrameSize, preset: StreamPreset, aggressive: Boolean): MediaFormat {
        val format = MediaFormat.createVideoFormat(
            mime,
            size.width,
            size.height,
        )
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, preset.bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, preset.fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        if (aggressive && Build.VERSION.SDK_INT >= 29) {
            // IDR に SPS/PPS を付ける。無いと受信側は同期できず真っ黒のままになる。
            format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        if (!aggressive) {
            return format
        }
        format.setInteger(
            MediaFormat.KEY_BITRATE_MODE,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
        )
        if (Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        // KEY_LATENCY と REPEAT_PREVIOUS_FRAME_AFTER は configure が成功したあとに
        // ネイティブ側で落ちる機種があるため、最初から付けない。
        return format
    }

    companion object {
        private const val TAG = "atcast"
    }
}
