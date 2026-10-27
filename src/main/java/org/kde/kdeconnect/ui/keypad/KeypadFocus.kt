/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.keypad

import com.google.android.material.button.MaterialButton
import androidx.appcompat.widget.SwitchCompat
import android.widget.ImageButton
import android.widget.CompoundButton
import android.view.ViewGroup
import android.view.Window
import android.widget.AbsListView
import java.util.WeakHashMap
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewTreeObserver

// D-pad helpers ported from alltechdev/vela-dpad (app/ui/DpadFocus.kt): every interactive element
// is reachable by focus traversal, the focused one carries an unmistakable orange ring, screens open
// already focused, and text fields / scroll areas never trap or lose focus. All no-ops under touch.

/** Vela's ring colour: an orange that reads on any fill, light or dark. */
private const val FOCUS_RING_ARGB = 0xFFFF6D00.toInt()
/**
 * The same orange ring for classic Views, drawn in a view overlay (installed on keypad devices).
 * Clean-wrap rules from vela-dpad's focus-ring polish:
 *  - a row holding a toggle rings the TOGGLE's pill, not the row
 *  - a button rings its own rounded shape inside its touch insets; an icon button gets a circle
 *  - anything else gets a softly rounded rectangle
 */
internal class ViewFocusRing(private val window: Window) : ViewTreeObserver.OnGlobalFocusChangeListener {
    private val density = window.context.resources.displayMetrics.density
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
        // A list (a dialog's choices) keeps the focus itself and marks its current row with its selector: ring that
        // row, not the whole list
        if (view is AbsListView) {
            ringRows(view)
            return
        }
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
                // Its drawable's bounds once drawn (a text-less radio is wider than its circle), else 32dp at the start
                val drawn = target.buttonDrawable?.bounds
                if (drawn != null && !drawn.isEmpty) {
                    ring.bounds = android.graphics.Rect(drawn)
                } else {
                    val size = (32 * density).toInt().coerceAtMost(minOf(w, h))
                    val left = target.paddingLeft.coerceAtMost(w - size).coerceAtLeast(0)
                    val top = (h - size) / 2
                    ring.setBounds(left, top, left + size, top + size)
                }
                ring.cornerRadius = if (target is android.widget.RadioButton) ring.bounds.width() / 2f else 6 * density
            }
            target is com.google.android.material.card.MaterialCardView -> {
                ring.setBounds(0, 0, w, h)
                ring.cornerRadius = target.radius
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

    private val rungLists = WeakHashMap<AbsListView, Boolean>()

    /** The list's selector (drawn on its current row while it has keypad focus) becomes the orange ring */
    private fun ringRows(list: AbsListView) {
        if (rungLists.put(list, true) != null) return
        list.selector = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(stroke, FOCUS_RING_ARGB)
            cornerRadius = 8 * density
        }
        list.setDrawSelectorOnTop(true)
    }

    fun install() {
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(this)
        // Ring whatever already has focus (the framework focuses the first focusable view on open)
        window.currentFocus?.let { onGlobalFocusChanged(null, it) }
        // Focus given before the window is attached (e.g. requestFocus() in onResume on first open) fires no focus
        // event, so check again once attached
        window.decorView.post {
            val current = window.currentFocus
            if (current != null && current !== focused) onGlobalFocusChanged(null, current)
        }
    }
}
