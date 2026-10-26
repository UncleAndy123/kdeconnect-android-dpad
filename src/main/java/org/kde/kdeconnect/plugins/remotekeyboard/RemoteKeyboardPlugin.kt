/*
 * SPDX-FileCopyrightText: 2026 Johann Specht <sajeg.dev@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.remotekeyboard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
import androidx.fragment.app.DialogFragment
import org.kde.kdeconnect.KdeConnectAccessibilityService
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.plugins.remotekeyboardime.RemoteKeyboardIMEPlugin
import org.kde.kdeconnect.plugins.remotekeyboardime.RemoteKeyboardService
import org.kde.kdeconnect.ui.StartActivityAlertDialogFragment
import org.kde.kdeconnect_tp.R


@LoadablePlugin
class RemoteKeyboardPlugin : Plugin() {
    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_remotekeyboard_accessibility)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_remotekeyboard_desc)

    override val permissionExplanationDialog: DialogFragment
        get() = StartActivityAlertDialogFragment.Builder()
            .setTitle(R.string.pref_plugin_remotekeyboard_desc)
            .setMessage(R.string.no_permissions_remotekeyboard_accessibility)
            .setPositiveButton(R.string.open_accessibility_settings)
            .setNegativeButton(R.string.cancel)
            .setIntentAction(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .setReloadPluginsOnResult(true)
            .create()

    companion object {
        const val PACKET_TYPE_MOUSEPAD_REQUEST: String = "kdeconnect.mousepad.request"
        const val PACKET_TYPE_MOUSEPAD_ECHO: String = "kdeconnect.mousepad.echo"
        const val PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE: String = "kdeconnect.mousepad.keyboardstate"

        private const val LOG_TAG = "RemoteKeyboardPlugin"
    }

    override fun checkRequiredPermissions(): Boolean {
        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )

        return enabledServices.any { enabledService ->
            val serviceInfo = enabledService.resolveInfo.serviceInfo
            serviceInfo.packageName == context.packageName &&
                serviceInfo.name == KdeConnectAccessibilityService::class.java.name
        }
    }

    override fun onCreate() {
        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE)
        np["state"] = true
        device.sendPacket(np)
    }

    override fun onDestroy() {
        if (device.getPlugin<RemoteKeyboardIMEPlugin>()?.isKeyboardAvailable == true) {
            return
        }

        val np = NetworkPacket(PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE)
        np["state"] = false
        device.sendPacket(np)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        // Check if this package is for the Mouse Receiver
        if (!(np.has("key") || np.has("specialKey"))) {
            return false
        }

        if (RemoteKeyboardService.instance?.acceptsKeys == true) {
            return false // This packet will be handled by the RemoteKeyboardIMEPlugin instead, silently ignore
        }

        if (!handleKey(np)) {
            Log.i(LOG_TAG, "Could not handle event!")
            return false
        }

        if (np.getBoolean("sendAck")) {
            sendAck(np)
        }

        return true
    }

    private fun sendAck(np: NetworkPacket) {
        val reply = NetworkPacket(PACKET_TYPE_MOUSEPAD_ECHO)
        reply["key"] = np.getString("key")
        if (np.has("specialKey")) {
            reply["specialKey"] = np.getInt("specialKey")
        }
        if (np.has("shift")) {
            reply["shift"] = np.getBoolean("shift")
        }
        if (np.has("ctrl")) {
            reply["ctrl"] = np.getBoolean("ctrl")
        }
        if (np.has("alt")) {
            reply["alt"] = np.getBoolean("alt")
        }
        if (np.has("super")) {
            reply["super"] = np.getBoolean("super")
        }
        reply["isAck"] = true
        device.sendPacket(reply)
    }

    private fun handleKey(np: NetworkPacket): Boolean {
        val key = np.getString("key")
        val sKey = np.getInt("specialKey")

        if (key == "" && sKey == 0) {
            return false
        }

        val focus = KdeConnectAccessibilityService.instance?.findFocus(FOCUS_INPUT)
        if (focus == null) {
            val specialKey = SpecialKeys.fromInt(sKey)
            // Below Android 13 there are no D-pad global actions, so arrows grab initial focus and
            // Esc acts as Back, keeping D-pad-only (keypad) devices navigable
            val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            if (legacy && specialKey == SpecialKeys.ESCAPE) {
                return KdeConnectAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true
            }
            if (specialKey == SpecialKeys.TAB || (legacy && (specialKey == SpecialKeys.DPAD_LEFT || specialKey == SpecialKeys.DPAD_RIGHT
                || specialKey == SpecialKeys.DPAD_UP || specialKey == SpecialKeys.DPAD_DOWN))) {
                KdeConnectAccessibilityService.instance?.findNodeByAction(
                    KdeConnectAccessibilityService.instance?.rootInActiveWindow,
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS
                )?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                return true
            }
            return false
        }

        val shift = np.getBoolean("shift")
        val ctrl = np.getBoolean("ctrl")
        if (shift || ctrl) {
            multiKey(focus, key, sKey, shift, ctrl)
        } else if (sKey != 0) {
            specialKey(focus, sKey)
        } else {
            keyInput(focus, key)
        }
        return true
    }

    override val supportedPacketTypes: Array<String> =
        arrayOf(PACKET_TYPE_MOUSEPAD_REQUEST)

    override val outgoingPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_MOUSEPAD_ECHO,
        PACKET_TYPE_MOUSEPAD_KEYBOARDSTATE
    )

    private fun multiKey(
        focus: AccessibilityNodeInfo,
        key: String,
        sKey: Int,
        shift: Boolean,
        ctrl: Boolean
    ) {
        val specialKey = SpecialKeys.fromInt(sKey)

        if (shift) {
            val movement = if (ctrl) Movement.WORD else Movement.CHARACTER
            when (specialKey) {
                SpecialKeys.TAB -> moveFocus(forward = false, focus)

                SpecialKeys.DPAD_LEFT -> moveCursor(
                    focus,
                    forward = false,
                    movement,
                    makeSelection = true
                )

                SpecialKeys.DPAD_RIGHT -> moveCursor(
                    focus,
                    forward = true,
                    movement,
                    makeSelection = true
                )

                SpecialKeys.MOVE_HOME, SpecialKeys.DPAD_UP -> moveCursor(
                    focus,
                    forward = false,
                    Movement.LINE,
                    makeSelection = true
                )

                SpecialKeys.MOVE_END, SpecialKeys.DPAD_DOWN -> moveCursor(
                    focus,
                    forward = true,
                    Movement.LINE,
                    makeSelection = true
                )

                SpecialKeys.ENTER -> keyInput(focus, "\n")

                // We already get the letters capitalized or as special characters.
                SpecialKeys.NO_KEY -> keyInput(focus, key)

                else -> {}
            }
        } else if (ctrl) {
            when (key) {
                "c" -> focus.performAction(AccessibilityNodeInfo.ACTION_COPY)
                "v" -> focus.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                "x" -> focus.performAction(AccessibilityNodeInfo.ACTION_CUT)
                "a" -> focus.setSelection(0, focus.text?.length ?: 0)
            }
            when (specialKey) {
                SpecialKeys.DPAD_LEFT -> moveCursor(focus, forward = false, Movement.WORD)
                SpecialKeys.DPAD_RIGHT -> moveCursor(focus, forward = true, Movement.WORD)
                SpecialKeys.DEL -> delete(focus, forward = false, words = true)
                SpecialKeys.FORWARD_DEL -> delete(focus, forward = true, words = true)
                SpecialKeys.ENTER -> {
                    if (focus.isEditable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        focus.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        KdeConnectAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
                    } else {
                        legacyEnter(focus)
                    }
                }
                else -> {}
            }
        }
    }

    private fun specialKey(focus: AccessibilityNodeInfo, sKey: Int) {
        val specialKey = SpecialKeys.fromInt(sKey)

        when (specialKey) {
            SpecialKeys.TAB -> moveFocus(forward = true, focus)

            SpecialKeys.DEL -> delete(focus, false)

            SpecialKeys.FORWARD_DEL -> delete(focus, true)

            SpecialKeys.MOVE_END -> moveCursor(focus, forward = true, Movement.LINE)

            SpecialKeys.MOVE_HOME -> moveCursor(focus, forward = false, Movement.LINE)

            SpecialKeys.DPAD_LEFT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                KdeConnectAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_LEFT)
            } else {
                legacyArrow(focus, specialKey)
            }

            SpecialKeys.DPAD_RIGHT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                KdeConnectAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT)
            } else {
                legacyArrow(focus, specialKey)
            }

            SpecialKeys.DPAD_UP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                KdeConnectAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_UP)
            } else {
                legacyArrow(focus, specialKey)
            }

            SpecialKeys.DPAD_DOWN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                KdeConnectAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_DOWN)
            } else {
                legacyArrow(focus, specialKey)
            }

            SpecialKeys.ENTER -> {
                if (focus.isEditable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    KdeConnectAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    focus.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else {
                    legacyEnter(focus)
                }
            }

            SpecialKeys.ESCAPE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    KdeConnectAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                } else if (focus.isEditable) {
                    focus.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                } else {
                    KdeConnectAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }
            }

            else -> {}
        }
    }

    /**
     * Pre-Android 11 there is no IME_ENTER / DPAD_CENTER for accessibility services:
     * insert a newline in multi-line fields, otherwise click the focused node.
     */
    private fun legacyEnter(focus: AccessibilityNodeInfo) {
        if (focus.isEditable && focus.isMultiLine) {
            keyInput(focus, "\n")
        } else {
            focus.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    /**
     * Before Android 13 accessibility services can't send D-pad presses. Arrows move the
     * cursor inside text (up/down only in multi-line fields); otherwise they move input
     * focus in that direction, so focus-based UIs (keypad phones, TVs) stay navigable.
     */
    private fun legacyArrow(focus: AccessibilityNodeInfo, key: SpecialKeys) {
        val vertical = key == SpecialKeys.DPAD_UP || key == SpecialKeys.DPAD_DOWN
        val forward = key == SpecialKeys.DPAD_RIGHT || key == SpecialKeys.DPAD_DOWN
        if (focus.isEditable && (!vertical || focus.isMultiLine)) {
            moveCursor(focus, forward, if (vertical) Movement.LINE else Movement.CHARACTER)
            return
        }
        val direction = when (key) {
            SpecialKeys.DPAD_LEFT -> View.FOCUS_LEFT
            SpecialKeys.DPAD_RIGHT -> View.FOCUS_RIGHT
            SpecialKeys.DPAD_UP -> View.FOCUS_UP
            else -> View.FOCUS_DOWN
        }
        focus.focusSearch(direction)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun moveFocus(
        forward: Boolean,
        focus: AccessibilityNodeInfo
    ) {
        val direction = if (forward) View.FOCUS_FORWARD else View.FOCUS_BACKWARD
        val next = focus.focusSearch(direction)
            ?: return
        next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun keyInput(focus: AccessibilityNodeInfo, key: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && focus.isEditable) {
            val inputConnection = KdeConnectAccessibilityService.instance
                ?.inputMethod?.currentInputConnection
            if (inputConnection != null) {
                // Use the editor's input pipeline instead of replacing the entire field.
                inputConnection.commitText(key, 1, null)
                return true
            }
        }

        val text = getFieldText(focus) ?: ""
        val arguments = Bundle()
        val selectionStart = focus.textSelectionStart
        val selectionEnd = focus.textSelectionEnd
        val selectionFirst = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val selectionLast = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)

        val newTextPartOne = text.substring(0, selectionFirst)
        val newTextPartTwo = text.substring(selectionLast)
        val newText = newTextPartOne + key + newTextPartTwo

        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            newText
        )

        val wasPerformed = focus.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            arguments
        )
        if (!wasPerformed) {
            return false
        }

        if (text != "") {
            moveCursorToPos(selectionFirst + key.length, focus)
        }

        return true
    }

    private fun delete(focus: AccessibilityNodeInfo, forward: Boolean, words: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && focus.isEditable) {
            val inputConnection = KdeConnectAccessibilityService.instance
                ?.inputMethod?.currentInputConnection
            if (inputConnection != null) {
                // Let the editor handle selections, word boundaries and multi-character glyphs.
                val keyCode = if (forward) KeyEvent.KEYCODE_FORWARD_DEL else KeyEvent.KEYCODE_DEL
                val metaState = if (words) KeyEvent.META_CTRL_ON else 0
                val now = SystemClock.uptimeMillis()
                val event = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
                inputConnection.sendKeyEvent(event)
                inputConnection.sendKeyEvent(KeyEvent.changeAction(event, KeyEvent.ACTION_UP))
                return
            }
        }

        val text = getFieldText(focus)
            ?: return
        val selectionStart = focus.textSelectionStart
        val selectionEnd = focus.textSelectionEnd
        val selectionFirst = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val selectionLast = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val hasSelection = selectionFirst != selectionLast

        var dropAmount =
            if (hasSelection) {
                0
            } else if (words && forward) {
                text.indexOf(' ', selectionFirst + 1, true) - selectionFirst
            } else if (words) {
                if (selectionFirst == 0) {
                    0
                } else {
                    selectionFirst - text.substring(0, selectionFirst - 1)
                        .indexOfLast { c -> c.isWhitespace() } - 1
                }
            } else {
                1
            }

        // indexOf returns -1 if no preceding space exists, which correctly
        // causes dropAmount to equal selectionStart (delete to beginning of field)
        if (dropAmount < 0 && forward) {
            dropAmount = text.length - selectionFirst

        }

        val newTextPartOne = text.substring(0, selectionFirst)
        val newTextPartTwo = text.substring(selectionLast, text.length)
        val newText =
            if (forward) {
                newTextPartOne + newTextPartTwo.drop(dropAmount)
            } else {
                newTextPartOne.dropLast(dropAmount) + newTextPartTwo
            }

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
        }

        focus.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            arguments
        )

        val moveCursorBy =
            if (forward) {
                0
            } else {
                -dropAmount
            }

        if (hasSelection) {
            moveCursorToPos(selectionFirst, focus)
        } else {
            moveCursorToPos(selectionFirst + moveCursorBy, focus)
        }
    }

    private fun moveCursorToPos(
        pos: Int,
        focus: AccessibilityNodeInfo
    ) {
        val pos = if (pos < 0) 0 else pos
        focus.setSelection(pos, pos)
    }

    private fun AccessibilityNodeInfo.setSelection(
        start: Int,
        end: Int,
    ) {
        this.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        })
    }

    private fun moveCursor(
        focus: AccessibilityNodeInfo,
        forward: Boolean,
        movement: Movement = Movement.CHARACTER,
        makeSelection: Boolean = false
    ) {
        val args = Bundle().apply {
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                when (movement) {
                    Movement.WORD -> AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
                    Movement.LINE -> AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
                    else -> AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
                }
            )
            putBoolean(
                AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN,
                makeSelection
            )
        }

        focus.performAction(
            if (forward) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY.id
            } else {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY.id
            },
            args
        )
    }

    private fun getFieldText(focus: AccessibilityNodeInfo): String? {
        // isShowingHintText is API 26+; older releases rely on the selection check below
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focus.isShowingHintText) return null
        // Fallback: some apps return hint as real text but report selection -1,-1
        if (focus.textSelectionStart == -1 && focus.textSelectionEnd == -1) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && isLegacyHint(focus)) return null
        return focus.text?.toString()
    }

    /**
     * Before API 26 an empty TextView reports its hint as text, with the cursor at 0,0.
     * TextView validates ACTION_SET_SELECTION against its real text, so a selection of
     * 1,1 is refused when the field is actually empty. Only probe nodes that advertise
     * the action, so fields that don't support it keep their text.
     */
    private fun isLegacyHint(focus: AccessibilityNodeInfo): Boolean {
        if (!focus.isEditable || focus.text.isNullOrEmpty()) return false
        if (focus.textSelectionStart != 0 || focus.textSelectionEnd != 0) return false
        if (!focus.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)) return false
        if (!focus.setSelectionResult(1, 1)) return true
        focus.setSelection(0, 0)
        return false
    }

    private fun AccessibilityNodeInfo.setSelectionResult(start: Int, end: Int): Boolean =
        performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        })

}
