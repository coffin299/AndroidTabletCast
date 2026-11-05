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

    @Volatile
    private var running = false

    fun start() {
        if (Build.VERSION.SDK_INT < 29) {
            return
        }
        stop()
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
            val bufferBytes = maxOf(BUFFER_BYTES * 2, if (minBuffer > 0) minBuffer else BUFFER_BYTES)
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
    }

    private fun loop(encoder: MediaCodec, record: AudioRecord) {
        val pcm = ByteArray(BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val read = record.read(pcm, 0, pcm.size)
                if (read > 0) {
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = encoder.getInputBuffer(inputIndex)
                        input?.clear()
                        input?.put(pcm, 0, read)
                        encoder.queueInputBuffer(inputIndex, 0, read, System.nanoTime() / 1000, 0)
                    }
                }
                while (true) {
                    val outputIndex = encoder.dequeueOutputBuffer(info, 0)
                    if (outputIndex < 0) {
                        break
                    }
                    val output = encoder.getOutputBuffer(outputIndex)
                    if (output != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        output.get(bytes)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (config) {
                            send(Packet.AudioConfig(SAMPLE_RATE, 2, bytes))
                        } else {
                            send(Packet.Audio(info.presentationTimeUs, bytes))
                        }
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                }
            } catch (error: Exception) {
                Log.e(TAG, "audio loop failed", error)
                running = false
            }
        }
    }

    companion object {
        private const val TAG = "atcast"
        private const val SAMPLE_RATE = 48000
        private const val BUFFER_BYTES = 4096
    }
}
