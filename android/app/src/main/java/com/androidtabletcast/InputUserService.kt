package com.androidtabletcast

import android.content.Context
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Shizuku 経由で shell 権限として動く入力注入。
 * 通常のアプリプロセスからは他アプリへ InputEvent を注入できない。
 */
class InputUserService : IInputInjector.Stub {
    @Suppress("unused")
    constructor() : super()

    @Suppress("unused")
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    override fun destroy() {
        // Shizuku がこのメソッドでプロセスを終わらせる契約になっている。
        System.exit(0)
    }

    override fun injectTouch(action: Int, x: Float, y: Float, downTime: Long): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        return inject(event).also { event.recycle() }
    }

    override fun injectKey(action: Int, keyCode: Int, meta: Int, downTime: Long): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val event = KeyEvent(downTime, now, action, keyCode, 0, meta)
        event.source = InputDevice.SOURCE_KEYBOARD
        return inject(event)
    }

    override fun injectScroll(x: Float, y: Float, delta: Float): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, delta)
        }
        val event = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_SCROLL,
            1,
            arrayOf(properties),
            arrayOf(coords),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0,
        )
        return inject(event).also { event.recycle() }
    }

    private fun inject(event: InputEvent): Boolean {
        return try {
            val clazz = Class.forName("android.hardware.input.InputManager")
            val manager = clazz.getDeclaredMethod("getInstance").invoke(null)
            val method = clazz.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            // 0 は待たずにキューへ入れるモード。往復を増やすと操作が遅れる。
            val result = method.invoke(manager, event, 0)
            result as? Boolean ?: true
        } catch (_: Throwable) {
            false
        }
    }
}
