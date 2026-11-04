package com.androidtabletcast

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.view.MotionEvent
import rikka.shizuku.Shizuku

object ShizukuInput {
    @Volatile
    var injector: IInputInjector? = null
        private set

    private var args: Shizuku.UserServiceArgs? = null
    private var listening = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            injector = IInputInjector.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            injector = null
        }
    }

    fun installListeners(context: Context) {
        if (listening) {
            return
        }
        listening = true
        try {
            Shizuku.addBinderReceivedListenerSticky { bind(context.applicationContext) }
            Shizuku.addBinderDeadListener { injector = null }
        } catch (_: Throwable) {
            listening = false
        }
    }

    fun isReady(): Boolean = injector != null

    fun isInstalled(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                return
            }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (_: Throwable) {
            // Shizuku アプリが無い、またはまだ起動していない。
        }
    }

    fun bind(context: Context) {
        try {
            if (!Shizuku.pingBinder()) {
                return
            }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
            if (injector != null) {
                return
            }
            val userArgs = Shizuku.UserServiceArgs(
                ComponentName(context, InputUserService::class.java),
            )
                .daemon(false)
                .processNameSuffix("input")
                .debuggable(BuildConfig.DEBUG)
                .version(1)
                .tag("atcast-input")
            args = userArgs
            Shizuku.bindUserService(userArgs, connection)
        } catch (_: Throwable) {
            injector = null
        }
    }

    fun touch(action: Int, x: Float, y: Float, downTime: Long): Boolean {
        val motion = when (action) {
            TOUCH_DOWN -> MotionEvent.ACTION_DOWN
            TOUCH_MOVE -> MotionEvent.ACTION_MOVE
            TOUCH_UP -> MotionEvent.ACTION_UP
            else -> MotionEvent.ACTION_CANCEL
        }
        return try {
            injector?.injectTouch(motion, x, y, downTime) == true
        } catch (_: Throwable) {
            false
        }
    }

    fun key(action: Int, keyCode: Int, meta: Int, downTime: Long): Boolean {
        val keyAction = if (action == 0) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP
        return try {
            injector?.injectKey(keyAction, keyCode, meta, downTime) == true
        } catch (_: Throwable) {
            false
        }
    }

    fun scroll(x: Float, y: Float, delta: Float): Boolean {
        return try {
            injector?.injectScroll(x, y, delta) == true
        } catch (_: Throwable) {
            false
        }
    }

    private const val REQUEST_CODE = 21
}
