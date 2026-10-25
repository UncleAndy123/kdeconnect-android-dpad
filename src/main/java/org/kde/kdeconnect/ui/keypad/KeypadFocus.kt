/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.keypad

import com.google.android.material.button.MaterialButton
import androidx.appcompat.widget.SwitchCompat
import android.widget.ImageButton
import android.widget.CompoundButton
import android.view.ViewGroup
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// D-pad helpers ported from alltechdev/vela-dpad (app/ui/DpadFocus.kt): every interactive element
// is reachable by focus traversal, the focused one carries an unmistakable orange ring, screens open
// already focused, and text fields / scroll areas never trap or lose focus. All no-ops under touch.

/** Vela's ring colour: an orange that reads on any fill, light or dark. */
private const val FOCUS_RING_ARGB = 0xFFFF6D00.toInt()
private val FocusRing = Color(FOCUS_RING_ARGB)

/** True on a keypad-first device (no touchscreen, or a real D-pad). */
@Composable
fun rememberKeypadDevice(): Boolean {
    val context = LocalContext.current
    return remember { KeypadSoftkeys.isKeypadDevice(context) }
}

/** True while the UI is key-driven: always on a keypad device, live on a touch phone + keyboard. */
@Composable
fun rememberKeyDriven(): Boolean =
    rememberKeypadDevice() || LocalInputModeManager.current.inputMode == InputMode.Keyboard

/**
 * Orange 3dp ring while this element (or a descendant: Material components host their own focus
 * target) has focus and the UI is key-driven. Like vela's DpadRingBox / VelaSwitch, the ring hugs
 * the control's VISIBLE shape, not Material's inflated 48dp touch target: pass [insetVertical] /
 * [insetHorizontal] for the touch-target padding (buttons: 4dp vertical, icon buttons: 4dp all
 * round) and the control's own [shape]. Drawn, not laid out, so layout is unchanged.
 */
fun Modifier.keypadFocusRing(
    shape: Shape = RoundedCornerShape(12.dp),
    insetVertical: Dp = 0.dp,
    insetHorizontal: Dp = 0.dp,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val show = focused && rememberKeyDriven()
    this
        .onFocusEvent { focused = it.hasFocus }
        .drawWithContent {
            drawContent()
            if (show) {
                val stroke = 3.dp.toPx()
                val dx = insetHorizontal.toPx() + stroke / 2
                val dy = insetVertical.toPx() + stroke / 2
                val ringSize = Size(size.width - 2 * dx, size.height - 2 * dy)
                if (ringSize.width > 0 && ringSize.height > 0) {
                    translate(dx, dy) {
                        drawOutline(shape.createOutline(ringSize, layoutDirection, this), FocusRing, style = Stroke(stroke))
                    }
                }
            }
        }
}

/** Ring for a Material Button / TextButton: its 40dp pill inside the 48dp touch target. */
fun Modifier.keypadButtonRing(shape: Shape): Modifier = keypadFocusRing(shape, insetVertical = 4.dp)

/** Ring for a Material IconButton: its 40dp circle inside the 48dp touch target. */
fun Modifier.keypadIconButtonRing(): Modifier =
    keypadFocusRing(androidx.compose.foundation.shape.CircleShape, insetVertical = 4.dp, insetHorizontal = 4.dp)

/**
 * Put focus here when the screen opens on a keypad device. Keeps re-requesting (every 50ms, ~2s)
 * until focus has really landed, then stops so it never fights the user (vela's dpadAutoFocus).
 */
fun Modifier.keypadAutoFocus(): Modifier = composed {
    val requester = remember { FocusRequester() }
    val keypad = rememberKeypadDevice()
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(keypad) {
        if (keypad) {
            // Keep watching briefly after focus lands: the soft-key bar appears just after the screen
            // composes, and its relayout can drop focus again. Stop once focus has held for 600ms.
            var heldFor = 0
            repeat(60) {
                if (focused) {
                    heldFor += 50
                    if (heldFor >= 600) return@LaunchedEffect
                } else {
                    heldFor = 0
                    runCatching { requester.requestFocus() }
                }
                delay(50)
            }
        }
    }
    this
        .focusRequester(requester)
        .onFocusEvent { focused = it.isFocused }
}

/**
 * UP/DOWN leave a text field instead of being eaten by its cursor handling (Compose only lets
 * SOURCE_DPAD keys escape; keypads may report as keyboards). Falls through at a list edge.
 */
fun Modifier.keypadFieldEscape(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    if (!rememberKeyDriven()) {
        this
    } else {
        this.onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) {
                false
            } else {
                when (ev.key) {
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    else -> false
                }
            }
        }
    }
}

/**
 * Makes a scrolling area with no focusable content D-pad scrollable: it becomes a focus stop
 * (with the ring) and UP/DOWN scroll it, passing the key on at the ends so focus can move on.
 */
fun Modifier.keypadScroll(state: ScrollState): Modifier = composed {
    val scope = rememberCoroutineScope()
    keypadScrollKeys { delta ->
        val can = if (delta > 0) state.canScrollForward else state.canScrollBackward
        if (can) scope.launch { state.animateScrollBy(delta) }
        can
    }
}

/** [keypadScroll] for a LazyColumn whose rows aren't focusable. */
fun Modifier.keypadScroll(state: LazyListState): Modifier = composed {
    val scope = rememberCoroutineScope()
    keypadScrollKeys { delta ->
        val can = if (delta > 0) state.canScrollForward else state.canScrollBackward
        if (can) scope.launch { state.animateScrollBy(delta) }
        can
    }
}

private fun Modifier.keypadScrollKeys(scrollBy: (Float) -> Boolean): Modifier = composed {
    val step = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
    this
        .keypadFocusRing(RoundedCornerShape(4.dp))
        .onKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (ev.key) {
                Key.DirectionDown -> scrollBy(step)
                Key.DirectionUp -> scrollBy(-step)
                else -> false
            }
        }
        .focusable()
}

/**
 * The same orange ring for classic Views, drawn in a view overlay (installed on keypad devices).
 * Clean-wrap rules from vela-dpad's focus-ring polish:
 *  - a row holding a toggle rings the TOGGLE's pill, not the row
 *  - a button rings its own rounded shape inside its touch insets; an icon button gets a circle
 *  - anything else gets a softly rounded rectangle
 * A focused ComposeView host is skipped: Compose draws its own ring (keypadFocusRing).
 */
internal class ViewFocusRing(private val activity: Activity) : ViewTreeObserver.OnGlobalFocusChangeListener {
    private val density = activity.resources.displayMetrics.density
    private val stroke = (3 * density).toInt().coerceAtLeast(2)
    private val ring = GradientDrawable().apply {
        setColor(android.graphics.Color.TRANSPARENT)
        setStroke(stroke, FOCUS_RING_ARGB)
    }
    private var host: View? = null
    private var focused: View? = null
    private val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }

    override fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?) {
        host?.let {
            it.overlay.remove(ring)
            it.removeOnLayoutChangeListener(relayout)
        }
        host = null
        focused = newFocus
        val view = newFocus ?: return
        if (view.javaClass.name.startsWith("androidx.compose.ui.platform.")) return
        val target = toggleIn(view) ?: view
        host = target
        target.overlay.add(ring)
        target.addOnLayoutChangeListener(relayout)
        place()
    }

    /** The toggle a focused row stands for (the row is one focus stop; the ring hugs its switch). */
    private fun toggleIn(view: View): CompoundButton? {
        if (view is CompoundButton) return view
        if (view !is ViewGroup) return null
        val stack = ArrayDeque<View>().apply { add(view) }
        while (stack.isNotEmpty()) {
            val v = stack.removeFirst()
            if (v is CompoundButton && v.isShown) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
        }
        return null
    }

    private fun place() {
        val target = host ?: return
        val w = target.width
        val h = target.height
        when {
            target is SwitchCompat -> {
                // The track pill: its drawable's bounds once drawn, else the centred 32dp track
                val track = target.trackDrawable?.bounds
                val r = if (track != null && !track.isEmpty) android.graphics.Rect(track)
                    else ((h - 32 * density) / 2).toInt().coerceAtLeast(0).let { inset -> android.graphics.Rect(0, inset, w, h - inset) }
                ring.bounds = r
                ring.cornerRadius = r.height() / 2f
            }
            target is android.widget.Switch -> {
                val inset = ((h - 32 * density) / 2).toInt().coerceAtLeast(0)
                ring.setBounds(0, inset, w, h - inset)
                ring.cornerRadius = (h - 2 * inset) / 2f
            }
            target is CompoundButton -> { // checkbox / radio: hug the button graphic
                val size = (32 * density).toInt().coerceAtMost(minOf(w, h))
                val left = target.paddingLeft.coerceAtMost(w - size).coerceAtLeast(0)
                val top = (h - size) / 2
                ring.setBounds(left, top, left + size, top + size)
                ring.cornerRadius = 6 * density
            }
            target is MaterialButton -> {
                ring.setBounds(0, target.insetTop, w, h - target.insetBottom)
                ring.cornerRadius = target.cornerRadius.toFloat().coerceAtMost((h - target.insetTop - target.insetBottom) / 2f)
            }
            target is ImageButton || target.javaClass.simpleName.contains("OverflowMenuButton") -> {
                val d = minOf(w, h)
                ring.setBounds((w - d) / 2, (h - d) / 2, (w + d) / 2, (h + d) / 2)
                ring.cornerRadius = d / 2f
            }
            else -> {
                ring.setBounds(0, 0, w, h)
                ring.cornerRadius = 8 * density
            }
        }
        target.invalidate()
    }

    fun install() {
        activity.window?.decorView?.viewTreeObserver?.addOnGlobalFocusChangeListener(this)
        // Ring whatever already has focus (the framework focuses the first focusable view on open)
        activity.currentFocus?.let { onGlobalFocusChanged(null, it) }
    }
}
