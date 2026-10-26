/*
 * SPDX-FileCopyrightText: 2026 Tanish Ranjan <tanishranjan4@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.kde.kdeconnect.base.KdeActivity
import org.kde.kdeconnect.DeviceHost
import org.kde.kdeconnect.ui.compose.screen.customdevices.CustomDevicesUiState
import org.kde.kdeconnect.ui.compose.screen.customdevices.CustomDevicesViewModel
import org.kde.kdeconnect.ui.compose.screen.customdevices.DeviceHostUiModel
import org.kde.kdeconnect.ui.compose.screen.customdevices.DialogState
import org.kde.kdeconnect.ui.compose.screen.customdevices.PingState
import org.kde.kdeconnect.ui.compose.screen.customdevices.SaveResult
import org.kde.kdeconnect.ui.compose.screen.customdevices.UiEvent
import org.kde.kdeconnect.ui.keypad.KeypadDialog
import org.kde.kdeconnect.ui.keypad.KeypadDialogButton
import org.kde.kdeconnect.ui.keypad.KeypadSoftkeys
import org.kde.kdeconnect.ui.keypad.SoftKey
import org.kde.kdeconnect.ui.views.ViewBuilders
import org.kde.kdeconnect.ui.views.ViewBuilders.dp
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityPlainBinding
import com.google.android.material.R as MR

/**
 * Hosts added by hand (IP or hostname), each with its ping result. Tap/OK edits, swipe deletes (with
 * undo); keypad phones delete from the edit dialog. Plain views (this used to be Compose).
 */
class CustomDevicesActivity : KdeActivity() {

    private val viewModel: CustomDevicesViewModel by viewModels()
    private lateinit var binding: ActivityPlainBinding
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var fab: FloatingActionButton
    private val adapter = Adapter()
    private var dialog: androidx.appcompat.app.AppCompatDialog? = null
    private var dialogFor: DialogState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.custom_devices_settings)
        }

        empty = ViewBuilders.text(this, getString(R.string.custom_device_list_help), MR.attr.textAppearanceBodyMedium, MR.attr.colorOnSurface).apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 24), 0, dp(context, 24), 0)
        }
        binding.content.addView(empty, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@CustomDevicesActivity.adapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            itemAnimator = null
            clipToPadding = false
            setPadding(0, 0, 0, dp(context, 88))
        }
        binding.content.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Swipe either way deletes (touch); keypad phones use Delete in the edit dialog
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START or ItemTouchHelper.END) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            private val swipeBackground = android.graphics.drawable.ColorDrawable(ViewBuilders.color(this@CustomDevicesActivity, MR.attr.colorErrorContainer))
            private val deleteIcon = androidx.core.content.ContextCompat.getDrawable(this@CustomDevicesActivity, R.drawable.ic_delete)!!.mutate().apply {
                setTint(ViewBuilders.color(this@CustomDevicesActivity, MR.attr.colorOnErrorContainer))
            }
            // The red delete background and icon behind a row being swiped (on the side it uncovers)
            override fun onChildDraw(c: android.graphics.Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, active: Boolean) {
                val v = vh.itemView
                if (dX != 0f) {
                    val left = if (dX > 0) v.left else v.right + dX.toInt()
                    val right = if (dX > 0) v.left + dX.toInt() else v.right
                    swipeBackground.setBounds(left, v.top, right, v.bottom); swipeBackground.draw(c)
                    val size = dp(this@CustomDevicesActivity, 24); val margin = dp(this@CustomDevicesActivity, 20)
                    val top = v.top + (v.height - size) / 2
                    val x = if (dX > 0) v.left + margin else v.right - margin - size
                    c.save(); c.clipRect(left, v.top, right, v.bottom)
                    deleteIcon.setBounds(x, top, x + size, top + size); deleteIcon.draw(c)
                    c.restore()
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, active)
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                adapter.items.getOrNull(vh.bindingAdapterPosition)?.let { viewModel.onDeviceDismissed(it.id) }
            }
        }).attachToRecyclerView(list)

        fab = FloatingActionButton(this).apply {
            setImageResource(R.drawable.ic_add)
            contentDescription = getString(R.string.add_device_dialog_title)
            setOnClickListener { viewModel.openAddDialog() }
        }
        binding.root.addView(fab, CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(dp(this@CustomDevicesActivity, 16), 0, dp(this@CustomDevicesActivity, 16), dp(this@CustomDevicesActivity, 16))
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch {
                    viewModel.uiEvents.collect { event ->
                        when (event) {
                            is UiEvent.ShowUndoSnackbar ->
                                Snackbar.make(binding.root, R.string.custom_device_deleted, Snackbar.LENGTH_LONG)
                                    .setAction(R.string.undo) { viewModel.restoreDevice(event.deletedHost) }
                                    .show()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Keypad: RIGHT = Add device (the FAB on touch)
        KeypadSoftkeys.bind(this, null, SoftKey(getString(R.string.add_device_dialog_title)) { viewModel.openAddDialog() })
        fab.visibility = if (KeypadSoftkeys.hasKeys(this)) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun render(state: CustomDevicesUiState) {
        val focusedId = (currentFocus?.tag as? String)
        adapter.items = state.devices
        adapter.notifyDataSetChanged()
        empty.visibility = if (state.devices.isEmpty()) View.VISIBLE else View.GONE
        if (focusedId != null) list.post { list.findViewWithTag<View>(focusedId)?.requestFocus() }
        else if (KeypadSoftkeys.isKeypadDevice(this) && state.devices.isNotEmpty() && currentFocus == null) {
            list.post { list.getChildAt(0)?.requestFocus() }
        }

        val ds = state.dialogState
        if (ds is DialogState.Hidden) {
            dialogFor = null
            dialog?.let { it.setOnDismissListener(null); it.dismiss() }
            dialog = null
        } else if (ds != dialogFor) {
            dialog?.let { it.setOnDismissListener(null); it.dismiss() }
            dialogFor = ds
            dialog = showDialog(ds)
        }
    }

    private fun showDialog(state: DialogState): androidx.appcompat.app.AppCompatDialog {
        val initial = (state as? DialogState.Editing)?.initialText ?: ""
        val layout = TextInputLayout(this, null, MR.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.add_device_hint)
        }
        val field = TextInputEditText(layout.context).apply {
            // Hosts are IPs/hostnames: start T9 keypads in a URI-friendly mode
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setText(initial)
            setSelection(initial.length)
        }
        layout.addView(field)
        val box = FrameLayout(this).apply {
            setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0)
            addView(layout)
        }
        val confirm = {
            when (viewModel.onDialogConfirmed(field.text?.toString().orEmpty())) {
                SaveResult.Success -> true // the state goes Hidden and closes the dialog
                SaveResult.InvalidHost -> { layout.error = getString(R.string.device_host_invalid); false }
                SaveResult.Duplicate -> { layout.error = getString(R.string.device_host_duplicate); false }
            }
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { layout.error = null }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        field.setOnEditorActionListener { _, _, _ -> confirm(); true }
        if (KeypadSoftkeys.isKeypadDevice(this)) {
            // Keypad: vela-style dialog (the field takes focus on open; arrows reach the buttons)
            var ok = false
            val okButton = KeypadDialogButton(getString(R.string.ok), {}, filled = true)
            return KeypadDialog.show(this, getString(R.string.add_device_dialog_title), box,
                listOfNotNull(
                    (state as? DialogState.Editing)?.let { editing ->
                        KeypadDialogButton(getString(R.string.sftp_action_mode_menu_delete), {
                            viewModel.onDialogDismissed()
                            viewModel.onDeviceDismissed(editing.editId)
                        })
                    },
                    KeypadDialogButton(getString(R.string.cancel), {}),
                    okButton,
                ),
                autoFocusIndex = null,
                onDismiss = { if (dialogFor != null && !ok) viewModel.onDialogDismissed() },
                keepOpen = { button -> button === okButton && !confirm().also { ok = it } },
            )
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_device_dialog_title)
            .setView(box)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
        if (state is DialogState.Editing) {
            builder.setNeutralButton(R.string.sftp_action_mode_menu_delete) { _, _ ->
                viewModel.onDialogDismissed()
                viewModel.onDeviceDismissed(state.editId)
            }
        }
        val d = builder.create()
        d.setOnDismissListener { if (dialogFor != null) viewModel.onDialogDismissed() }
        d.setOnShowListener {
            // OK keeps the dialog open on an invalid or duplicate host
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { confirm() }
            field.requestFocus()
        }
        d.show()
        return d
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<DeviceHostUiModel> = emptyList()

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isClickable = true
                setBackgroundColor(ViewBuilders.color(ctx, MR.attr.colorSurface))
                foreground = ViewBuilders.selectableBackground(ctx)
                setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(ViewBuilders.text(ctx, "", MR.attr.textAppearanceBodyLarge, MR.attr.colorOnSurface))
                addView(ViewBuilders.text(ctx, "", MR.attr.textAppearanceBodySmall).apply {
                    setPadding(0, dp(ctx, 2), 0, 0)
                })
            }
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val model = items[position]
            val row = holder.itemView as LinearLayout
            row.tag = model.id
            (row.getChildAt(0) as TextView).text = model.host.toString()
            (row.getChildAt(1) as TextView).text = when (val ping = model.pingState) {
                is PingState.InProgress -> getString(R.string.ping_in_progress)
                is PingState.Success -> getString(R.string.ping_result, ping.latencyMs)
                is PingState.Failed -> getString(R.string.ping_failed)
            }
            row.setOnClickListener { viewModel.openEditDialog(model.id) }
        }
    }

    companion object {
        const val KEY_CUSTOM_DEVICE_LIST_PREFERENCE = "device_list_preference"
        const val IP_DELIM = ","

        fun getCustomDeviceList(context: Context): ArrayList<DeviceHost> {
            val prefs = context.getSharedPreferences("${context.packageName}_preferences", MODE_PRIVATE)
            val raw = prefs.getString(KEY_CUSTOM_DEVICE_LIST_PREFERENCE, "") ?: ""
            val list = ArrayList<DeviceHost>()
            if (raw.isNotEmpty()) {
                for (entry in raw.split(IP_DELIM)) {
                    val host = DeviceHost.toDeviceHostOrNull(entry)
                    if (host != null) list.add(host)
                }
            }
            list.sortWith(compareBy { it.toString() })
            return list
        }
    }
}
