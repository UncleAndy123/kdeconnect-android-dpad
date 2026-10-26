/*
 * SPDX-FileCopyrightText: 2021 Maxim Leshchenko <cnmaks90@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import org.kde.kdeconnect.ui.views.ViewBuilders
import org.kde.kdeconnect.ui.views.ViewBuilders.dp
import org.kde.kdeconnect_tp.R
import com.google.android.material.R as MR

/** About: the app card (OK copies the version; three quick taps open the easter egg), links, authors. Plain views. */
class AboutFragment : Fragment() {
    companion object {
        fun newInstance(): Fragment = AboutFragment()
    }

    private lateinit var aboutData: AboutData
    private var tapCount = 0
    private var firstTapMillis: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutData = applicationAboutData
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
        }
        val gap = dp(ctx, 16)
        fun lp(top: Int = gap) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
        column.addView(appInfoCard(ctx), lp(0))
        column.addView(actionButtons(ctx), lp())
        column.addView(authorsCard(ctx), lp())
        return NestedScrollView(ctx).apply { addView(column) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.let { actionBar ->
            actionBar.title = getString(R.string.about)
            actionBar.subtitle = null
        }
    }

    private fun elevatedCard(ctx: Context) = MaterialCardView(ctx, null, MR.attr.materialCardViewElevatedStyle)

    private fun appInfoCard(ctx: Context): View = elevatedCard(ctx).apply {
        isFocusable = true
        isClickable = true
        setOnClickListener { onAppCardClicked(ctx) }
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            addView(ImageView(ctx).apply { setImageResource(aboutData.icon) }, LinearLayout.LayoutParams(dp(ctx, 52), dp(ctx, 52)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(ViewBuilders.text(ctx, getString(aboutData.name), MR.attr.textAppearanceTitleLarge, MR.attr.colorOnSurface))
                addView(ViewBuilders.text(ctx, getString(R.string.version, aboutData.versionName), MR.attr.textAppearanceBodyMedium))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
        })
    }

    private fun onAppCardClicked(ctx: Context) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Version", aboutData.versionName))
        Toast.makeText(ctx, R.string.clipboard_toast, Toast.LENGTH_SHORT).show()
        val now = SystemClock.elapsedRealtime()
        val first = firstTapMillis ?: now.also { firstTapMillis = it }
        tapCount++
        if (tapCount == 3) {
            if (now - first <= 500) startActivity(Intent(ctx, EasterEggActivity::class.java))
            tapCount = 0
            firstTapMillis = null
        } else if (now - first > 500) {
            tapCount = 1
            firstTapMillis = now
        }
    }

    /** The link buttons: one row when they fit at 84dp each, else rows of three */
    private fun actionButtons(ctx: Context): View {
        val buttons = listOfNotNull(
            aboutData.bugURL?.let { Triple(R.string.report_bug, R.drawable.ic_baseline_bug_report_24) { openUrl(it) } },
            aboutData.donateURL?.let { Triple(R.string.donate, R.drawable.ic_baseline_attach_money_24) { openUrl(it) } },
            aboutData.sourceCodeURL?.let { Triple(R.string.source_code, R.drawable.ic_baseline_code_24) { openUrl(it) } },
            Triple(R.string.licenses, R.drawable.ic_baseline_gavel_24) { startActivity(Intent(ctx, LicensesActivity::class.java)) },
            Triple(R.string.about_kde, R.drawable.ic_kde_24dp) { startActivity(Intent(ctx, AboutKDEActivity::class.java)) },
            aboutData.websiteURL?.let { Triple(R.string.website, R.drawable.ic_baseline_web_24) { openUrl(it) } },
        )
        val available = ctx.resources.displayMetrics.widthPixels - dp(ctx, 32)
        val perRow = if (dp(ctx, 84) * buttons.size <= available) buttons.size else 3
        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        buttons.chunked(perRow).forEach { chunk ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            // SpaceEvenly: equal weight spacers around every button
            row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            chunk.forEach { (text, icon, onClick) ->
                row.addView(actionButton(ctx, text, icon, onClick), LinearLayout.LayoutParams(dp(ctx, 84), dp(ctx, 84)))
                row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            }
            rows.addView(row)
        }
        return rows
    }

    private fun actionButton(ctx: Context, text: Int, icon: Int, onClick: () -> Unit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        background = ViewBuilders.selectableBackground(ctx, borderless = true)
        setOnClickListener { onClick() }
        addView(ViewBuilders.icon(ctx, icon, MR.attr.colorOnBackground), LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24)))
        addView(ViewBuilders.text(ctx, getString(text), MR.attr.textAppearanceLabelMedium, MR.attr.colorOnBackground).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, 8)
        })
    }

    private fun authorsCard(ctx: Context): View = elevatedCard(ctx).apply {
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            addView(ViewBuilders.text(ctx, getString(R.string.authors), MR.attr.textAppearanceTitleLarge, MR.attr.colorOnSurface).apply {
                setPadding(0, 0, 0, dp(ctx, 8))
            })
            aboutData.authors.forEach { author ->
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
                    addView(ViewBuilders.text(ctx, author.name, MR.attr.textAppearanceBodyLarge, MR.attr.colorOnSurface))
                    author.task?.let { addView(ViewBuilders.text(ctx, getString(it), MR.attr.textAppearanceBodyMedium)) }
                })
            }
            aboutData.authorsFooterText?.let { footer ->
                addView(ViewBuilders.text(ctx, getString(footer), MR.attr.textAppearanceBodyMedium, MR.attr.colorOnSurface).apply {
                    setPadding(dp(ctx, 4), dp(ctx, 8), 0, 0)
                })
            }
        })
    }

    private fun openUrl(url: String?) {
        url?.let { startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) }
    }
}
