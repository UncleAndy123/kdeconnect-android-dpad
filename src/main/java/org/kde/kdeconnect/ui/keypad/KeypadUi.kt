/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.keypad

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.InputDevice
import android.view.Menu
import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import com.theonionsarewatching.yapchik.Softkeys
import com.theonionsarewatching.yapchik.Yapchik
import org.kde.kdeconnect_tp.R
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Scales the app's density on tiny (feature-phone) screens so layouts get at least
 * [MIN_WIDTH_DP] of logical width. Normal screens are untouched. Same approach as
 * alltechdev/vela-dpad's AdaptiveDensity. Applied from [org.kde.kdeconnect.base.KdeActivity].
 */
object AdaptiveDensity {
    const val MIN_WIDTH_DP = 360

    /** True once a screen was shrunk, i.e. this is a feature-phone-sized display. Use this, not
     * screenWidthDp/screenHeightDp, which report the inflated size after wrapping. */
    @Volatile
    var applied: Boolean = false
        private set

    fun wrap(base: Context): Context {
        val cfg = base.resources.configuration
        val widthDp = cfg.screenWidthDp
        if (widthDp <= 0 || widthDp >= MIN_WIDTH_DP) return base
        applied = true
        val scale = widthDp.toFloat() / MIN_WIDTH_DP
        val out = Configuration(cfg).apply {
            densityDpi = (cfg.densityDpi * scale).roundToInt().coerceAtLeast(1)
            screenWidthDp = (screenWidthDp / scale).roundToInt()
            screenHeightDp = (screenHeightDp / scale).roundToInt()
            smallestScreenWidthDp = (smallestScreenWidthDp / scale).roundToInt()
        }
        return base.createConfigurationContext(out)
    }

    fun isSmallScreen(context: Context): Boolean = applied || context.resources.configuration.screenWidthDp < 320
}

/** One entry of a screen's softkey Options menu (for Compose screens without an options menu). */
data class SoftkeyOption(val label: String, val onClick: () -> Unit)

/**
 * Keypad phones: the hardware soft keys replace on-screen chrome. LEFT opens an Options popup
 * holding what the toolbar / overflow / FABs held, RIGHT is Back, and those on-screen controls
 * are hidden to give the content room. Only active on keypad devices (Yapchik AUTO detection);
 * touch phones are untouched.
 */
object KeypadSoftkeys {
    /** Compose mirror of the softkey bar's on/off state. */
    private val activeState = mutableStateOf(false)

    val isActive: Boolean get() = Yapchik.isInstalled && Yapchik.isActive

    private val composeOptions = WeakHashMap<Activity, List<SoftkeyOption>>()
    private val extraOptions = WeakHashMap<Activity, List<SoftkeyOption>>()
    private val hiddenActionBars = WeakHashMap<Activity, Boolean>()

    /**
     * Keypad-first device rule, same as alltechdev/vela-dpad's detectDpadFirst: no touchscreen,
     * or a real (non-virtual) D-pad. `adb shell settings put global kdeconnect_force_dpad 1`
     * forces it on for testing on a touch phone.
     */
    fun isKeypadDevice(context: Context): Boolean {
        val forced = runCatching {
            Settings.Global.getInt(context.contentResolver, "kdeconnect_force_dpad", 0) == 1
        }.getOrDefault(false)
        if (forced) return true
        val noTouch = !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        val hasPhysicalDpad = runCatching {
            InputDevice.getDeviceIds().any { id ->
                val dev = InputDevice.getDevice(id) ?: return@any false
                // Skip the framework's virtual aggregate device; it reports DPAD on every phone
                val virtual = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) dev.isVirtual else id < 0
                !virtual && (dev.sources and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
            }
        }.getOrDefault(false)
        return noTouch || hasPhysicalDpad
    }

    fun init(app: Application) {
        Yapchik.install(app)
        Yapchik.autoDetector = ::isKeypadDevice
        Yapchik.autoInsetContent = true
        Yapchik.defaults { activity, keys ->
            keys.left(activity.getString(R.string.softkey_options), visibleIf = { hasOptions(activity) }) {
                showOptions(activity)
            }
            keys.right(activity.getString(R.string.softkey_back)) { back(activity) }
        }
        activeState.value = isActive
        Yapchik.addStateListener { active ->
            activeState.value = active
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = updateActionBar(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** The toolbar's actions are in the LEFT Options popup and Back is RIGHT, so drop the toolbar. */
    private fun updateActionBar(activity: Activity) {
        val actionBar = (activity as? AppCompatActivity)?.supportActionBar ?: return
        if (isActive) {
            if (actionBar.isShowing) {
                actionBar.hide()
                hiddenActionBars[activity] = true
            }
        } else if (hiddenActionBars.remove(activity) == true) {
            actionBar.show()
        }
    }

    /** Extra Options entries a screen adds on top of its options menu (e.g. opening the drawer). */
    fun setExtraOptions(activity: Activity, options: List<SoftkeyOption>) {
        extraOptions[activity] = options
    }

    private fun back(activity: Activity) {
        if (activity is ComponentActivity) {
            activity.onBackPressedDispatcher.onBackPressed()
        } else {
            @Suppress("DEPRECATION")
            activity.onBackPressed()
        }
    }

    private fun buildMenu(activity: Activity, menu: Menu): List<SoftkeyOption> {
        val options = (extraOptions[activity] ?: emptyList()) + (composeOptions[activity] ?: emptyList())
        if (composeOptions[activity] == null) {
            // Same items the toolbar / overflow / Menu key would show, including fragments' menus
            activity.onCreatePanelMenu(Window.FEATURE_OPTIONS_PANEL, menu)
            activity.onPreparePanel(Window.FEATURE_OPTIONS_PANEL, null, menu)
        }
        options.forEachIndexed { i, option -> menu.add(Menu.NONE, OPTION_ID_BASE + i, i, option.label) }
        return options
    }

    private fun hasOptions(activity: Activity): Boolean {
        val probe = PopupMenu(activity, View(activity)).menu
        buildMenu(activity, probe)
        return (0 until probe.size()).any { probe.getItem(it).isVisible }
    }

    fun showOptions(activity: Activity) {
        val anchor = activity.findViewById<View>(android.R.id.content) ?: return
        val popup = PopupMenu(activity, anchor, Gravity.BOTTOM or Gravity.START)
        val options = buildMenu(activity, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            val index = item.itemId - OPTION_ID_BASE
            if (index in options.indices) {
                options[index].onClick()
                true
            } else {
                activity.onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL, item)
            }
        }
        popup.show()
    }

    /** True while the soft keys stand in for on-screen chrome; Compose screens hide bars/FABs. */
    @Composable
    fun rememberActive(): Boolean = activeState.value

    /**
     * Registers a Compose screen's toolbar/FAB actions as its LEFT Options entries.
     * Call from the screen (KdeTopAppBar does it for its actions).
     */
    @Composable
    fun Options(options: List<SoftkeyOption>) {
        val activity = LocalActivity.current ?: return
        SideEffect { composeOptions[activity] = options }
        DisposableEffect(activity, options.isEmpty()) {
            if (isActive) Softkeys.of(activity).refresh()
            onDispose { composeOptions.remove(activity) }
        }
    }

    private const val OPTION_ID_BASE = 0x5f00
}
