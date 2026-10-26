/*
 * SPDX-FileCopyrightText: 2021 Maxim Leshchenko <cnmaks90@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.about

import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kde.kdeconnect.base.KdeActivity
import org.kde.kdeconnect.ui.keypad.KeypadSoftkeys
import org.kde.kdeconnect.ui.keypad.SoftkeyOption
import org.kde.kdeconnect.ui.views.ViewBuilders
import org.kde.kdeconnect.ui.views.ViewBuilders.dp
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityPlainBinding
import com.google.android.material.R as MR

/** The bundled licenses, one block per library. Keypad: the list is the focus stop and UP/DOWN scroll it. */
class LicensesActivity : KdeActivity() {

    private lateinit var list: RecyclerView
    private var chunks: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityPlainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.licenses)
        }
        val progress = CircularProgressIndicator(this).apply { isIndeterminate = true }
        binding.content.addView(progress, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            clipToPadding = false
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            visibility = View.GONE
        }
        ViewBuilders.keypadScroll(list)
        binding.content.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        lifecycleScope.launch {
            chunks = withContext(Dispatchers.IO) {
                resources.openRawResource(R.raw.license).bufferedReader().use { it.readText() }
                    .split("-".repeat(80))
                    .filter { it.isNotBlank() }
                    .map { it.trim() }
            }
            list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = chunks.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                    object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                        ViewBuilders.styled(this, MR.attr.textAppearanceBodyLarge, MR.attr.colorOnBackground)
                        setPadding(0, dp(context, 8), 0, dp(context, 8))
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    (holder.itemView as TextView).text = chunks[position]
                }
            }
            progress.visibility = View.GONE
            list.visibility = View.VISIBLE
            if (KeypadSoftkeys.isKeypadDevice(this@LicensesActivity)) list.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        // Keypad: the scroll shortcuts go to the soft key Options menu (the bar is dropped)
        KeypadSoftkeys.bind(this, KeypadSoftkeys.optionsKey(this, listOf(
            SoftkeyOption("Scroll to top") { scrollToTop() },
            SoftkeyOption("Scroll to bottom") { scrollToBottom() },
        )), null)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_TOP, Menu.NONE, "Scroll to top").setIcon(R.drawable.ic_arrow_upward_black_24dp)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(Menu.NONE, MENU_BOTTOM, Menu.NONE, "Scroll to bottom").setIcon(R.drawable.ic_arrow_downward_black_24dp)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_TOP -> { scrollToTop(); true }
        MENU_BOTTOM -> { scrollToBottom(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun scrollToTop() = list.smoothScrollToPosition(0)

    private fun scrollToBottom() {
        if (chunks.isNotEmpty()) list.smoothScrollToPosition(chunks.size - 1)
    }

    companion object {
        private const val MENU_TOP = 1
        private const val MENU_BOTTOM = 2
    }
}
