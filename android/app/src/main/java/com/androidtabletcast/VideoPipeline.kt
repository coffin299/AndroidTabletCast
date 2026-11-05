package com.androidtabletcast

import android.media.MediaCodec
import android.media.MediaCodecInfo
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

    fun start(presetId: Int): Boolean {
        synchronized(lock) {
            stopLocked()
            val chosen = presetOf(presetId)
            preset = chosen
            val requested = scaledSize(deviceWidth, deviceHeight, chosen.longSide)
            val sizes = listOf(
                requested,
                scaledSize(deviceWidth, deviceHeight, 1280),
                scaledSize(deviceWidth, deviceHeight, 960),
            ).distinctBy { size -> size.width to size.height }
            for (size in sizes) {
                if (tryStart(size, chosen)) {
                    return true
                }
            }
            running = false
            return false
        }
    }

    fun replayConfig() {
        val config = lastConfig ?: return
        send(config)
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
        return start(presetId)
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        running = false
        val worker = thread
        thread = null
        display?.release()
        display = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        worker?.join(500)
        surface?.release()
        surface = null
        codec?.release()
        codec = null
    }

    private fun tryStart(size: FrameSize, chosen: StreamPreset): Boolean {
        val opened = openEncoder(size, chosen) ?: return false
        val encoder = opened.first
        val fitted = opened.second
        var input: Surface? = null
        var virtual: android.hardware.display.VirtualDisplay? = null
        try {
            input = encoder.createInputSurface()
            encoder.start()
            virtual = projection.createVirtualDisplay(
                "atcast",
                fitted.width,
                fitted.height,
                densityDpi.coerceAtLeast(1),
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                input,
                null,
                null,
            )
            codec = encoder
            surface = input
            display = virtual
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
            if (codec === encoder) {
                codec = null
                surface = null
                display = null
            }
            try {
                virtual?.release()
            } catch (_: Exception) {
            }
            try {
                encoder.stop()
            } catch (_: Exception) {
            }
            input?.release()
            encoder.release()
            return false
        }
    }

    private fun openEncoder(size: FrameSize, preset: StreamPreset): Pair<MediaCodec, FrameSize>? {
        val aggressive = createConfigured(size, preset, aggressive = true)
        if (aggressive != null) {
            return aggressive
        }
        // 低遅延オプションを拒否する機種は、色とビットレートだけの設定で作り直す。
        return createConfigured(size, preset, aggressive = false)
    }

    private fun createConfigured(
        size: FrameSize,
        preset: StreamPreset,
        aggressive: Boolean,
    ): Pair<MediaCodec, FrameSize>? {
        val encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (error: Exception) {
            Log.e(TAG, "encoder missing", error)
            return null
        }
        val fitted = fit(encoder, size)
        try {
            encoder.configure(
                baseFormat(fitted, preset, aggressive),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            return encoder to fitted
        } catch (error: Exception) {
            Log.e(TAG, "encoder configure failed aggressive=$aggressive", error)
            encoder.release()
            return null
        }
    }

    private fun fit(encoder: MediaCodec, requested: FrameSize): FrameSize {
        val width16 = (requested.width / 16) * 16
        val height16 = (requested.height / 16) * 16
        val aligned = FrameSize(width16.coerceAtLeast(16), height16.coerceAtLeast(16))
        val caps = try {
            encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
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
            } catch (_: Exception) {
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
                            val annex = toAnnexB(bytes)
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            if (config) {
                                publishConfig(Packet.VideoConfig(size.width, size.height, annex))
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
        val sps = try {
            format.getByteBuffer("csd-0")
        } catch (_: Exception) {
            null
        } ?: return
        val pps = try {
            format.getByteBuffer("csd-1")
        } catch (_: Exception) {
            null
        }
        val spsSize = sps.remaining()
        val ppsSize = pps?.remaining() ?: 0
        val merged = ByteArray(spsSize + ppsSize)
        sps.get(merged, 0, spsSize)
        if (pps != null && ppsSize > 0) {
            pps.get(merged, spsSize, ppsSize)
        }
        publishConfig(Packet.VideoConfig(size.width, size.height, toAnnexB(merged)))
    }

    private fun publishConfig(config: Packet.VideoConfig) {
        lastConfig = config
        send(config)
    }

    private fun baseFormat(size: FrameSize, preset: StreamPreset, aggressive: Boolean): MediaFormat {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
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
