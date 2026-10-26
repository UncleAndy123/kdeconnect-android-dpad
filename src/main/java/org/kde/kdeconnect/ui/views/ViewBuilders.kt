/*
 * SPDX-FileCopyrightText: 2026 KDE Connect contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.kde.kdeconnect.ui.keypad.AdaptiveDensity
import com.google.android.material.R as MR

/**
 * Small plain-view building blocks for the screens that used to be Jetpack Compose (device list, device
 * screen, share chooser). Plain views are far cheaper to load and build on a low-end phone. They keep the
 * same Material 3 look: the same text styles, colours, filled cards and spacing.
 */
object ViewBuilders {
    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun color(context: Context, attr: Int): Int = MaterialColors.getColor(context, attr, Color.GRAY)

    fun attrRes(context: Context, attr: Int): Int =
        TypedValue().also { context.theme.resolveAttribute(attr, it, true) }.resourceId

    /** A text style (theme attribute, e.g. textAppearanceBodyLarge) in onSurfaceVariant, these screens' text colour */
    fun styled(view: TextView, attr: Int, colorAttr: Int = MR.attr.colorOnSurfaceVariant): TextView {
        val res = attrRes(view.context, attr)
        if (res != 0) TextViewCompat.setTextAppearance(view, res)
        view.setTextColor(color(view.context, colorAttr))
        return view
    }

    fun text(context: Context, value: CharSequence, attr: Int, colorAttr: Int = MR.attr.colorOnSurfaceVariant): TextView =
        styled(TextView(context).apply { text = value }, attr, colorAttr)

    fun icon(context: Context, res: Int, tintAttr: Int = MR.attr.colorOnSurfaceVariant): ImageView = ImageView(context).apply {
        setImageResource(res)
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(context, tintAttr)))
    }

    /** The theme's ripple (selectableItemBackground / ...Borderless) for a clickable row or icon button */
    fun selectableBackground(context: Context, borderless: Boolean = false) =
        androidx.core.content.ContextCompat.getDrawable(context, attrRes(context,
            if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground))

    /**
     * A scrolling page with nothing focusable in it (licenses, About KDE): on keypad phones it is the focus
     * stop and UP/DOWN scroll it by 120dp, passing the key on at the ends so focus can move on.
     */
    fun keypadScroll(view: View) {
        if (!org.kde.kdeconnect.ui.keypad.KeypadSoftkeys.isKeypadDevice(view.context)) return
        view.isFocusable = true
        if (view is android.view.ViewGroup) view.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val step = dp(view.context, 120)
        view.setOnKeyListener { v, keyCode, event ->
            val dir = when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 1
                android.view.KeyEvent.KEYCODE_DPAD_UP -> -1
                else -> return@setOnKeyListener false
            }
            if (!v.canScrollVertically(dir)) return@setOnKeyListener false
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (v) {
                    is androidx.recyclerview.widget.RecyclerView -> v.smoothScrollBy(0, dir * step)
                    is androidx.core.widget.NestedScrollView -> v.smoothScrollBy(0, dir * step)
                    else -> v.scrollBy(0, dir * step)
                }
            }
            true
        }
    }

    /**
     * A scroll container that never takes focus itself (NestedScrollView makes itself focusable): on a keypad
     * phone the first control inside gets it, not the whole page.
     */
    fun unfocusableScroll(view: View) {
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        (view as? android.view.ViewGroup)?.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    fun sectionHeader(context: Context, title: String): View = text(context, title, MR.attr.textAppearanceTitleMedium).apply {
        setPadding(dp(context, 16), dp(context, 16), dp(context, 16), 0)
    }

    /** A filled Material 3 card (surfaceContainerHighest, medium corners), clickable and a D-pad focus stop */
    fun card(context: Context, onClick: () -> Unit): MaterialCardView =
        MaterialCardView(context, null, MR.attr.materialCardViewFilledStyle).apply {
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }

    /**
     * A device card: its icon, its name and an optional red second line (e.g. "newer protocol" or
     * "not reachable"). Tagged with the device id so a screen can keep or restore focus on it.
     */
    fun deviceCard(context: Context, id: String, iconRes: Int, name: String, redLine: String?, onClick: () -> Unit): View {
        val card = card(context, onClick).apply { tag = id }
        val pad = dp(context, if (AdaptiveDensity.isSmallScreen(context)) 8 else 32)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 48)
            setPadding(pad, pad, pad, pad)
        }
        if (iconRes > 0) {
            row.addView(icon(context, iconRes), LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)).apply {
                marginStart = dp(context, 12)
            })
        }
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(context, name, MR.attr.textAppearanceBodyLarge).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
            if (redLine != null) {
                addView(text(context, redLine, MR.attr.textAppearanceBodySmall).apply {
                    setTextColor(Color.parseColor("#CC2222"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    maxLines = 1
                })
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(context, 8)
        })
        card.addView(row)
        return card
    }
}
