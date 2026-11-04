package com.androidtabletcast

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

class InputAccessibilityService : AccessibilityService() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pending = ArrayDeque<StrokePoint>()
    private var active: GestureDescription.StrokeDescription? = null
    private var busy = false

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun enqueueTouch(action: Int, x: Float, y: Float) {
        handler.post {
            if (action == TOUCH_MOVE && pending.lastOrNull()?.action == TOUCH_MOVE) {
                // 溜まった移動は最後の位置だけ残す。ジェスチャの遅れを増やさない。
                pending.removeLast()
            }
            pending.add(StrokePoint(action, x, y))
            pump()
        }
    }

    fun back() {
        handler.post { performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    fun home() {
        handler.post { performGlobalAction(GLOBAL_ACTION_HOME) }
    }

    fun typeCharacter(character: Char) {
        handler.post { editFocused { current -> current + character } }
    }

    fun backspace() {
        handler.post { editFocused { current -> if (current.isEmpty()) current else current.dropLast(1) } }
    }

    fun enter() {
        handler.post {
            val node = focusedInput() ?: return@post
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
    }

    private fun editFocused(transform: (String) -> String) {
        val node = focusedInput() ?: return
        val current = node.text?.toString() ?: ""
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            transform(current),
        )
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun focusedInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    private fun pump() {
        if (busy) {
            return
        }
        val point = if (pending.isEmpty()) null else pending.removeFirst()
        if (point == null) {
            return
        }
        val path = Path().apply { moveTo(point.x, point.y) }
        val willContinue = point.action != TOUCH_UP && point.action != TOUCH_CANCEL
        val stroke = if (active == null || point.action == TOUCH_DOWN) {
            GestureDescription.StrokeDescription(path, 0, 20, willContinue)
        } else {
            active!!.continueStroke(path, 0, 20, willContinue)
        }
        active = if (willContinue) stroke else null
        busy = true
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    busy = false
                    if (!willContinue) {
                        active = null
                    }
                    pump()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    busy = false
                    active = null
                    pending.clear()
                }
            },
            handler,
        )
        if (!accepted) {
            busy = false
            active = null
        }
    }

    fun scroll(x: Float, y: Float, delta: Int) {
        handler.post {
            val distance = delta.coerceIn(-20, 20) * 40f
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y - distance)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 80, false)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, handler)
        }
    }

    private data class StrokePoint(val action: Int, val x: Float, val y: Float)

    companion object {
        @Volatile
        var instance: InputAccessibilityService? = null
    }
}
