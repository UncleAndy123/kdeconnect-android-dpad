/*
 * SPDX-FileCopyrightText: 2015 Aleix Pol Gonzalez <aleixpol@kde.org>
 * SPDX-FileCopyrightText: 2015 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2026 Johann Specht <sajeg.dev@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.runcommand

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.base.KdeActivity
import org.kde.kdeconnect.ui.keypad.AdaptiveDensity
import org.kde.kdeconnect.ui.keypad.KeypadDialog
import org.kde.kdeconnect.ui.keypad.KeypadDialogButton
import org.kde.kdeconnect.ui.keypad.KeypadSoftkeys
import org.kde.kdeconnect.ui.keypad.SoftkeyOption
import org.kde.kdeconnect.ui.views.ViewBuilders
import org.kde.kdeconnect.ui.views.ViewBuilders.dp
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityRuncommandBinding
import com.google.android.material.R as MR

/**
 * Run command: the desktop's commands (OK runs one, hold OK copies its kdeconnect:// URL) and, when the
 * desktop sends output, a card with the output of the commands run. Plain views (this used to be Compose).
 */
class RunCommandActivity : KdeActivity() {

    private lateinit var binding: ActivityRuncommandBinding
    private lateinit var deviceId: String
    private var plugin: RunCommandPlugin? = null
    private var commandList: List<CommandEntry> = emptyList()

    // The output card's parts, kept so new output only updates the text
    private var outputText: TextView? = null
    private var outputScroll: NestedScrollView? = null
    private var stopButton: View? = null
    private val showStop = Runnable { stopButton?.visibility = if (plugin?.commandRunning == true) View.VISIBLE else View.GONE }

    private val commandsChanged = RunCommandPlugin.CommandsChangedCallback { runOnUiThread { updateList() } }
    private val outputChanged: () -> Unit = { updateOutput() }

    private val keypad by lazy { KeypadSoftkeys.isKeypadDevice(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)!!
        val device = KdeConnect.getInstance().getDevice(deviceId)
        val plugin = device?.getPlugin<RunCommandPlugin>()
        if (plugin == null) {
            finish()
            return
        }
        this.plugin = plugin
        binding = ActivityRuncommandBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.pref_plugin_runcommand)
            subtitle = device.name
        }
        binding.addCommand.setOnClickListener { addCommand() }
        ViewBuilders.unfocusableScroll(binding.scroll)
        plugin.addCommandsUpdatedCallback(commandsChanged)
        plugin.addOutputListener(outputChanged)
        updateList()
    }

    override fun onDestroy() {
        plugin?.removeCommandsUpdatedCallback(commandsChanged)
        plugin?.removeOutputListener(outputChanged)
        binding.root.removeCallbacks(showStop)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (KdeConnect.getInstance().getDevicePlugin<RunCommandPlugin>(deviceId) == null) {
            finish()
            return
        }
        bindKeys()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private val showOutputCard: Boolean
        get() = commandList.isNotEmpty() &&
            KdeConnect.getInstance().getDevice(deviceId)?.canSendPacketType(RunCommandPlugin.PACKET_TYPE_RUNCOMMAND_OUTPUT) == true

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (showOutputCard) menu.add(Menu.NONE, MENU_CLEAR, Menu.NONE, R.string.runcommand_clear_output)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_CLEAR) {
            plugin?.clearOutput()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** LEFT "Options": Add command / Clear output; the FAB is only for touch (no soft keys) */
    private fun bindKeys() {
        val plugin = plugin ?: return
        val options = listOfNotNull(
            if (plugin.canAddCommand()) SoftkeyOption(getString(R.string.add_command)) { addCommand() } else null,
            if (showOutputCard) SoftkeyOption(getString(R.string.runcommand_clear_output)) { plugin.clearOutput() } else null,
        )
        KeypadSoftkeys.bind(this, if (options.isNotEmpty()) KeypadSoftkeys.optionsKey(this, options) else null, null)
        binding.addCommand.visibility =
            if (plugin.canAddCommand() && !KeypadSoftkeys.hasKeys(this)) View.VISIBLE else View.GONE
    }

    private fun addCommand() {
        plugin?.sendSetupPacket()
        if (keypad) {
            KeypadDialog.show(this, getString(R.string.add_command), TextView(this).apply { setText(R.string.add_command_description) },
                listOf(KeypadDialogButton(getString(R.string.ok), {}, filled = true)), onDismiss = { updateList() })
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_command)
            .setMessage(R.string.add_command_description)
            .setPositiveButton(R.string.ok, null)
            .setOnDismissListener { updateList() }
            .show()
    }

    private fun updateList() {
        val plugin = KdeConnect.getInstance().getDevicePlugin<RunCommandPlugin>(deviceId) ?: return
        this.plugin = plugin
        commandList = plugin.commandList.map { CommandEntry(it) }.sortedBy { it.name.lowercase() }
        render()
        bindKeys()
        invalidateOptionsMenu()
    }

    private fun render() {
        val plugin = plugin ?: return
        val content = binding.content
        val focusedKey = currentFocus?.tag as? String
        content.removeAllViews()
        outputText = null; outputScroll = null; stopButton = null
        if (commandList.isEmpty()) {
            var text = getString(R.string.addcommand_explanation)
            if (!plugin.canAddCommand()) text += "\n" + getString(R.string.addcommand_explanation2)
            content.gravity = Gravity.CENTER
            content.addView(ViewBuilders.text(this, text, MR.attr.textAppearanceBodyMedium, MR.attr.colorOnSurface).apply {
                gravity = Gravity.CENTER
                setPadding(dp(context, 16), 0, dp(context, 16), 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            content.minimumHeight = binding.scroll.height
            return
        }
        content.gravity = Gravity.NO_GRAVITY
        content.minimumHeight = 0
        if (showOutputCard) content.addView(outputCard())
        var first: View? = null
        for (command in commandList) {
            val row = commandRow(command)
            content.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (first == null) first = row
        }
        updateOutput()
        val target = focusedKey?.let { content.findViewWithTag<View>(it) } ?: if (keypad && currentFocus == null) first else null
        target?.requestFocus()
    }

    private fun commandRow(command: CommandEntry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = command.key
        isFocusable = true
        isClickable = true
        isLongClickable = true
        background = ViewBuilders.selectableBackground(context)
        setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10))
        addView(ViewBuilders.text(context, command.name, MR.attr.textAppearanceTitleLarge).apply { singleLine() })
        addView(ViewBuilders.text(context, command.command, MR.attr.textAppearanceBodyMedium).apply {
            singleLine()
            setTextColor(Color.GRAY)
        })
        setOnClickListener { plugin?.runCommand(command.key) }
        setOnLongClickListener { row ->
            row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val copy = getString(R.string.copy_url_to_clipboard)
            if (keypad) {
                // Keypad (hold OK): the auto-focused keypad menu (vela's VelaMenu)
                KeypadSoftkeys.showMenu(this@RunCommandActivity, listOf(SoftkeyOption(copy) { copyCommandToClipboard(command) }))
            } else {
                PopupMenu(context, row).apply {
                    menu.add(copy)
                    setOnMenuItemClickListener { copyCommandToClipboard(command); true }
                }.show()
            }
            true
        }
    }

    private fun TextView.singleLine() {
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun outputCard(): View {
        val card = MaterialCardView(this, null, MR.attr.materialCardViewFilledStyle)
        val frame = FrameLayout(this)
        val scroll = NestedScrollView(this).apply { isFocusable = false }
        val text = TextView(this).apply {
            ViewBuilders.styled(this, MR.attr.textAppearanceBodyLarge, MR.attr.colorOnSurface)
            setPadding(dp(context, 15), dp(context, 5), dp(context, 15), dp(context, 5))
        }
        scroll.addView(text)
        frame.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val size = dp(this, 48)
        val stop = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            contentDescription = getString(R.string.runcommand_stop)
            background = ViewBuilders.selectableBackground(context, borderless = true)
            visibility = View.GONE
            addView(CircularProgressIndicator(context).apply { isIndeterminate = true; indicatorSize = dp(context, 40) },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(ViewBuilders.icon(context, R.drawable.ic_stop),
                FrameLayout.LayoutParams(dp(context, 24), dp(context, 24), Gravity.CENTER))
            setOnClickListener { plugin?.sendStop() }
        }
        frame.addView(stop, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(this@RunCommandActivity, 5), dp(this@RunCommandActivity, 5), dp(this@RunCommandActivity, 5), dp(this@RunCommandActivity, 5))
        })
        card.addView(frame)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(this, if (AdaptiveDensity.isSmallScreen(this)) 140 else 300) - dp(this, 20)
        ).apply { setMargins(dp(this@RunCommandActivity, 20), dp(this@RunCommandActivity, 10), dp(this@RunCommandActivity, 20), dp(this@RunCommandActivity, 10)) }
        outputText = text; outputScroll = scroll; stopButton = stop
        return card
    }

    /** Output lines into the one card text (commands bold, green when they succeeded, red when they failed) */
    private fun updateOutput() {
        val plugin = plugin ?: return
        val text = outputText ?: return
        val lines = plugin.outputSnapshot()
        if (lines.isEmpty()) {
            val sb = SpannableStringBuilder()
            sb.append(getString(R.string.runcommand_output_no_output))
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append('\n').append(getString(R.string.runcommand_output_no_output_desc))
            text.text = sb
            text.gravity = Gravity.CENTER
            (text.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.CENTER
            outputScroll?.isFillViewport = true
            text.setPadding(dp(this, 20), dp(this, 20), dp(this, 20), dp(this, 20))
        } else {
            val error = ViewBuilders.color(this, androidx.appcompat.R.attr.colorError)
            val sb = SpannableStringBuilder()
            lines.forEachIndexed { i, line ->
                if (i > 0) sb.append('\n')
                val start = sb.length
                sb.append(line.string)
                if (line.isCommand) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val color = when (line.commandStatus) {
                    RunCommandStatus.COMMAND_SUCCESSFUL -> 0xFF2E7D32.toInt()
                    RunCommandStatus.COMMAND_FAILED -> error
                    else -> null
                }
                if (color != null) sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text.text = sb
            text.gravity = Gravity.START
            outputScroll?.isFillViewport = false
            text.setPadding(dp(this, 15), dp(this, 5), dp(this, 15), dp(this, 5))
            outputScroll?.post { outputScroll?.smoothScrollTo(0, text.height) }
        }
        // The stop button shows once a command has been running for a second
        binding.root.removeCallbacks(showStop)
        if (plugin.commandRunning) {
            if (stopButton?.visibility != View.VISIBLE) binding.root.postDelayed(showStop, 1000)
        } else {
            stopButton?.visibility = View.GONE
        }
    }

    private fun copyCommandToClipboard(command: CommandEntry) {
        val device: Device = KdeConnect.getInstance().getDevice(deviceId) ?: return
        val url = "kdeconnect://runcommand/${device.deviceId}/${command.key}"
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Command", url))
        Toast.makeText(this, R.string.clipboard_toast, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        private const val MENU_CLEAR = 1
    }
}

enum class RunCommandStatus {
    STDERR,
    STDOUT,
    COMMAND_RUNNING,
    COMMAND_SUCCESSFUL,
    COMMAND_FAILED,
}

data class RunCommandOutput(
    val commandStatus: RunCommandStatus,
    val string: String,
    val id: Int,
) {
    val isCommand: Boolean
        get() = commandStatus == RunCommandStatus.COMMAND_RUNNING || commandStatus == RunCommandStatus.COMMAND_SUCCESSFUL || commandStatus == RunCommandStatus.COMMAND_FAILED
}
