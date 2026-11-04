package com.androidtabletcast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
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

    fun start(presetId: Int) {
        synchronized(lock) {
            stopLocked()
            preset = presetOf(presetId)
            val size = scaledSize(deviceWidth, deviceHeight, preset.longSide)
            val encoder = openEncoder(size, preset)
            val input = encoder.createInputSurface()
            encoder.start()
            val virtual = projection.createVirtualDisplay(
                "atcast",
                size.width,
                size.height,
                densityDpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                input,
                null,
                null,
            )
            codec = encoder
            surface = input
            display = virtual
            running = true
            thread = Thread({ drainLoop(encoder, size) }, "atcast-video").also { it.start() }
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

    fun changePreset(presetId: Int) {
        start(presetId)
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

    private fun openEncoder(size: FrameSize, preset: StreamPreset): MediaCodec {
        try {
            return createConfigured(size, preset, aggressive = true)
        } catch (_: Exception) {
            // 低遅延オプションを拒否する機種は、ベースライン設定でもう一度作る。
            return createConfigured(size, preset, aggressive = false)
        }
    }

    private fun createConfigured(size: FrameSize, preset: StreamPreset, aggressive: Boolean): MediaCodec {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            encoder.configure(
                baseFormat(size, preset, aggressive),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            return encoder
        } catch (error: Exception) {
            encoder.release()
            throw error
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
                                send(Packet.VideoConfig(size.width, size.height, annex))
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
        send(Packet.VideoConfig(size.width, size.height, toAnnexB(merged)))
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
            format.setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            )
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
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000)
        return format
    }
}
