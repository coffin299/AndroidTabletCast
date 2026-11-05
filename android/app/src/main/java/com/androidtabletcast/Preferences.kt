package com.androidtabletcast

import android.content.Context

const val VIDEO_ENCODER_AUTO = 0
const val VIDEO_ENCODER_H264 = 1
const val VIDEO_ENCODER_H265 = 2

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("atcast", Context.MODE_PRIVATE)

    var deviceName: String
        get() = prefs.getString(KEY_NAME, android.os.Build.MODEL) ?: "Tablet"
        set(value) {
            prefs.edit().putString(KEY_NAME, value).apply()
        }

    var trustMode: Boolean
        get() = prefs.getBoolean(KEY_TRUST, false)
        set(value) {
            // 接続の HELLO が直後に読むので、apply の遅れでオフのままにしない。
            prefs.edit().putBoolean(KEY_TRUST, value).commit()
        }

    var videoEncoder: Int
        get() = prefs.getInt(KEY_VIDEO_ENCODER, VIDEO_ENCODER_AUTO)
            .takeIf { it in VIDEO_ENCODER_AUTO..VIDEO_ENCODER_H265 } ?: VIDEO_ENCODER_AUTO
        set(value) {
            prefs.edit().putInt(KEY_VIDEO_ENCODER, value.coerceIn(VIDEO_ENCODER_AUTO, VIDEO_ENCODER_H265)).apply()
        }

    companion object {
        private const val KEY_NAME = "device_name"
        private const val KEY_TRUST = "trust_mode"
        private const val KEY_VIDEO_ENCODER = "video_encoder"
    }
}
