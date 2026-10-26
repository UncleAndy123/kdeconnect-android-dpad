/*
 * SPDX-FileCopyrightText: 2023 Dmitry Yudin <dgyudin@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.mousepad

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.base.KdeActivity
import org.kde.kdeconnect.ui.keypad.KeypadSoftkeys
import org.kde.kdeconnect.ui.keypad.SoftKey
import org.kde.kdeconnect.ui.views.ViewBuilders.dp
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityPlainBinding
import com.google.android.material.R as MR

private const val INPUT_CACHE_KEY = "compose_send_input_cache"

/** Type a text on the phone and send it to the desktop in one go. Plain views (this used to be Compose). */
class ComposeSendActivity : KdeActivity() {
    private lateinit var deviceId: String
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private lateinit var binding: ActivityPlainBinding
    private lateinit var field: TextInputEditText
    private lateinit var sendButton: MaterialButton
    private lateinit var clearButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)!!
        binding = ActivityPlainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.compose_send_title)
        }
        // Clear in the toolbar (a text button, as before)
        clearButton = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.clear_compose)
            setOnClickListener { clearComposeInput() }
        }
        binding.toolbarLayout.toolbar.addView(clearButton, androidx.appcompat.widget.Toolbar.LayoutParams(
            androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT, androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT, Gravity.END
        ).apply { marginEnd = dp(this@ComposeSendActivity, 8) })

        val layout = TextInputLayout(this, null, MR.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.click_here_to_type)
        }
        field = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 6
            setText(savedInstanceState?.getString(INPUT_CACHE_KEY) ?: prefs.getString(INPUT_CACHE_KEY, null))
            setSelection(text?.length ?: 0)
            // Keypad: UP/DOWN leave the field (to the next focus stop) instead of moving the cursor
            setOnKeyListener { v, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || !KeypadSoftkeys.isKeypadDevice(v.context)) return@setOnKeyListener false
                val dir = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                    else -> return@setOnKeyListener false
                }
                v.focusSearch(dir)?.requestFocus() ?: false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (::sendButton.isInitialized) sendButton.isEnabled = !s.isNullOrEmpty()
                }
            })
        }
        layout.addView(field)
        sendButton = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.send_compose)
            setIconResource(R.drawable.ic_baseline_send_24)
            isEnabled = !field.text.isNullOrEmpty()
            setOnClickListener { sendComposed() }
        }
        val pad = dp(this, 16)
        binding.content.addView(layout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START
        ).apply { setMargins(pad, 0, pad, dp(this@ComposeSendActivity, 80)) })
        binding.content.addView(sendButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END
        ).apply { setMargins(pad, pad, pad, pad) })
        field.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onResume() {
        super.onResume()
        // Keypad phones: LEFT = Clear, RIGHT = Send; the on-screen buttons go
        KeypadSoftkeys.bind(this,
            SoftKey(getString(R.string.clear_compose)) { clearComposeInput() },
            SoftKey(getString(R.string.send_compose)) { sendComposed() })
        val softkeys = KeypadSoftkeys.hasKeys(this)
        sendButton.visibility = if (softkeys) View.GONE else View.VISIBLE
        clearButton.visibility = if (softkeys) View.GONE else View.VISIBLE
        (field.parent.parent as? View)?.let { box ->
            (box.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.bottomMargin = dp(this, if (softkeys) 8 else 80)
                box.layoutParams = it
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(INPUT_CACHE_KEY, field.text?.toString())
    }

    override fun onStop() {
        super.onStop()
        val text = field.text?.toString().orEmpty()
        prefs.edit {
            if (text.isNotBlank()) putString(INPUT_CACHE_KEY, text)
            else remove(INPUT_CACHE_KEY)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun sendComposed() {
        val plugin = KdeConnect.getInstance().getDevicePlugin<MousePadPlugin>(deviceId)
        if (plugin == null) {
            finish()
            return
        }
        plugin.sendText(field.text?.toString().orEmpty())
        clearComposeInput()
    }

    private fun clearComposeInput() {
        field.setText("")
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
    }
}
