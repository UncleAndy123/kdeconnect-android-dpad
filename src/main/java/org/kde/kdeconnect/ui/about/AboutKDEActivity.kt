/*
 * SPDX-FileCopyrightText: 2021 Maxim Leshchenko <cnmaks90@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.about

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.text.HtmlCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.card.MaterialCardView
import org.kde.kdeconnect.base.KdeActivity
import org.kde.kdeconnect.ui.keypad.KeypadSoftkeys
import org.kde.kdeconnect.ui.views.ViewBuilders
import org.kde.kdeconnect.ui.views.ViewBuilders.dp
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityPlainBinding
import com.google.android.material.R as MR

/** About KDE: four text cards and Konqi. Keypad: the page is the focus stop and UP/DOWN scroll it. */
class AboutKDEActivity : KdeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityPlainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.about_kde)
        }
        val gap = dp(this, 16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gap, gap, gap, gap)
        }
        fun lp(top: Int = gap) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = top }

        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 8))
            addView(ViewBuilders.icon(context, R.drawable.ic_kde_48dp, MR.attr.colorOnBackground), LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
            addView(ViewBuilders.text(context, getString(R.string.kde_be_free), MR.attr.textAppearanceTitleLarge, MR.attr.colorOnBackground),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(context, 8) })
        }, lp(0))
        for (res in intArrayOf(R.string.about_kde_about, R.string.about_kde_report_bugs_or_wishes, R.string.about_kde_join_kde, R.string.about_kde_support_kde)) {
            column.addView(htmlCard(getString(res)), lp())
        }
        column.addView(ImageView(this).apply {
            setImageResource(R.drawable.konqi)
            contentDescription = getString(R.string.konqi)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 256)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = gap * 2
            bottomMargin = gap
        })
        val scroll = NestedScrollView(this).apply { addView(column) }
        ViewBuilders.keypadScroll(scroll)
        binding.content.addView(scroll)
        if (KeypadSoftkeys.isKeypadDevice(this)) scroll.requestFocus()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun htmlCard(html: String) = MaterialCardView(this, null, MR.attr.materialCardViewElevatedStyle).apply {
        val adjusted = html.replace("</p>", "</p><br>").replace("</h1>", "</h1><br>")
        addView(ViewBuilders.text(context, HtmlCompat.fromHtml(adjusted, HtmlCompat.FROM_HTML_MODE_LEGACY).trimEnd(),
            MR.attr.textAppearanceBodyMedium, MR.attr.colorOnSurface).apply {
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            setLinkTextColor(ViewBuilders.color(context, androidx.appcompat.R.attr.colorPrimary))
            // Links open on touch; on keypad phones the page scrolls instead of stopping on each link
            if (!KeypadSoftkeys.isKeypadDevice(context)) movementMethod = LinkMovementMethod.getInstance()
        })
    }
}
