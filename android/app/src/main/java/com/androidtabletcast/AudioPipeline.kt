package com.androidtabletcast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log

class AudioPipeline(
    private val projection: MediaProjection,
    private val send: (Packet) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private val sendLock = Any()
    @Volatile private var sendingEnabled = true
    @Volatile private var lastConfig: Packet.AudioConfig? = null

    @Volatile
    private var running = false

    val isRunning: Boolean
        get() = running

    fun start() {
        if (Build.VERSION.SDK_INT < 29) {
            return
        }
        stop()
        sendingEnabled = true
        lastConfig = null
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var record: AudioRecord? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferBytes = maxOf(BUFFER_BYTES * 4, if (minBuffer > 0) minBuffer else BUFFER_BYTES)
            val created = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(capture)
                .build()
            record = created
            created.startRecording()
            codec = encoder
            recorder = created
            running = true
            val worker = Thread({ loop(encoder, created) }, "atcast-audio")
            worker.setUncaughtExceptionHandler { _, error ->
                running = false
                Log.e(TAG, "audio failed", error)
            }
            thread = worker
            worker.start()
        } catch (error: Exception) {
            Log.e(TAG, "audio start failed", error)
            try {
                record?.release()
            } catch (_: Exception) {
            }
            try {
                encoder.stop()
            } catch (_: Exception) {
            }
            encoder.release()
            throw error
        }
    }

    fun stop() {
        running = false
        sendingEnabled = false
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        thread?.join(500)
        recorder?.release()
        recorder = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        codec?.release()
        codec = null
        thread = null
        lastConfig = null
    }

    fun pauseSending() {
        synchronized(sendLock) {
            sendingEnabled = false
        }
    }

    fun replayConfigAndResume() {
        synchronized(sendLock) {
            lastConfig?.let(send)
            sendingEnabled = true
        }
    }

    private fun loop(encoder: MediaCodec, record: AudioRecord) {
        val pcm = ByteArray(BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()
        var ptsUs = 0L
        while (running) {
            try {
                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = encoder.getInputBuffer(inputIndex)
                        ?: throw IllegalStateException("AAC encoder returned an empty input buffer")
                    input.clear()
                    val readLimit = minOf(pcm.size, input.remaining()) / BYTES_PER_SAMPLE_FRAME * BYTES_PER_SAMPLE_FRAME
                    val read = record.read(pcm, 0, readLimit, AudioRecord.READ_BLOCKING)
                    if (read < 0) {
                        throw IllegalStateException("AudioRecord.read failed: $read")
                    }
                    if (read > 0) {
                        input.put(pcm, 0, read)
                        encoder.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
                        ptsUs += read.toLong() / BYTES_PER_SAMPLE_FRAME * 1_000_000L / SAMPLE_RATE
                    } else {
                        encoder.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
                    }
                }
                while (true) {
                    val outputIndex = encoder.dequeueOutputBuffer(info, 0)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val outputFormat = encoder.outputFormat
                        val csd = outputFormat.getByteBuffer("csd-0")?.duplicate()
                        val specificConfig = if (csd == null) {
                            ByteArray(0)
                        } else {
                            ByteArray(csd.remaining()).also { csd.get(it) }
                        }
                        publishConfig(
                            outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            specificConfig,
                        )
                        continue
                    }
                    if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        continue
                    }
                    if (outputIndex < 0) {
                        break
                    }
                    try {
                        val output = encoder.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.get(bytes)
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (config) {
                                publishConfig(SAMPLE_RATE, 2, bytes)
                            } else {
                                sendPacket(Packet.Audio(info.presentationTimeUs, bytes))
                            }
                        }
                    } finally {
                        encoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "audio loop failed", error)
                running = false
            }
        }
    }

    private fun publishConfig(sampleRate: Int, channels: Int, specificConfig: ByteArray) {
        val previous = lastConfig
        if (previous != null && previous.sampleRate == sampleRate && previous.channels == channels &&
            previous.specificConfig.contentEquals(specificConfig)
        ) {
            return
        }
        val config = Packet.AudioConfig(sampleRate, channels, specificConfig)
        lastConfig = config
        sendPacket(config)
    }

    private fun sendPacket(packet: Packet) {
        synchronized(sendLock) {
            if (sendingEnabled) {
                send(packet)
            }
        }
    }

    companion object {
        private const val TAG = "atcast"
        private const val SAMPLE_RATE = 48000
        private const val BUFFER_BYTES = 4096
        private const val BYTES_PER_SAMPLE_FRAME = 4
    }
}
