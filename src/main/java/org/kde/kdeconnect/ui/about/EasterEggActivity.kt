/*
 * SPDX-FileCopyrightText: 2021 Maxim Leshchenko <cnmaks90@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.about

import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.widget.ImageViewCompat
import org.kde.kdeconnect.base.KdeActivity
import org.kde.kdeconnect.ui.views.ViewBuilders.dp
import org.kde.kdeconnect_tp.R
import kotlin.math.PI
import kotlin.math.atan2

private val KDE_ICON_BACKGROUND_COLOR = Color.rgb(29, 153, 243)
private val KONQI_BACKGROUND_COLOR = Color.rgb(191, 255, 0)

/** The KDE logo kept upright by the accelerometer; a long press swaps in a random app icon (or Konqi). */
class EasterEggActivity : KdeActivity(), SensorEventListener {

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as? SensorManager }
    private val accelerometer by lazy { sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    private lateinit var root: FrameLayout
    private lateinit var image: ImageView
    private var angleText: TextView? = null

    private val icons = intArrayOf(
        R.drawable.ic_action_keyboard_24dp, R.drawable.ic_action_refresh_24dp,
        R.drawable.ic_action_image_edit_24dp, R.drawable.ic_arrow_upward_black_24dp,
        R.drawable.ic_baseline_attach_money_24, R.drawable.ic_baseline_bug_report_24,
        R.drawable.ic_baseline_code_24, R.drawable.ic_baseline_gavel_24,
        R.drawable.ic_baseline_info_24, R.drawable.ic_baseline_web_24,
        R.drawable.ic_baseline_send_24, R.drawable.ic_baseline_sms_24,
        R.drawable.ic_accept_pairing_24dp, R.drawable.ic_share_white,
        R.drawable.ic_delete,
        R.drawable.ic_device_laptop_32dp, R.drawable.ic_device_phone_32dp,
        R.drawable.ic_device_tablet_32dp, R.drawable.ic_device_tv_32dp,
        R.drawable.ic_delete, R.drawable.ic_warning,
        R.drawable.ic_volume, R.drawable.ic_wifi,
        R.drawable.ic_add, R.drawable.touchpad_plugin_action_24dp,
        R.drawable.konqi, R.drawable.run_command_plugin_icon_24dp,
        R.drawable.ic_phonelink_36dp, R.drawable.ic_phonelink_off_36dp,
        R.drawable.ic_error_outline_48dp, R.drawable.ic_home_black_24dp,
        R.drawable.ic_settings_white_32dp, R.drawable.ic_stop,
        R.drawable.ic_rewind_black, R.drawable.ic_play_black,
        R.drawable.ic_mic_black, R.drawable.ic_pause_black,
        R.drawable.ic_volume_mute, R.drawable.ic_arrow_upward_black_24dp,
        R.drawable.ic_next_black, R.drawable.ic_previous_black,
        R.drawable.ic_presenter_24dp, R.drawable.ic_key,
        R.drawable.ic_keyboard_return_black_24dp, R.drawable.ic_keyboard_hide_36dp,
        R.drawable.ic_kde_24dp, R.drawable.ic_album_art_placeholder,
        R.drawable.ic_arrow_back_black_24dp, R.drawable.share_plugin_action_24dp
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR

        image = ImageView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(image, LinearLayout.LayoutParams(dp(context, 396), dp(context, 396)))
            if (accelerometer != null) {
                angleText = TextView(context).apply {
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    text = "0°"
                }
                addView(angleText)
            }
        }
        root = FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            isLongClickable = true
            setOnLongClickListener { setIcon(icons.random()); true }
        }
        setContentView(root)
        setIcon(R.drawable.ic_kde_48dp)
    }

    private fun setIcon(icon: Int) {
        val konqi = icon == R.drawable.konqi
        image.setImageResource(icon)
        ImageViewCompat.setImageTintList(image, if (konqi) null else ColorStateList.valueOf(Color.WHITE))
        val background = if (konqi) KONQI_BACKGROUND_COLOR else KDE_ICON_BACKGROUND_COLOR
        root.setBackgroundColor(background)
        val barStyle = if (!konqi) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val angle = (atan2(event.values[0], event.values[1]) / (PI / 180)).toFloat()
        image.animate().rotation(angle).setDuration(300).start()
        angleText?.text = "${angle.toInt()}°"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
