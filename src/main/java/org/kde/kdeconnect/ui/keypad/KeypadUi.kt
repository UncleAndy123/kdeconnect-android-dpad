/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.keypad

import com.theonionsarewatching.yapchik.SoftkeyProfileChooser
import com.theonionsarewatching.yapchik.SoftkeyMode
import android.annotation.SuppressLint
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
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import com.google.android.material.color.MaterialColors
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
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

/** One entry of an Options menu. */
data class SoftkeyOption(val label: String, val onClick: () -> Unit)

/** One soft-key binding: a label and its press action (vela-dpad's VelaSoftkeys.Key). */
class SoftKey(val label: String, val onPress: () -> Unit)

/**
 * Hardware soft keys for keypad phones, the vela-dpad way (app/ui/softkey/VelaSoftkeys.kt):
 *  - CONTEXTUAL per screen: RIGHT is the screen's one primary action, LEFT is "Options" (a menu of
 *    every secondary action) or a direct action. BACK stays the hardware Back key.
 *  - Only screens that bind keys show a bar; everything else keeps its normal top bar.
 *  - On-screen controls a soft key covers are dropped (the screen checks [rememberActive] or the
 *    activity's bound state); while a screen has keys its toolbar is hidden.
 *  - A modal dialog hides the bar (its own buttons + BACK answer it); the Options menu keeps it.
 *  - Gated on the keypad detector, so touch phones are untouched.
 */
object KeypadSoftkeys {

    /** The engine resolved on (keypad device and not switched off in Settings). */
    val isActive: Boolean get() = Yapchik.isInstalled && Yapchik.isActive

    /** Settings: hardware soft keys on (AUTO: keypad phones only) or off everywhere. */
    var isEnabled: Boolean
        get() = Yapchik.mode != SoftkeyMode.OFF
        set(value) {
            Yapchik.mode = if (value) SoftkeyMode.AUTO else SoftkeyMode.OFF
        }

    /** Settings: press-your-keys calibration for phones whose soft keys send odd keycodes. */
    fun calibrate(activity: Activity) = SoftkeyProfileChooser.startCalibration(activity)

    private class Binding(val left: SoftKey?, val right: SoftKey?)
    private val bindings = WeakHashMap<Activity, Binding>()
    private val suspendedForModal = WeakHashMap<Activity, Boolean>()
    private val hiddenActionBars = WeakHashMap<Activity, Boolean>()
    private val drawerInsets = WeakHashMap<Activity, Int>()
    private val focusRings = WeakHashMap<Activity, ViewFocusRing>()
    private var menuOpen = false
    private var lastDark: Boolean? = null

    /**
     * Keypad-first device rule, same as vela-dpad's detectDpadFirst: no touchscreen, or a real
     * (non-virtual) D-pad. `adb shell settings put global kdeconnect_force_dpad 1` forces it on
     * for testing on a touch phone or emulator.
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
        // Reserve the bar's height by padding the content instead of covering it
        Yapchik.autoInsetContent = true
        // No nav-bar guard: our edge-to-edge UI has no vendor strip to clear (it made the bar double-tall)
        Yapchik.navGuardDp = 0
        Yapchik.style.apply {
            heightDp = 44
            textSizeSp = 15f
            bold = true
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!isKeypadDevice(activity)) return
                if (focusRings[activity] == null) {
                    focusRings[activity] = ViewFocusRing(activity).also { it.install() }
                    watchModals(activity)
                }
                updateActionBar(activity)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                bindings.remove(activity)
            }
        })
    }

    /** Bind this screen's soft keys (null = unbound; both null = no bar). */
    fun bind(activity: Activity, left: SoftKey?, right: SoftKey?) {
        if (left == null && right == null) bindings.remove(activity) else bindings[activity] = Binding(left, right)
        apply(activity)
    }

    fun clear(activity: Activity) = bind(activity, null, null)

    /** True when this activity currently shows soft keys (so its on-screen equivalents are hidden). */
    fun hasKeys(activity: Activity): Boolean = isActive && bindings[activity] != null

    private fun apply(activity: Activity) {
        if (!Yapchik.isInstalled) return
        val ctl = Softkeys.of(activity)
        val binding = bindings[activity]
        // The bar follows the app theme; Yapchik colours it at construction, so rebuild on a flip only
        val dark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val flipped = lastDark != null && lastDark != dark
        lastDark = dark
        applyThemeColors(activity)
        if (binding == null || suspendedForModal[activity] == true) {
            ctl.clear()
        } else {
            if (flipped) ctl.clear()
            ctl.set {
                noDefaults()
                binding.left?.let { k -> left(k.label) { k.onPress() } }
                binding.right?.let { k -> right(k.label) { k.onPress() } }
            }
        }
        insetDrawerContent(activity, isActive && binding != null && suspendedForModal[activity] != true)
        updateActionBar(activity)
    }

    /**
     * Yapchik makes room for the bar by padding the content root, but a DrawerLayout root ignores its
     * own padding, so the screens in it (device list, device, settings) would scroll their last rows
     * under the bar. Pad the drawer's main view instead.
     */
    private fun insetDrawerContent(activity: Activity, barShown: Boolean) {
        val drawer = activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? DrawerLayout ?: return
        val main = drawer.getChildAt(0) ?: return
        val want = if (barShown) Yapchik.barHeightPx(activity) else 0
        val had = drawerInsets[activity] ?: 0
        if (want == had) return
        main.setPadding(main.paddingLeft, main.paddingTop, main.paddingRight, main.paddingBottom - had + want)
        drawerInsets[activity] = want
    }

    private fun applyThemeColors(activity: Activity) {
        Yapchik.style.apply {
            backgroundColor = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurfaceContainerHighest, Color.DKGRAY)
            textColor = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
            pressedTextColor = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, Color.CYAN)
            dividerColor = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY)
        }
    }

    /**
     * A modal dialog is its own window: the soft keys go to it, so a bar behind its scrim would be
     * inert. Hide the bar while one is up; its buttons + BACK answer it. The Options menu keeps it.
     */
    private fun watchModals(activity: Activity) {
        activity.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { hasFocus ->
            val resumed = (activity as? ComponentActivity)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
            if (!hasFocus && resumed && !menuOpen && bindings[activity] != null) {
                suspendedForModal[activity] = true
                apply(activity)
            } else if (hasFocus && suspendedForModal.remove(activity) == true) {
                apply(activity)
            }
        }
    }

    /** While a screen has soft keys its toolbar's actions live on them, so the toolbar goes. */
    private fun updateActionBar(activity: Activity) {
        val actionBar = (activity as? AppCompatActivity)?.supportActionBar ?: return
        // The AppBarLayout only pads for the status bar above a visible toolbar; keep that room
        val appBar = activity.findViewById<View>(R.id.toolbar)?.parent as? View
        if (hasKeys(activity)) {
            if (actionBar.isShowing) {
                actionBar.hide()
                hiddenActionBars[activity] = true
                // Pad instead: other app-bar children (e.g. tabs) must also clear the status bar
                appBar?.setPadding(appBar.paddingLeft, statusBarHeightPx(), appBar.paddingRight, appBar.paddingBottom)
            }
        } else if (hiddenActionBars.remove(activity) == true) {
            actionBar.show()
            appBar?.setPadding(appBar.paddingLeft, 0, appBar.paddingRight, appBar.paddingBottom)
        }
    }

    /** Real status bar height (system resources: the app's density may be scaled). */
    private fun statusBarHeightPx(): Int {
        val res = android.content.res.Resources.getSystem()
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    /** LEFT "Options" key showing the screen's own options menu (toolbar + overflow + fragments)
     *  plus [extra] entries. */
    fun menuOptionsKey(activity: Activity, extra: List<SoftkeyOption> = emptyList(), exclude: Set<Int> = emptySet()) =
        SoftKey(activity.getString(R.string.softkey_options)) { showMenuOptions(activity, extra, exclude) }

    /** The same bottom-left menu, for a screen's own choosers (e.g. a row's long-press menu). */
    fun showMenu(activity: Activity, options: List<SoftkeyOption>) =
        showMenuDialog(activity, options.map { it.label to it.onClick })

    /** LEFT "Options" key showing exactly [options]. */
    fun optionsKey(activity: Activity, options: List<SoftkeyOption>) =
        SoftKey(activity.getString(R.string.softkey_options)) {
            showMenuDialog(activity, options.map { it.label to it.onClick })
        }

    /** [exclude]: menu item ids already on a soft key (the primary action lives only on RIGHT). */
    private fun showMenuOptions(activity: Activity, extra: List<SoftkeyOption>, exclude: Set<Int>) {
        val menu = PopupMenu(activity, View(activity)).menu
        activity.onCreatePanelMenu(Window.FEATURE_OPTIONS_PANEL, menu)
        activity.onPreparePanel(Window.FEATURE_OPTIONS_PANEL, null, menu)
        val items = (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible && it.itemId !in exclude }
        val entries = items.map { item ->
            val label = if (item.isCheckable && item.isChecked) "\u2713 ${item.title}" else item.title.toString()
            label to {
                val sub = item.subMenu
                if (sub != null) {
                    showMenuDialog(activity, (0 until sub.size()).map { sub.getItem(it) }.filter { it.isVisible }.map { subItem ->
                        subItem.title.toString() to { invokeMenuItem(activity, sub, subItem) }
                    })
                } else {
                    invokeMenuItem(activity, menu, item)
                }
            }
        } + extra.map { it.label to it.onClick }
        if (entries.isNotEmpty()) showMenuDialog(activity, entries)
    }

    /** Run an item the way a real options menu would: its own click listener / intent first
     *  (fragments set those directly), then the activity and fragment menu callbacks. */
    @SuppressLint("RestrictedApi")
    private fun invokeMenuItem(activity: Activity, menu: Menu, item: MenuItem) {
        // Many items are added without an id (all 0), so invoke this exact item, not by id
        val handled = (menu as? MenuBuilder)?.performItemAction(item, 0) == true
        if (!handled) activity.onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL, item)
    }

    private fun showMenuDialog(activity: Activity, entries: List<Pair<String, () -> Unit>>) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val surface = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.WHITE)
        val outline = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOutline, Color.GRAY)
        val onSurface = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val ringPx = dp(3)
        // Vela's orange focus ring, square to match the square menu
        fun ring() = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(ringPx, 0xFFFF6D00.toInt())
        }

        // No minimum width: the menu is as wide as its longest label
        val dialog = AppCompatDialog(activity, R.style.KeypadMenuDialog)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var first: View? = null
        entries.forEachIndexed { i, (label, onClick) ->
            if (i > 0) list.addView(View(activity).apply { setBackgroundColor(outline) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            val row = TextView(activity).apply {
                text = label
                setTextColor(onSurface)
                textSize = 16f
                setPadding(dp(20), dp(12), dp(20), dp(12))
                isFocusable = true
                isClickable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), ring())
                    addState(intArrayOf(android.R.attr.state_pressed), ring())
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
            list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (first == null) first = row
        }
        val container = ScrollView(activity).apply {
            addView(list)
            background = GradientDrawable().apply {
                setColor(surface)
                setStroke(dp(1), outline) // square corners: no radius
            }
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(container)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM or Gravity.START)
            // Exactly as wide as the longest row (dialog decors otherwise impose a minimum width)
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            val widest = (0 until list.childCount).maxOf { i ->
                list.getChildAt(i).let { it.measure(unspecified, unspecified); if (it is TextView) it.measuredWidth else 0 }
            }
            w.setLayout(widest + dp(2), WindowManager.LayoutParams.WRAP_CONTENT)
            // Lift by the bar height (same density the bar is drawn at) so the menu sits on top of it
            w.attributes = w.attributes.apply {
                x = 0
                y = Yapchik.barHeightPx(activity)
            }
        }
        dialog.setOnDismissListener { menuOpen = false }
        menuOpen = true
        dialog.setOnShowListener {
            val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.85f).toInt()
            if (container.height > maxHeight) container.layoutParams = container.layoutParams.apply { height = maxHeight }
            first?.requestFocus()
        }
        dialog.show()
    }
}
