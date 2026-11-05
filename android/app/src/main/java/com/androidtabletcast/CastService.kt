package com.androidtabletcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.CopyOnWriteArrayList

data class CastSnapshot(
    val running: Boolean = false,
    val pin: String = "",
    val ips: String = "",
    val status: String = "停止中",
    val connected: Boolean = false,
)

object CastStatus {
    @Volatile
    var current: CastSnapshot = CastSnapshot()
        private set

    private val listeners = CopyOnWriteArrayList<(CastSnapshot) -> Unit>()

    fun update(block: (CastSnapshot) -> CastSnapshot) {
        val next = block(current)
        current = next
        for (listener in listeners) {
            listener(next)
        }
    }

    fun listen(listener: (CastSnapshot) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(current)
        return { listeners.remove(listener) }
    }
}

class CastService : Service(), StreamEvents {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var trustStore: TrustStore
    private var server: StreamServer? = null
    private var projection: MediaProjection? = null
    private var video: VideoPipeline? = null
    private var audio: AudioPipeline? = null
    private var router: InputRouter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var desiredPreset = PRESET_BALANCED
    private var releasing = false
    private var overlay: TextView? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        trustStore = TrustStore(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 画面収録の許可より先に前景へ出さないと、Android 14 以降は投影を開始できない。
        startInForeground()
        when (intent?.action) {
            ACTION_STOP -> stopCast()
            ACTION_START -> begin(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseSession()
        CastStatus.update { CastSnapshot() }
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun approveTrust(pcIdHex: String, name: String): Boolean {
        val pcId = try {
            hexToBytes(pcIdHex)
        } catch (_: Exception) {
            return false
        }
        return server?.grantIfPending(pcId, name) == true
    }

    override fun onAuthenticated(preset: Int) {
        main.post {
            // 仮想ディスプレイは配信開始時に作ってある。ここでは溜めた設定と IDR を送り直す。
            if (video == null) {
                startVideo(desiredPreset)
            }
            if (video == null) {
                CastStatus.update { it.copy(connected = true, status = "映像を開始できませんでした") }
                return@post
            }
            video?.replayConfig()
            video?.requestKeyframe()
            audio?.takeIf { it.isRunning }?.replayConfigAndResume()
            startAudio()
            CastStatus.update { it.copy(connected = true, status = "接続中") }
        }
    }

    override fun onClientClosed() {
        audio?.pauseSending()
        main.post {
            // 切ったあとに仮想ディスプレイを破棄すると、次の接続で再作成に失敗して落ちることがある。
            CastStatus.update { it.copy(connected = false, status = "待受中") }
        }
    }

    override fun onKeyframeRequested() {
        video?.replayConfig()
        video?.requestKeyframe()
    }

    override fun onPreset(preset: Int) {
        desiredPreset = preset
        main.post {
            val current = video ?: return@post
            if (!current.changePreset(preset)) {
                CastStatus.update { it.copy(status = "画質変更に失敗しました（前の設定への復旧を試しました）") }
            } else {
                current.replayConfig()
                current.requestKeyframe()
            }
        }
    }

    override fun onInput(packet: Packet) {
        router?.handle(packet)
    }

    override fun onTrustRequested(pcId: ByteArray, name: String) {
        val prompt = Intent(this, TrustPromptActivity::class.java)
        prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        prompt.putExtra(TrustPromptActivity.EXTRA_PC_ID, pcId.toHex())
        prompt.putExtra(TrustPromptActivity.EXTRA_NAME, name)
        startActivity(prompt)
    }

    override fun onStatus(text: String) {
        CastStatus.update { it.copy(status = text, connected = text == "接続中") }
    }

    private fun begin(intent: Intent) {
        releaseSession()
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopCast()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val created = try {
            manager.getMediaProjection(code, data)
        } catch (_: Exception) {
            null
        }
        if (created == null) {
            stopCast()
            return
        }
        created.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // releaseSession 中の stop では、もう一度停止処理を始めない。
                    if (!releasing) {
                        stopCast()
                    }
                }
            },
            main,
        )
        projection = created
        val display = readDisplay(this)
        router = InputRouter(display.first, display.second)
        acquireLocks()
        val pin = newPin()
        val stream = StreamServer(this, trustStore, this)
        stream.trustMode = Preferences(this).trustMode
        stream.start(pin, trustStore.tabletId(), display.first, display.second)
        server = stream
        // 画面収録の許可の直後、アプリが前面にあるうちにエンコーダを開始する。
        val videoReady = startVideo(desiredPreset)
        showOverlay()
        CastStatus.update {
            it.copy(
                running = true,
                pin = pin,
                ips = localIpv4().joinToString("\n").ifEmpty { "IP なし" },
                status = if (videoReady) "待受中" else "映像を開始できませんでした",
                connected = false,
            )
        }
    }

    fun refreshTrustMode() {
        server?.trustMode = Preferences(this).trustMode
    }

    private fun startVideo(preset: Int): Boolean {
        val currentProjection = projection ?: return false
        val display = readDisplay(this)
        router?.resize(display.first, display.second)
        video?.stop()
        video = null
        try {
            val videoPipeline = VideoPipeline(
                currentProjection,
                display.first,
                display.second,
                display.third,
                codecPreference = Preferences(this).videoEncoder,
                send = { packet -> server?.send(packet) },
            )
            if (!videoPipeline.start(preset)) {
                return false
            }
            video = videoPipeline
            return true
        } catch (error: Exception) {
            Log.e(TAG, "video pipeline failed", error)
            return false
        }
    }

    private fun startAudio() {
        val existing = audio
        if (existing != null) {
            if (existing.isRunning) {
                return
            }
            existing.stop()
            audio = null
        }
        val currentProjection = projection ?: return
        try {
            val audioPipeline = AudioPipeline(currentProjection) { packet -> server?.send(packet) }
            audioPipeline.start()
            audio = audioPipeline
        } catch (error: Exception) {
            // 再生音を取れない端末でも映像は続ける。
            Log.e(TAG, "audio pipeline failed", error)
        }
    }

    private fun stopPipelines() {
        audio?.stop()
        audio = null
        video?.stop()
        video = null
    }

    private fun releaseSession() {
        if (releasing) {
            return
        }
        releasing = true
        try {
            stopPipelines()
            hideOverlay()
            server?.stop()
            server = null
            val current = projection
            projection = null
            try {
                current?.stop()
            } catch (_: Exception) {
            }
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
            if (multicast?.isHeld == true) {
                multicast?.release()
            }
            multicast = null
        } finally {
            releasing = false
        }
    }

    private fun stopCast() {
        releaseSession()
        CastStatus.update { CastSnapshot() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireLocks() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "atcast:cast").apply { acquire() }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        multicast = wifi?.createMulticastLock("atcast")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun showOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) {
            return
        }
        val chip = TextView(this)
        chip.text = "配信中"
        chip.setTextColor(0xFFE8EEF2.toInt())
        chip.setPadding(28, 12, 28, 12)
        chip.setBackgroundColor(0xCC101418.toInt())
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 24
        params.y = 24
        try {
            getSystemService(WindowManager::class.java).addView(chip, params)
            overlay = chip
        } catch (error: Exception) {
            Log.e(TAG, "overlay failed", error)
        }
    }

    private fun hideOverlay() {
        val chip = overlay ?: return
        overlay = null
        try {
            getSystemService(WindowManager::class.java).removeView(chip)
        } catch (_: Exception) {
        }
    }

    private fun startInForeground() {
        // mediaPlayback を足すと、再生していないサービスをシステムが接続時に止める端末がある。
        val projectionType = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), projectionType)
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("画面を配信中")
            .setContentText("同じ Wi-Fi の Windows から接続できます")
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "画面配信", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.androidtabletcast.START"
        const val ACTION_STOP = "com.androidtabletcast.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val TAG = "atcast"
        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 7

        @Volatile
        var instance: CastService? = null
    }
}

fun readDisplay(context: Context): Triple<Int, Int, Int> {
    try {
        val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = manager?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi.coerceAtLeast(1))
            }
        }
    } catch (_: Exception) {
        // サービスからウィンドウ寸法を取れない端末は、リソースの既定値に落とす。
    }
    val fallback = context.resources.displayMetrics
    return Triple(
        fallback.widthPixels.coerceAtLeast(16),
        fallback.heightPixels.coerceAtLeast(16),
        fallback.densityDpi.coerceAtLeast(1),
    )
}

fun accessibilityEnabled(context: Context): Boolean {
    val expected = android.content.ComponentName(context, InputAccessibilityService::class.java)
    val enabled = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { name ->
        android.content.ComponentName.unflattenFromString(name) == expected
    }
}
