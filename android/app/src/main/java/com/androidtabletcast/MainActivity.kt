package com.androidtabletcast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.AdapterView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {
    private lateinit var preferences: Preferences
    private lateinit var trustStore: TrustStore
    private var stopListening: (() -> Unit)? = null

    private val notifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { capture.launch(projectionIntent()) }

    private val capture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            return@registerForActivityResult
        }
        val start = Intent(this, CastService::class.java).setAction(CastService.ACTION_START)
        start.putExtra(CastService.EXTRA_RESULT_CODE, result.resultCode)
        start.putExtra(CastService.EXTRA_DATA, data)
        ContextCompat.startForegroundService(this, start)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preferences = Preferences(this)
        trustStore = TrustStore(this)
        ShizukuInput.installListeners(this)

        val nameInput = findViewById<EditText>(R.id.nameInput)
        nameInput.setText(preferences.deviceName)
        nameInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                preferences.deviceName = nameInput.text.toString().ifBlank { "Tablet" }
            }
        }

        val trustSwitch = findViewById<MaterialSwitch>(R.id.trustSwitch)
        trustSwitch.isChecked = preferences.trustMode
        trustSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.trustMode = checked
            CastService.instance?.refreshTrustMode()
        }

        val videoEncoderSpinner = findViewById<Spinner>(R.id.videoEncoderSpinner)
        videoEncoderSpinner.setSelection(preferences.videoEncoder, false)
        videoEncoderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                preferences.videoEncoder = position
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        findViewById<MaterialButton>(R.id.castButton).setOnClickListener {
            if (CastStatus.current.running) {
                startService(Intent(this, CastService::class.java).setAction(CastService.ACTION_STOP))
            } else {
                preferences.deviceName = nameInput.text.toString().ifBlank { "Tablet" }
                beginCapture()
            }
        }
        findViewById<MaterialButton>(R.id.overlayButton).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        }
        findViewById<MaterialButton>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.shizukuButton).setOnClickListener {
            ShizukuInput.requestPermission()
            ShizukuInput.bind(this)
            renderPermissions()
        }

        stopListening = CastStatus.listen { snapshot ->
            runOnUiThread { render(snapshot) }
        }
        renderTrustList()
    }

    override fun onResume() {
        super.onResume()
        ShizukuInput.bind(this)
        renderPermissions()
        renderTrustList()
        render(CastStatus.current)
    }

    override fun onDestroy() {
        stopListening?.invoke()
        super.onDestroy()
    }

    private fun beginCapture() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        capture.launch(projectionIntent())
    }

    private fun projectionIntent(): Intent {
        return getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
    }

    private fun render(snapshot: CastSnapshot) {
        findViewById<TextView>(R.id.statusText).text = snapshot.status
        findViewById<TextView>(R.id.pinText).text = if (snapshot.running) "PIN  ${snapshot.pin}" else ""
        findViewById<TextView>(R.id.ipText).text = if (snapshot.running) {
            "ポート $TCP_PORT\n${snapshot.ips}"
        } else {
            ""
        }
        findViewById<MaterialButton>(R.id.castButton).text =
            if (snapshot.running) getString(R.string.stop_cast) else getString(R.string.start_cast)
        findViewById<Spinner>(R.id.videoEncoderSpinner).isEnabled = !snapshot.running
    }

    private fun renderPermissions() {
        findViewById<TextView>(R.id.overlayState).text = if (Settings.canDrawOverlays(this)) {
            "他のアプリの上に表示: 許可済み"
        } else {
            "他のアプリの上に表示: 未許可（許可すると前面のアプリでも収録が安定します）"
        }
        findViewById<TextView>(R.id.accessibilityState).text = if (accessibilityEnabled(this)) {
            "ユーザー補助: 有効"
        } else {
            "ユーザー補助: 無効（タップの返しに必要）"
        }
        val shizuku = when {
            ShizukuInput.isReady() -> "Shizuku: 入力注入を使用できます"
            ShizukuInput.isInstalled() -> "Shizuku: 起動済み。許可すると低遅延の操作になります"
            else -> "Shizuku: 未接続。入れなくても映像と基本操作は動きます"
        }
        findViewById<TextView>(R.id.shizukuState).text = shizuku
    }

    private fun renderTrustList() {
        val list = findViewById<LinearLayout>(R.id.trustList)
        list.removeAllViews()
        val records = trustStore.list()
        if (records.isEmpty()) {
            list.addView(label("信頼済みの PC はありません"))
            return
        }
        for (record in records) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val title = label(record.name.ifBlank { record.pcId.toHex().take(8) })
            title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val remove = MaterialButton(this)
            remove.text = "取消"
            remove.setOnClickListener {
                trustStore.revoke(record.pcId)
                renderTrustList()
            }
            row.addView(title)
            row.addView(remove)
            list.addView(row)
        }
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setPadding(0, 12, 0, 12)
        }
    }
}
