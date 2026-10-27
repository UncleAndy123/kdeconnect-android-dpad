/*
 * SPDX-FileCopyrightText: 2023 Dmitry Yudin <dgyudin@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.presenter

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.media.VolumeProviderCompat
import com.google.android.material.button.MaterialButton
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.base.KdeActivity
import org.kde.kdeconnect.ui.keypad.KeypadSoftkeys
import org.kde.kdeconnect.ui.keypad.SoftkeyOption
import org.kde.kdeconnect.ui.views.ViewBuilders
import org.kde.kdeconnect.ui.views.ViewBuilders.dp
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityPlainBinding
import com.google.android.material.R as MR

private const val VOLUME_UP = 1
private const val VOLUME_DOWN = -1

class PresenterActivity : KdeActivity(), SensorEventListener, OnSharedPreferenceChangeListener {

    private val offScreenControlsSupported = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
    private val mediaSession by lazy {
        if (offScreenControlsSupported) MediaSessionCompat(this, "kdeconnect") else null
    }
    private lateinit var plugin : PresenterPlugin
    private var prefsApplied = false
    private var volumeKeys = false
    private lateinit var prefs: SharedPreferences
    private var sensitivity = 0.03f

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            val xPos = -event.values[2] * sensitivity
            val yPos = -event.values[0] * sensitivity

            plugin.sendPointer(xPos, yPos)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // ignored
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)!!
        val device = KdeConnect.getInstance().getDevice(deviceId)
        plugin = device?.getPlugin<PresenterPlugin>()
            ?: run {
                finish()
                return
            }

        prefs = plugin.preferences
        prefs.registerOnSharedPreferenceChangeListener(this)
        applyPrefs()

        buildUi(device.name)
        createMediaSession()
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(SENSOR_SERVICE) as? SensorManager)?.unregisterListener(this)
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        mediaSession?.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        //Only override dispatchKeyEvent if offScreenControls is not supported
        if (offScreenControlsSupported){
            return super.dispatchKeyEvent(event)
        }

        val keyCode = event.keyCode
        val action = event.action
        if (volumeKeys) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && action == KeyEvent.ACTION_UP) {
                plugin.sendPrevious()
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && action == KeyEvent.ACTION_UP) {
                plugin.sendNext()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun createMediaSession() {
        if (volumeKeys){
            mediaSession?.setPlaybackState(
                PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_PLAYING, 0, 0f).build()
            )
            mediaSession?.setPlaybackToRemote(volumeProvider)
            mediaSession?.setActive(true)
        }
    }

    private val volumeProvider = object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, 0, 0) {
        override fun onAdjustVolume(direction: Int) {
            if (direction == VOLUME_UP) {
                plugin.sendNext()
            } else if (direction == VOLUME_DOWN) {
                plugin.sendPrevious()
            }
        }
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences?,
        key: String?
    ) {
        if (prefsApplied) prefsApplied = false
    }

    private fun applyPrefs() {
        if (prefsApplied) return

        var scrollSensitivity = prefs.getInt(getString(R.string.pref_presenter_sensitivity), 50)
        scrollSensitivity += 10 // Do not allow near-zero sensitivity
        sensitivity = ((scrollSensitivity / 100f)/10f)*(6f/10f)

        volumeKeys = prefs.getBoolean(getString(R.string.pref_presenter_enable_volume_keys), true)

        prefsApplied = true
    }

    private lateinit var binding: ActivityPlainBinding
    private var keyPointerActive = false

    override fun onResume() {
        super.onResume()
        // Keypad: LEFT "Options" = Fullscreen / Exit presentation (the toolbar's menu on touch)
        KeypadSoftkeys.bind(this, KeypadSoftkeys.optionsKey(this, listOf(
            SoftkeyOption(getString(R.string.presenter_fullscreen)) { plugin.sendFullscreen() },
            SoftkeyOption(getString(R.string.presenter_exit)) { plugin.sendEsc() },
        )), null)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_FULLSCREEN, Menu.NONE, R.string.presenter_fullscreen)
        menu.add(Menu.NONE, MENU_EXIT, Menu.NONE, R.string.presenter_exit)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_FULLSCREEN -> { plugin.sendFullscreen(); true }
        MENU_EXIT -> { plugin.sendEsc(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun button(tonal: Boolean = false): MaterialButton =
        MaterialButton(this, null, if (tonal) MR.attr.materialButtonTonalStyle else MR.attr.materialButtonStyle).apply {
            cornerRadius = dp(context, 24)
            insetTop = 0
            insetBottom = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 0
            maxLines = 1
            // Keypad phones start in touch mode until their first key press: stay focusable then too
            isFocusableInTouchMode = KeypadSoftkeys.isKeypadDevice(context)
        }

    /** Volume-keys tip, big Previous / Next buttons and (with a gyroscope) the pointer button */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(deviceName: String) {
        binding = ActivityPlainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.pref_plugin_presenter)
            subtitle = deviceName
        }
        val gap = dp(this, 20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
        }
        if (volumeKeys) {
            column.addView(ViewBuilders.text(this, getString(
                if (offScreenControlsSupported) R.string.presenter_volume_keys_tip else R.string.presenter_volume_keys_foreground_tip
            ), MR.attr.textAppearanceBodyLarge, MR.attr.colorOnSurface).apply {
                setPadding(dp(context, 16), 0, dp(context, 16), dp(context, 8))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = gap
            })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val previous = button().apply {
            setIconResource(R.drawable.ic_arrow_back_black_24dp)
            contentDescription = getString(R.string.mpris_previous)
            setOnClickListener { plugin.sendPrevious(); it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        }
        val next = button().apply {
            setIconResource(R.drawable.ic_arrow_forward_black_24dp)
            contentDescription = getString(R.string.mpris_next)
            setOnClickListener { plugin.sendNext(); it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        }
        row.addView(previous, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = gap / 2 })
        row.addView(next, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = gap / 2 })
        column.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f))

        val sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        // The pointer needs a gyroscope; many keypad phones don't have one
        val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (sensorManager != null && gyroscope != null) {
            val pointer = button(tonal = true).apply {
                text = getString(R.string.presenter_pointer)
                // D-pad OK toggles the pointer; touch holds the button (below)
                setOnClickListener {
                    keyPointerActive = !keyPointerActive
                    if (keyPointerActive) {
                        sensorManager.registerListener(this@PresenterActivity, gyroscope, SensorManager.SENSOR_DELAY_GAME)
                    } else {
                        sensorManager.unregisterListener(this@PresenterActivity)
                        plugin.stopPointer()
                    }
                }
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            sensorManager.registerListener(this@PresenterActivity, gyroscope, SensorManager.SENSOR_DELAY_GAME)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            sensorManager.unregisterListener(this@PresenterActivity)
                            plugin.stopPointer()
                        }
                    }
                    true
                }
            }
            column.addView(pointer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = gap })
        }
        binding.content.addView(column)
        if (KeypadSoftkeys.isKeypadDevice(this)) previous.requestFocus()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        private const val MENU_FULLSCREEN = 1
        private const val MENU_EXIT = 2
    }
}
