/*
 * SPDX-FileCopyrightText: 2026 KDE Connect contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.view.menu.MenuPresenter
import androidx.appcompat.view.menu.MenuView
import androidx.appcompat.view.menu.SubMenuBuilder
import androidx.core.graphics.drawable.DrawableCompat
import org.kde.kdeconnect.ui.views.ViewBuilders.dp
import org.kde.kdeconnect_tp.R
import com.google.android.material.R as MR

/**
 * The navigation drawer: a header and the entries of [menu] (sub-menus become titled sections). A plain-view
 * stand-in for Material's NavigationView, which builds its rows with a RecyclerView at every app start; this one
 * builds them only when the drawer is actually shown (on a keypad phone it never is: Options has the same entries).
 */
@SuppressLint("RestrictedApi")
class NavDrawerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val header: View
    private var listener: ((MenuItem) -> Boolean)? = null
    private var dirty = true

    val menu: Menu = MenuBuilder(context).apply {
        setCallback(object : MenuBuilder.Callback {
            override fun onMenuItemSelected(menu: MenuBuilder, item: MenuItem) = listener?.invoke(item) ?: false
            override fun onMenuModeChange(menu: MenuBuilder) {}
        })
        // Rebuild the rows (when shown) whenever the entries change
        addMenuPresenter(object : MenuPresenter {
            override fun initForMenu(context: Context, menu: MenuBuilder) {}
            override fun getMenuView(root: android.view.ViewGroup?): MenuView? = null
            override fun updateMenuView(cleared: Boolean) = invalidateRows()
            override fun setCallback(cb: MenuPresenter.Callback?) {}
            override fun onSubMenuSelected(subMenu: SubMenuBuilder?) = false
            override fun onCloseMenu(menu: MenuBuilder?, allMenusAreClosing: Boolean) {}
            override fun flagActionItems() = false
            override fun expandItemActionView(menu: MenuBuilder?, item: MenuItemImpl?) = false
            override fun collapseItemActionView(menu: MenuBuilder?, item: MenuItemImpl?) = false
            override fun getId() = 0
            override fun onSaveInstanceState(): android.os.Parcelable? = null
            override fun onRestoreInstanceState(state: android.os.Parcelable?) {}
        })
    }

    init {
        setBackgroundColor(ViewBuilders.color(context, MR.attr.colorSurfaceContainerLow))
        header = LayoutInflater.from(context).inflate(R.layout.nav_header, list, false)
        list.addView(header)
        // As wide as Material's drawer: at most 300dp, and always leaving 56dp of the screen uncovered
        val width = minOf(dp(context, 300), context.resources.displayMetrics.widthPixels - dp(context, 56))
        addView(ScrollView(context).apply { addView(list) }, LayoutParams(width, LayoutParams.MATCH_PARENT))
    }

    fun getHeaderView(@Suppress("UNUSED_PARAMETER") index: Int): View = header

    fun setNavigationItemSelectedListener(l: (MenuItem) -> Boolean) {
        listener = l
    }

    fun setCheckedItem(id: Int) {
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            item.subMenu?.let { sub -> for (j in 0 until sub.size()) sub.getItem(j).let { it.isChecked = it.itemId == id } }
            if (item.isCheckable) item.isChecked = item.itemId == id
        }
        invalidateRows()
    }

    private fun invalidateRows() {
        dirty = true
        if (isShown) render()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (dirty && isShown) render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (dirty && isShown) post { if (dirty && isShown) render() }
    }

    private fun render() {
        dirty = false
        while (list.childCount > 1) list.removeViewAt(1)
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (!item.isVisible) continue
            val sub = item.subMenu
            if (sub != null) {
                list.addView(ViewBuilders.text(context, item.title ?: "", MR.attr.textAppearanceTitleSmall).apply {
                    setPadding(dp(context, 28), dp(context, 16), dp(context, 16), dp(context, 8))
                })
                for (j in 0 until sub.size()) sub.getItem(j).takeIf { it.isVisible }?.let { list.addView(row(it)) }
            } else {
                list.addView(row(item))
            }
        }
    }

    /** One entry: its icon and title on a pill that is filled when checked (Material 3 navigation drawer look) */
    private fun row(item: MenuItem): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(context, 56)
        setPadding(dp(context, 16), 0, dp(context, 24), 0)
        isFocusable = true
        isClickable = true
        val pill = GradientDrawable().apply {
            cornerRadius = dp(context, 28).toFloat()
            setColor(ViewBuilders.color(context, MR.attr.colorSecondaryContainer))
        }
        background = if (item.isChecked) pill else null
        foreground = ViewBuilders.selectableBackground(context)
        item.icon?.let { icon ->
            addView(ImageView(context).apply {
                setImageDrawable(DrawableCompat.wrap(icon.mutate()).also {
                    DrawableCompat.setTint(it, ViewBuilders.color(context, MR.attr.colorOnSurfaceVariant))
                })
            }, LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)).apply { marginEnd = dp(context, 12) })
        }
        addView(ViewBuilders.text(context, item.title ?: "", MR.attr.textAppearanceLabelLarge,
            if (item.isChecked) MR.attr.colorOnSecondaryContainer else MR.attr.colorOnSurfaceVariant))
        setOnClickListener { (menu as MenuBuilder).performItemAction(item, 0) }
    }.also { row ->
        row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(context, 12), 0, dp(context, 12), 0)
        }
    }
}
