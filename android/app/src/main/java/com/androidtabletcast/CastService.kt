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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
    private var desiredPreset = PRESET_HIGH_QUALITY
    private var releasing = false

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
        main.post { startPipelines(desiredPreset) }
    }

    override fun onClientClosed() {
        main.post {
            stopPipelines()
            CastStatus.update { it.copy(connected = false, status = "待受中") }
        }
    }

    override fun onKeyframeRequested() {
        video?.requestKeyframe()
    }

    override fun onPreset(preset: Int) {
        desiredPreset = preset
        main.post { video?.changePreset(preset) }
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
        CastStatus.update {
            it.copy(
                running = true,
                pin = pin,
                ips = localIpv4().joinToString("\n").ifEmpty { "IP なし" },
                status = "待受中",
                connected = false,
            )
        }
    }

    fun refreshTrustMode() {
        server?.trustMode = Preferences(this).trustMode
    }

    private fun startPipelines(preset: Int) {
        val currentProjection = projection ?: return
        val display = readDisplay(this)
        router?.resize(display.first, display.second)
        stopPipelines()
        val videoPipeline = VideoPipeline(
            currentProjection,
            display.first,
            display.second,
            display.third,
            send = { packet -> server?.send(packet) },
        )
        videoPipeline.start(preset)
        video = videoPipeline
        try {
            val audioPipeline = AudioPipeline(currentProjection) { packet -> server?.send(packet) }
            audioPipeline.start()
            audio = audioPipeline
        } catch (_: Exception) {
            // 再生音を取れない端末でも映像は続ける。
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

    private fun startInForeground() {
        val projectionType = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), projectionType)
        } catch (_: Exception) {
            val fallback = if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), fallback)
        }
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
        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 7

        @Volatile
        var instance: CastService? = null
    }
}

fun readDisplay(context: Context): Triple<Int, Int, Int> {
    val window = context.getSystemService(android.view.WindowManager::class.java)
    val density = context.resources.displayMetrics.densityDpi
    if (Build.VERSION.SDK_INT >= 30) {
        val bounds = window.currentWindowMetrics.bounds
        return Triple(bounds.width(), bounds.height(), density)
    }
    val point = android.graphics.Point()
    @Suppress("DEPRECATION")
    window.defaultDisplay.getRealSize(point)
    return Triple(point.x, point.y, density)
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
