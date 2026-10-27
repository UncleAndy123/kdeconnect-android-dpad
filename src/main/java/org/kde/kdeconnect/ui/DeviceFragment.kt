/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui

import android.app.Activity
import org.kde.kdeconnect.ui.keypad.SoftkeyOption
import org.kde.kdeconnect.ui.keypad.SoftKey
import org.kde.kdeconnect.ui.keypad.KeypadSoftkeys
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.annotation.UiThread
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.Device.PluginsChangedListener
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.PairingHandler
import org.kde.kdeconnect.base.BaseFragment
import org.kde.kdeconnect.extensions.setupBottomPadding
import org.kde.kdeconnect.helpers.security.SslHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.battery.BatteryPlugin
import org.kde.kdeconnect.plugins.connectivityreport.ConnectivityReportPlugin
import org.kde.kdeconnect.ui.compose.screen.device.DeviceViewModel
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityDeviceBinding

/**
 * Main view. Displays the current device and its plugins
 */
class DeviceFragment : BaseFragment<ActivityDeviceBinding>() {

    companion object {
        private const val ARG_DEVICE_ID = "deviceId"
        private const val ARG_FROM_DEVICE_LIST = "fromDeviceList"
        private const val TAG = "KDE/DeviceFragment"
        fun newInstance(deviceId: String, fromDeviceList: Boolean): DeviceFragment {
            val frag = DeviceFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DEVICE_ID, deviceId)
                    putBoolean(ARG_FROM_DEVICE_LIST, fromDeviceList)
                }
            }
            return frag
        }
    }

    override fun getActionBarTitle() = null

    val deviceId: String by lazy {
        arguments?.getString(ARG_DEVICE_ID)
            ?: throw RuntimeException("You must instantiate a new DeviceFragment using DeviceFragment.newInstance()")
    }

    private val device by lazy { KdeConnect.getInstance().getDevice(deviceId) }

    private val viewModel by viewModels<DeviceViewModel>()

    override fun onInflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): ActivityDeviceBinding {
        val fromDeviceList = requireArguments().getBoolean(ARG_FROM_DEVICE_LIST, false)
        if (fromDeviceList) {
            val callback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Handle back button, so we go to the list of devices in case we came from there
                    (mActivity as? MainActivity)?.onDeviceSelected(null)
                }
            }
            requireActivity().onBackPressedDispatcher.addCallback(getViewLifecycleOwner(), callback)
        }
        return ActivityDeviceBinding.inflate(inflater, container, false)
    }

    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menu.clear()
            val device = device ?: return

            //Plugins button list
            val menuEntries: Collection<Plugin.PluginUiMenuEntry> =
                device.loadedPlugins.values.flatMap { it.getUiMenuEntries() }
            for (p in menuEntries) {
                menu.add(p.name).setOnMenuItemClickListener {
                    p.onClick(mActivity!!)
                    true
                }
            }
            val intent = PluginSettingsActivity.createIntent(mActivity!!, deviceId)
            menu.add(R.string.device_menu_plugins).setOnMenuItemClickListener {
                startActivity(intent)
                true
            }
            if (device.isReachable) {
                val builder = MaterialAlertDialogBuilder(requireContext())
                builder.setTitle(requireContext().resources.getString(R.string.encryption_info_title))
                builder.setPositiveButton(requireContext().resources.getString(R.string.ok)) { dialog, _ ->
                    dialog.dismiss()
                }
                builder.setMessage(
                    "${
                        requireContext().resources.getString(R.string.my_device_fingerprint)
                    } \n ${
                        SslHelper.getCertificateHash(SslHelper.certificate)
                    } \n\n ${
                        requireContext().resources.getString(R.string.remote_device_fingerprint)
                    } \n ${
                        SslHelper.getCertificateHash(device.certificate)
                    } \n\n ${
                        requireContext().resources.getString(R.string.protocol_version)
                    } ${
                        device.protocolVersion
                    }"
                )
                menu.add(R.string.encryption_info_title).setOnMenuItemClickListener {
                    builder.show()
                    true
                }
            }
            if (device.isPaired) {
                menu.add(R.string.device_menu_unpair).setOnMenuItemClickListener {
                    device.apply {
                        // Remove listener so buttons don't show for an instant before changing the view
                        removePairingCallback(pairingCallback)
                        removePluginsChangedListener(pluginsChangedListener)
                        unpair()
                    }
                    (mActivity as? MainActivity)?.onDeviceSelected(null)
                    true
                }
            }
            if (device.pairStatus == PairingHandler.PairState.Requested) {
                menu.add(R.string.cancel_pairing).setOnMenuItemClickListener {
                    device.cancelPairing()
                    true
                }
            }
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            return true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.deviceView.setupBottomPadding()
        org.kde.kdeconnect.ui.views.ViewBuilders.unfocusableScroll(binding.deviceView)
        device?.apply {
            mActivity?.supportActionBar?.title = name
            removePairingCallback(pairingCallback)
            addPairingCallback(pairingCallback)
            removePluginsChangedListener(pluginsChangedListener)
            addPluginsChangedListener(pluginsChangedListener)
        } ?: run { // device is null
            Log.e(TAG, "Trying to display a device fragment but the device is not present")
            (mActivity as? MainActivity)?.onDeviceSelected(null)
        }
        mActivity?.addMenuProvider(menuProvider, viewLifecycleOwner)
        refreshUI()
    }

    private fun refreshDevicesAction() {
        BackgroundService.ForceRefreshConnections(requireContext())
        viewModel.setRefreshing(refreshing = true)
        binding.deviceView.postDelayed({
            if (isResumed && !isDetached) { // the view might be destroyed by now
                viewModel.setRefreshing(refreshing = false)
            }
        }, 1500)
    }

    override fun onPause() {
        viewModel.setRefreshing(refreshing = false)
        super.onPause()
    }

    private val pluginsChangedListener =
        PluginsChangedListener { mActivity?.runOnUiThread { refreshUI() } }

    override fun onDestroyView() {
        refreshingCollected = false
        boundKeys = null
        firstControl = null
        shownSignature = null
        mActivity?.let { KeypadSoftkeys.clear(it) }
        device?.apply {
            removePluginsChangedListener(pluginsChangedListener)
            removePairingCallback(pairingCallback)
        }
        super.onDestroyView()
    }

    // This screen's soft keys and first control, re-applied on resume: when this screen replaces another one,
    // that screen's view is torn down (clearing its keys) after this one was built
    private var boundKeys: Pair<SoftKey?, SoftKey?>? = null
    private var firstControl: View? = null

    private fun bindKeys(activity: Activity, left: SoftKey?, right: SoftKey?) {
        boundKeys = left to right
        KeypadSoftkeys.bind(activity, left, right)
    }

    override fun onResume() {
        super.onResume()
        mActivity?.let { activity ->
            boundKeys?.let { (left, right) -> KeypadSoftkeys.bind(activity, left, right) }
            if (KeypadSoftkeys.isKeypadDevice(activity)) {
                val focus = activity.currentFocus
                val inContent = focus != null && generateSequence(focus.parent) { it.parent }.any { it === binding.content }
                if (!inContent) firstControl?.requestFocus()
            }
        }
        with(requireView()) {
            // In non-touch (D-pad) mode a focused root swallows navigation; let the content take focus. Keypad
            // phones too, even in touch mode (they start in it until their first key press): the ring would sit on
            // the whole page
            if (!isInTouchMode || KeypadSoftkeys.isKeypadDevice(context)) return
            isFocusableInTouchMode = true
            requestFocus()
        }
    }

    @UiThread
    private fun refreshUI() {
        if (!hasBinding) return // in case onDestroyView has already been called
        val device = device ?: return
        val activity = mActivity ?: return

        //Once in-app, there is no point in keep displaying the notification if any
        device.hidePairingNotification()

        val content = binding.content
        // Plugins load (and report "changed") several times while a device connects: when the screen would come out
        // the same (same plugin instances, buttons and permission lists), keep the views instead of rebuilding them
        val signature = if (device.pairStatus == PairingHandler.PairState.Paired && device.isReachable) pluginsSignature(device) else null
        if (signature != null && signature == shownSignature && content.childCount > 0) {
            displayBatteryInfoIfPossible()
            return
        }
        shownSignature = signature
        val focusedTag = activity.currentFocus?.tag as? String
        content.removeAllViews()
        binding.refresh.isEnabled = false

        // Keypad soft keys (vela-dpad model): LEFT = Options (this screen's menu, plus the device list since the
        // drawer's toolbar is hidden), RIGHT = the primary action
        val optionsKey = KeypadSoftkeys.menuOptionsKey(activity, listOfNotNull((activity as? MainActivity)?.let { main ->
            SoftkeyOption(getString(R.string.softkey_all_devices)) { main.openDeviceList() }
        }))

        var firstFocusable: View? = null
        when (device.pairStatus) {
            PairingHandler.PairState.Paired -> {
                if (device.isReachable) {
                    bindKeys(activity, optionsKey, null)
                    firstFocusable = addPluginsScreen(content, device)
                    displayBatteryInfoIfPossible()
                } else {
                    bindKeys(activity, optionsKey, SoftKey(getString(R.string.refresh)) { refreshDevicesAction() })
                    firstFocusable = addErrorScreen(content, showRefreshButton = !KeypadSoftkeys.hasKeys(activity))
                }
            }

            else -> {
                val onRequestPairing = {
                    viewModel.setPairingErrorMessage(message = null)
                    device.requestPairing()
                    refreshUI()
                }
                val onAcceptPairing = {
                    device.acceptPairing()
                    refreshUI()
                }
                val onRejectPairing = {
                    device.apply {
                        // Remove listener so buttons don't show for an instant before changing the view
                        removePluginsChangedListener(pluginsChangedListener)
                        removePairingCallback(pairingCallback)
                        cancelPairing()
                    }
                    (mActivity as? MainActivity)?.onDeviceSelected(null)
                    Unit
                }
                when (device.pairStatus) {
                    PairingHandler.PairState.NotPaired -> bindKeys(activity, optionsKey,
                        SoftKey(getString(R.string.softkey_pair)) { onRequestPairing() })
                    PairingHandler.PairState.RequestedByPeer -> bindKeys(activity,
                        SoftKey(getString(R.string.pairing_reject)) { onRejectPairing() },
                        SoftKey(getString(R.string.pairing_accept)) { onAcceptPairing() })
                    else -> bindKeys(activity, optionsKey, null)
                }
                firstFocusable = addPairingScreen(content, device, !KeypadSoftkeys.hasKeys(activity),
                    onRequestPairing, onAcceptPairing, onRejectPairing)
            }
        }

        // Keypad phones: keep the focus on the same control across refreshes; a new screen opens with its first
        // control focused
        if (KeypadSoftkeys.isKeypadDevice(activity)) {
            val keep = focusedTag?.let { content.findViewWithTag<View>(it) }
            (keep ?: firstFocusable)?.requestFocus()
        }
        firstControl = firstFocusable
        activity.invalidateOptionsMenu()
    }

    /** What the plugins screen shows, to tell whether a refresh would change it */
    private var shownSignature: String? = null

    private fun pluginsSignature(device: Device): String = buildString {
        for (plugin in device.loadedPlugins.values) {
            append(System.identityHashCode(plugin)).append(':')
            for (button in plugin.getUiButtons()) append(button.name).append('/').append(button.iconRes).append(',')
            append(';')
        }
        append('|').append(device.pluginsWithoutPermissions.keys.filter { device.isPluginEnabled(it) }.sorted())
        append('|').append(device.pluginsWithoutOptionalPermissions.keys.filter { device.isPluginEnabled(it) }.sorted())
    }

    /** Plugin buttons in a grid, then the plugins that still need a permission. Returns the first button. */
    private fun addPluginsScreen(content: LinearLayout, device: Device): View? {
        val context = requireContext()
        val buttons = device.loadedPlugins.values.flatMap { plugin -> plugin.getUiButtons() }
        val needPermissions = device.pluginsWithoutPermissions.values.filter { device.isPluginEnabled(it.pluginKey) }
        val needOptional = device.pluginsWithoutOptionalPermissions.values.filter { device.isPluginEnabled(it.pluginKey) }
        val columns = resources.getInteger(R.integer.plugins_columns)

        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), 0)
        }
        var first: View? = null
        buttons.chunked(columns).forEach { rowButtons ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until columns) {
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(8)
                }
                val button = rowButtons.getOrNull(i)
                if (button == null) {
                    row.addView(View(context), lp)
                    continue
                }
                val card = pluginButton(button)
                if (first == null) first = card
                row.addView(card, lp)
            }
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        content.addView(grid)
        content.addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12)))
        if (needPermissions.isNotEmpty()) {
            addPluginsWithoutPermissions(content, getString(R.string.plugins_need_permission), needPermissions) { plugin ->
                plugin.permissionExplanationDialog.show(childFragmentManager, null)
            }
            content.addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)))
        }
        if (needOptional.isNotEmpty()) {
            addPluginsWithoutPermissions(content, getString(R.string.plugins_need_optional_permission), needOptional) { plugin ->
                plugin.optionalPermissionExplanationDialog.show(childFragmentManager, null)
            }
        }
        return first
    }

    private fun pluginButton(button: Plugin.PluginUiButton): View {
        val context = requireContext()
        return MaterialCardView(context, null, com.google.android.material.R.attr.materialCardViewFilledStyle).apply {
            tag = "plugin:" + button.name
            isFocusable = true
            // Keypad phones start in touch mode until their first key press: stay focusable then too
            isFocusableInTouchMode = org.kde.kdeconnect.ui.keypad.KeypadSoftkeys.isKeypadDevice(context)
            isClickable = true
            contentDescription = button.name
            setOnClickListener { mActivity?.let { button.onClick(it) } }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                addView(ImageView(context).apply {
                    setImageResource(button.iconRes)
                    ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurface)))
                }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { topMargin = dp(12) })
                addView(TextView(context).apply {
                    text = button.name
                    setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    maxLines = 2
                    minLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                })
            })
        }
    }

    private fun addPluginsWithoutPermissions(content: LinearLayout, title: String, plugins: Collection<Plugin>, action: (Plugin) -> Unit) {
        val context = requireContext()
        content.addView(TextView(context).apply {
            text = title
            textAppearance(this, com.google.android.material.R.attr.textAppearanceBodyLarge)
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(16), dp(6), dp(16), dp(6))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        plugins.forEach { plugin ->
            content.addView(TextView(context).apply {
                text = plugin.displayName
                tag = "permission:" + plugin.pluginKey
                textAppearance(this, com.google.android.material.R.attr.textAppearanceBodyLarge)
                setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
                setPadding(dp(28), dp(12), dp(16), dp(12))
                isFocusable = true
                // Keypad phones start in touch mode until their first key press: stay focusable then too
                isFocusableInTouchMode = org.kde.kdeconnect.ui.keypad.KeypadSoftkeys.isKeypadDevice(context)
                isClickable = true
                setBackgroundResource(attrRes(android.R.attr.selectableItemBackground))
                setOnClickListener { action(plugin) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** The device is paired but not reachable. Returns the retry button, if shown. */
    private fun addErrorScreen(content: LinearLayout, showRefreshButton: Boolean): View? {
        val context = requireContext()
        binding.refresh.isEnabled = true
        binding.refresh.setOnRefreshListener { refreshDevicesAction() }
        lifecycleScopeCollectRefreshing()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_error_outline_48dp)
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant)))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            addView(TextView(context).apply {
                text = getString(R.string.unreachable_description)
                textAppearance(this, com.google.android.material.R.attr.textAppearanceBodyLarge)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        var retry: View? = null
        // Pull-to-refresh needs touch: a focusable retry, unless the RIGHT soft key already is one
        if (showRefreshButton) {
            retry = MaterialButton(context).apply {
                text = getString(R.string.refresh)
                setOnClickListener { refreshDevicesAction() }
            }
            column.addView(retry, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            })
        }
        content.addView(column, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)) // centred in the screen
        return retry
    }

    private var refreshingCollected = false

    private fun lifecycleScopeCollectRefreshing() {
        if (refreshingCollected) return
        refreshingCollected = true
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isRefreshing.collect { if (hasBinding) binding.refresh.isRefreshing = it }
            }
        }
    }

    /** Not paired, pairing requested, or a pairing request from the other device. Returns the first button shown. */
    private fun addPairingScreen(
        content: LinearLayout,
        device: Device,
        showButtons: Boolean,
        onRequestPairing: () -> Unit,
        onAcceptPairing: () -> Unit,
        onRejectPairing: () -> Unit,
    ): View? {
        val context = requireContext()
        val pairStatus = device.pairStatus
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val message = viewModel.pairingErrorMessage.value ?: when (pairStatus) {
            PairingHandler.PairState.NotPaired -> getString(R.string.device_not_paired)
            else -> getString(R.string.pair_requested)
        }
        column.addView(TextView(context).apply {
            text = message
            textAppearance(this, com.google.android.material.R.attr.textAppearanceTitleMedium)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        column.addView(TextView(context).apply {
            text = getString(R.string.pairing_explanation)
            textAppearance(this, com.google.android.material.R.attr.textAppearanceBodyMedium)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        if (pairStatus != PairingHandler.PairState.NotPaired) {
            column.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_key)
                    ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant)))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(5) })
                addView(TextView(context).apply {
                    text = device.verificationKey ?: ""
                    textAppearance(this, com.google.android.material.R.attr.textAppearanceTitleMedium)
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        if (pairStatus == PairingHandler.PairState.Requested) {
            column.addView(CircularProgressIndicator(context).apply { isIndeterminate = true },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(8); bottomMargin = dp(8)
                })
        }
        var first: View? = null
        if (showButtons && pairStatus == PairingHandler.PairState.NotPaired) {
            first = MaterialButton(context).apply {
                text = getString(R.string.request_pairing)
                setOnClickListener { onRequestPairing() }
            }
            column.addView(first, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        if (showButtons && pairStatus == PairingHandler.PairState.RequestedByPeer) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }
            first = MaterialButton(context).apply {
                text = getString(R.string.pairing_accept)
                setOnClickListener { onAcceptPairing() }
            }
            row.addView(first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(context).apply {
                text = getString(R.string.pairing_reject)
                setOnClickListener { onRejectPairing() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            column.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        content.addView(column)
        return first
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun color(attr: Int): Int = MaterialColors.getColor(requireContext(), attr, Color.GRAY)

    private fun attrRes(attr: Int): Int = TypedValue().also { requireContext().theme.resolveAttribute(attr, it, true) }.resourceId

    // Text style + onSurfaceVariant, the colour the screen's texts used
    private fun textAppearance(view: TextView, attr: Int) {
        val res = attrRes(attr)
        if (res != 0) TextViewCompat.setTextAppearance(view, res)
        view.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private val pairingCallback: PairingHandler.PairingCallback =
        object : PairingHandler.PairingCallback {
            override fun incomingPairRequest() {
                mActivity?.runOnUiThread { refreshUI() }
            }

            override fun pairingSuccessful() {
                val accessibilityManager =
                    requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                if (accessibilityManager?.isEnabled == true) {
                    @Suppress("DEPRECATION")
                    val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        AccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
                    } else {
                        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
                    }
                    event.text.add(getString(R.string.pair_succeeded))
                    accessibilityManager.sendAccessibilityEvent(event)
                }
                mActivity?.runOnUiThread { refreshUI() }
            }

            override fun pairingFailed(error: String) {
                mActivity?.runOnUiThread {
                    viewModel.setPairingErrorMessage(message =error)
                    refreshUI()
                }
            }

            override fun unpaired(device: Device) {
                mActivity?.runOnUiThread {
                    viewModel.setPairingErrorMessage(message =null)
                    refreshUI()
                }
            }
        }


    /**
     * This method tries to display status info (battery and connectivity) for the remote device.
     */
    private fun displayBatteryInfoIfPossible() {
        val batteryPlugin = device?.getPlugin<BatteryPlugin>()
        val connectivityPlugin = device?.getPlugin<ConnectivityReportPlugin>()

        val batteryInfo = batteryPlugin?.remoteBatteryInfo
        val batteryStatus = if (batteryInfo != null) {
            @StringRes
            val resId = when {
                batteryInfo.isCharging -> R.string.battery_status_charging_format
                BatteryPlugin.isLowBattery(batteryInfo) -> R.string.battery_status_low_format
                else -> R.string.battery_status_format
            }
            mActivity?.getString(resId, batteryInfo.currentCharge)
        } else {
            null
        }

        val connectivityInfoMap = connectivityPlugin?.remoteConnectivityInfo
        val connectivityStatus = if (!connectivityInfoMap.isNullOrEmpty()) {
            connectivityInfoMap.values
                .map { it.networkType }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
                .ifEmpty { null }
        } else {
            null
        }

        val fullSubtitle = when {
            batteryStatus != null && connectivityStatus != null -> "$batteryStatus  •  $connectivityStatus"
            batteryStatus != null -> batteryStatus
            connectivityStatus != null -> connectivityStatus
            else -> null
        }

        mActivity?.supportActionBar?.subtitle = fullSubtitle
    }
}
