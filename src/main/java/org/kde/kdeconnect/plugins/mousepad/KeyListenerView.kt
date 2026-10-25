/*
 * SPDX-FileCopyrightText: 2014 Saikrishna Arcot <saiarcot895@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.mousepad

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.SparseIntArray
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket

class KeyListenerView(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var deviceId: String? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setDeviceId(id: String) {
        deviceId = id
    }

    /**
     * Keypad pointer mode, for phones without a touchscreen: the keypad drives the remote
     * pointer instead of typing. The IME is detached so it can't swallow the number keys.
     */
    var pointerMode = false
        set(value) {
            if (field == value) return
            field = value
            stopMoving()
            if (dragging) {
                dragging = false
                plugin()?.sendSingleRelease()
            }
            context.getSystemService<InputMethodManager>()?.let { imm ->
                if (value) imm.hideSoftInputFromWindow(windowToken, 0)
                imm.restartInput(this)
            }
            onPointerModeChanged?.invoke(value)
        }
    var onPointerModeChanged: ((Boolean) -> Unit)? = null

    private val heldDirections = LinkedHashSet<Int>()
    private var moveTicks = 0
    private var dragging = false
    private val moveHandler = Handler(Looper.getMainLooper())
    private val moveRunnable = object : Runnable {
        override fun run() {
            var dx = 0
            var dy = 0
            for (key in heldDirections) {
                when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_4 -> dx -= 1
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_6 -> dx += 1
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_2 -> dy -= 1
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_8 -> dy += 1
                }
            }
            if (dx == 0 && dy == 0) return
            // Start precise, accelerate while held
            val speed = (POINTER_START_SPEED + moveTicks * POINTER_ACCELERATION).coerceAtMost(POINTER_MAX_SPEED)
            moveTicks++
            plugin()?.sendMouseDelta(dx * speed, dy * speed)
            moveHandler.postDelayed(this, POINTER_TICK_MS)
        }
    }

    private fun stopMoving() {
        heldDirections.clear()
        moveTicks = 0
        moveHandler.removeCallbacks(moveRunnable)
    }

    override fun onDetachedFromWindow() {
        stopMoving()
        super.onDetachedFromWindow()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) stopMoving()
    }

    private fun plugin() = KdeConnect.getInstance().getDevicePlugin<MousePadPlugin>(deviceId)

    private fun isDirectionKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_8 -> true
        else -> false
    }

    private fun isPointerKey(keyCode: Int) = isDirectionKey(keyCode) || when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_5,
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND -> true
        else -> false
    }

    private fun onPointerKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isPointerKey(keyCode)) return false // Back, Menu, soft keys, volume: let the system have them
        when {
            isDirectionKey(keyCode) -> if (heldDirections.add(keyCode) && heldDirections.size == 1) {
                moveTicks = 0
                moveHandler.post(moveRunnable)
            }
            // Scroll repeats while held
            keyCode == KeyEvent.KEYCODE_1 -> plugin()?.sendScroll(0.0, POINTER_SCROLL_STEP)
            keyCode == KeyEvent.KEYCODE_7 -> plugin()?.sendScroll(0.0, -POINTER_SCROLL_STEP)
        }
        return true
    }

    private fun onPointerKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isPointerKey(keyCode)) return false
        if (isDirectionKey(keyCode)) {
            heldDirections.remove(keyCode)
            if (heldDirections.isEmpty()) stopMoving()
            return true
        }
        val plugin = plugin() ?: return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_5 -> {
                if (dragging) {
                    dragging = false
                    plugin.sendSingleRelease()
                } else {
                    plugin.sendLeftClick()
                }
            }
            KeyEvent.KEYCODE_STAR -> plugin.sendRightClick()
            KeyEvent.KEYCODE_3 -> plugin.sendMiddleClick()
            KeyEvent.KEYCODE_9 -> plugin.sendDoubleClick()
            KeyEvent.KEYCODE_0 -> {
                dragging = !dragging
                if (dragging) plugin.sendSingleHold() else plugin.sendSingleRelease()
            }
            KeyEvent.KEYCODE_POUND -> pointerMode = false
        }
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (pointerMode) return null
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        return KeyInputConnection(this, true)
    }

    override fun onCheckIsTextEditor(): Boolean = !pointerMode

    fun sendChars(chars: CharSequence) {
        KdeConnect.getInstance().getDevicePlugin<MousePadPlugin>(deviceId)
            ?.sendText(chars.toString())
    }

    private fun sendKeyPressPacket(np: NetworkPacket) {
        KdeConnect.getInstance().getDevicePlugin<MousePadPlugin>(deviceId)
            ?.sendPacket(np)
    }

    fun sendSpecialKey(specialKey: Int) {
        val np = NetworkPacket(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST)
        np.set("specialKey", specialKey)
        sendKeyPressPacket(np)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (pointerMode) return onPointerKeyDown(keyCode, event)
        // consume events that otherwise would move the focus away from us
        return keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (pointerMode) return onPointerKeyUp(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // We don't want to swallow the back button press
            return false
        }

        // NOTE: Most keyboards, and specifically the Android default keyboard when
        // entering non-ascii characters, will not trigger KeyEvent events as documented
        // here: http://developer.android.com/reference/android/view/KeyEvent.html

        // Log.e("KeyDown", "------------")
        // Log.e("KeyDown", "keyChar:" + (int) event.getDisplayLabel())
        // Log.e("KeyDown", "utfChar:" + (char)event.getUnicodeChar())
        // Log.e("KeyDown", "intUtfChar:" + event.getUnicodeChar())

        val np = NetworkPacket(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST)

        var modifier = false
        if (event.isAltPressed) {
            np.set("alt", true)
            modifier = true
        }

        if (event.isCtrlPressed) {
            np.set("ctrl", true)
            modifier = true
        }

        if (event.isShiftPressed) {
            np.set("shift", true)
        }

        if (event.isMetaPressed) {
            np.set("super", true)
            modifier = true
        }

        val specialKey = SpecialKeysMap.get(keyCode, -1)

        if (specialKey != -1) {
            np.set("specialKey", specialKey)
        } else if (event.displayLabel.code != 0 && modifier) {
            // Alt will change the utf symbol to non-ascii characters, we want the plain original letter
            // Since getDisplayLabel will always have a value, we have to check for special keys before
            val keyCharacter = event.displayLabel
            np.set("key", keyCharacter.toString().lowercase())
        } else if (event.unicodeChar != 0) {
            // A normal key, but still not handled by the KeyInputConnection (happens with numbers)
            np.set("key", event.unicodeChar.toChar().toString())
        } else {
            // No character (OK, soft keys, volume, call...): don't send a NUL key to the desktop
            return false
        }

        sendKeyPressPacket(np)
        return true
    }

    companion object {
        private const val POINTER_TICK_MS = 25L
        private const val POINTER_START_SPEED = 2f
        private const val POINTER_ACCELERATION = 0.5f
        private const val POINTER_MAX_SPEED = 24f
        private const val POINTER_SCROLL_STEP = 3.0

        val SpecialKeysMap = SparseIntArray().apply {
            var i = 0
            put(KeyEvent.KEYCODE_DEL, ++i) // 1
            put(KeyEvent.KEYCODE_TAB, ++i) // 2
            put(KeyEvent.KEYCODE_ENTER, 12)
            ++i // 3 is not used, return is 12 instead
            put(KeyEvent.KEYCODE_DPAD_LEFT, ++i) // 4
            put(KeyEvent.KEYCODE_DPAD_UP, ++i) // 5
            put(KeyEvent.KEYCODE_DPAD_RIGHT, ++i) // 6
            put(KeyEvent.KEYCODE_DPAD_DOWN, ++i) // 7
            put(KeyEvent.KEYCODE_PAGE_UP, ++i) // 8
            put(KeyEvent.KEYCODE_PAGE_DOWN, ++i) // 9
            put(KeyEvent.KEYCODE_MOVE_HOME, ++i) // 10
            put(KeyEvent.KEYCODE_MOVE_END, ++i) // 11
            put(KeyEvent.KEYCODE_NUMPAD_ENTER, ++i) // 12
            put(KeyEvent.KEYCODE_FORWARD_DEL, ++i) // 13
            put(KeyEvent.KEYCODE_ESCAPE, ++i) // 14
            put(KeyEvent.KEYCODE_SYSRQ, ++i) // 15
            put(KeyEvent.KEYCODE_SCROLL_LOCK, ++i) // 16
            put(KeyEvent.KEYCODE_CTRL_LEFT, ++i) // 17
            put(KeyEvent.KEYCODE_ALT_LEFT, ++i) // 18
            put(KeyEvent.KEYCODE_SHIFT_LEFT, ++i) // 19
            put(KeyEvent.KEYCODE_META_LEFT, ++i) // 20
            put(KeyEvent.KEYCODE_F1, ++i) // 21
            put(KeyEvent.KEYCODE_F2, ++i) // 22
            put(KeyEvent.KEYCODE_F3, ++i) // 23
            put(KeyEvent.KEYCODE_F4, ++i) // 24
            put(KeyEvent.KEYCODE_F5, ++i) // 25
            put(KeyEvent.KEYCODE_F6, ++i) // 26
            put(KeyEvent.KEYCODE_F7, ++i) // 27
            put(KeyEvent.KEYCODE_F8, ++i) // 28
            put(KeyEvent.KEYCODE_F9, ++i) // 29
            put(KeyEvent.KEYCODE_F10, ++i) // 30
            put(KeyEvent.KEYCODE_F11, ++i) // 31
            put(KeyEvent.KEYCODE_F12, ++i) // 32
        }
    }
}
