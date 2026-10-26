/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.keypad

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import org.kde.kdeconnect.ui.views.ViewBuilders
import org.kde.kdeconnect.ui.views.ViewBuilders.dp
import com.google.android.material.R as MR

/** A dialog button: [filled] is the higher-emphasis (confirm) action. */
class KeypadDialogButton(val text: String, val onClick: () -> Unit, val filled: Boolean = false)

/**
 * D-pad-first dialog, ported from vela-dpad's VelaDialog: lands already focused (on [autoFocusIndex], or
 * on the body's own focus target such as a text field when that is null), OK activates, arrows move, BACK
 * dismisses. The soft-key bar hides while it is up (KeypadSoftkeys watches window focus). Styled like
 * Material's AlertDialog (28dp corners, surfaceContainerHigh); tighter on feature-phone screens. The
 * dialog is its own window, so its buttons carry the orange ring themselves.
 */
object KeypadDialog {
    private const val RING_ARGB = 0xFFFF6D00.toInt()

    /** [onClick] of a button runs after the dialog closes unless [keepOpen] says otherwise (e.g. a bad input). */
    fun show(
        activity: Activity,
        title: String,
        body: View,
        buttons: List<KeypadDialogButton>,
        autoFocusIndex: Int? = 0,
        onDismiss: () -> Unit = {},
        keepOpen: (KeypadDialogButton) -> Boolean = { false },
    ): AppCompatDialog {
        val ctx = activity
        val compact = AdaptiveDensity.applied
        val dialog = AppCompatDialog(ctx)
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, if (compact) 16 else 24)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 28).toFloat()
                setColor(ViewBuilders.color(ctx, MR.attr.colorSurfaceContainerHigh))
            }
        }
        column.addView(ViewBuilders.text(ctx, title,
            if (compact) MR.attr.textAppearanceTitleLarge else MR.attr.textAppearanceHeadlineSmall, MR.attr.colorOnSurface))
        // The body scrolls so the buttons never fall off a small screen
        val scroll = ScrollView(ctx).apply { addView(body) }
        if (body is TextView) ViewBuilders.styled(body, MR.attr.textAppearanceBodyMedium)
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(ctx, if (compact) 8 else 16)
        })
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val views = buttons.map { button ->
            dialogButton(ctx, button) {
                if (!keepOpen(button)) dialog.dismiss()
                button.onClick()
            }.also { v ->
                row.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(ctx, 8)
                })
            }
        }
        column.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, if (compact) 12 else 24)
        })
        dialog.setContentView(column)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener { onDismiss() }
        dialog.setOnShowListener {
            // Cap at 90% of the screen; the body scrolls
            val max = (ctx.resources.displayMetrics.heightPixels * 0.9f).toInt()
            if (column.height > max) column.layoutParams = column.layoutParams.apply { height = max }
            autoFocusIndex?.let { views.getOrNull(it)?.requestFocus() } ?: body.requestFocus()
        }
        dialog.show()
        return dialog
    }

    /** A text button (or a filled pill for the confirm action) with its own orange focus ring */
    private fun dialogButton(ctx: Activity, button: KeypadDialogButton, onClick: () -> Unit): View {
        val radius = dp(ctx, 20).toFloat()
        val primary = ViewBuilders.color(ctx, androidx.appcompat.R.attr.colorPrimary)
        val fill = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (button.filled) primary else Color.TRANSPARENT)
        }
        val ring = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(dp(ctx, 3), RING_ARGB)
        }
        return ViewBuilders.text(ctx, button.text, MR.attr.textAppearanceLabelLarge).apply {
            setTextColor(if (button.filled) ViewBuilders.color(ctx, MR.attr.colorOnPrimary) else primary)
            setPadding(dp(ctx, if (button.filled) 24 else 12), dp(ctx, 10), dp(ctx, if (button.filled) 24 else 12), dp(ctx, 10))
            background = fill
            foreground = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), ring)
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
    }
}
