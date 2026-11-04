package com.androidtabletcast

import android.content.Context

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
            prefs.edit().putBoolean(KEY_TRUST, value).apply()
        }

    companion object {
        private const val KEY_NAME = "device_name"
        private const val KEY_TRUST = "trust_mode"
    }
}
