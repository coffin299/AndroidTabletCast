package com.androidtabletcast

import android.view.KeyEvent

class InputRouter(
    private var deviceWidth: Int,
    private var deviceHeight: Int,
) {
    private var downTime = 0L

    fun resize(width: Int, height: Int) {
        deviceWidth = width.coerceAtLeast(1)
        deviceHeight = height.coerceAtLeast(1)
    }

    fun handle(packet: Packet) {
        when (packet) {
            is Packet.InputTouch -> touch(packet)
            is Packet.InputKey -> key(packet)
            is Packet.InputScroll -> scroll(packet)
            else -> Unit
        }
    }

    private fun touch(packet: Packet.InputTouch) {
        val x = packet.xNorm / 65535f * (deviceWidth - 1)
        val y = packet.yNorm / 65535f * (deviceHeight - 1)
        if (packet.action == TOUCH_DOWN) {
            downTime = android.os.SystemClock.uptimeMillis()
        }
        val started = downTime
        val injected = ShizukuInput.touch(packet.action, x, y, started)
        if (packet.action == TOUCH_UP || packet.action == TOUCH_CANCEL) {
            // 指を離したあとの移動を、次のタップと混ぜない。
            downTime = 0
        }
        if (injected) {
            return
        }
        if (packet.action == TOUCH_MOVE && started == 0L) {
            // ボタンを押していない移動は、ジェスチャにすると誤タップになる。
            return
        }
        InputAccessibilityService.instance?.enqueueTouch(packet.action, x, y)
    }

    private fun key(packet: Packet.InputKey) {
        if (packet.action == KeyEvent.ACTION_DOWN && packet.keyCode == KeyEvent.KEYCODE_BACK) {
            if (!ShizukuInput.key(packet.action, packet.keyCode, packet.meta, android.os.SystemClock.uptimeMillis())) {
                InputAccessibilityService.instance?.back()
            }
            return
        }
        if (packet.action == KeyEvent.ACTION_DOWN && packet.keyCode == KeyEvent.KEYCODE_HOME) {
            if (!ShizukuInput.key(packet.action, packet.keyCode, packet.meta, android.os.SystemClock.uptimeMillis())) {
                InputAccessibilityService.instance?.home()
            }
            return
        }
        if (ShizukuInput.key(packet.action, packet.keyCode, packet.meta, android.os.SystemClock.uptimeMillis())) {
            return
        }
        if (packet.action != KeyEvent.ACTION_DOWN) {
            return
        }
        when (packet.keyCode) {
            KeyEvent.KEYCODE_DEL -> InputAccessibilityService.instance?.backspace()
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                InputAccessibilityService.instance?.enter()
            else -> {
                val character = characterForKey(packet.keyCode, packet.meta) ?: return
                InputAccessibilityService.instance?.typeCharacter(character)
            }
        }
    }

    private fun scroll(packet: Packet.InputScroll) {
        val x = packet.xNorm / 65535f * (deviceWidth - 1)
        val y = packet.yNorm / 65535f * (deviceHeight - 1)
        if (ShizukuInput.scroll(x, y, packet.delta / 120f)) {
            return
        }
        InputAccessibilityService.instance?.scroll(x, y, packet.delta / 120)
    }
}
