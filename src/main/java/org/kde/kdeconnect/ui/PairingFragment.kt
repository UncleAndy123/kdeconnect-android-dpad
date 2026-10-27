/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui

import org.kde.kdeconnect.ui.keypad.SoftkeyOption
import org.kde.kdeconnect.ui.keypad.SoftKey
import org.kde.kdeconnect.ui.keypad.KeypadSoftkeys
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.MenuProvider
import androidx.fragment.app.viewModels
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.kde.kdeconnect.BackgroundService.Companion.ForceRefreshConnections
import org.kde.kdeconnect.BackgroundService.Companion.instance
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.base.BaseFragment
import org.kde.kdeconnect.helpers.TrustedNetworkHelper.Companion.isTrustedNetwork
import org.kde.kdeconnect.ui.compose.model.device.DeviceUiModel
import org.kde.kdeconnect.ui.compose.screen.pairing.PairingUiState
import org.kde.kdeconnect.ui.compose.screen.pairing.PairingViewModel
import org.kde.kdeconnect.ui.views.ViewBuilders
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.PairingListBinding

/**
 * The view that the user will see when there are no devices paired, or when you choose "add a new device" from the sidebar.
 */
class PairingFragment : BaseFragment<PairingListBinding>() {

    private val viewModel by viewModels<PairingViewModel>()

    private var listRefreshCalledThisFrame = false

    private val menuProvider = object : MenuProvider {

        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.pairing, menu)
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            return when (menuItem.itemId) {
                R.id.menu_refresh -> {
                    viewModel.onRefresh()
                    true
                }

                R.id.menu_custom_device_list -> {
                    startActivity(Intent(mActivity, CustomDevicesActivity::class.java))
                    true
                }

                R.id.menu_trusted_networks -> {
                    startActivity(Intent(mActivity, TrustedNetworksActivity::class.java))
                    true
                }

                else -> false
            }
        }
    }

    override fun getActionBarTitle() = getString(R.string.pairing_title)

    override fun onInflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): PairingListBinding = PairingListBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mActivity?.addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)

        binding.refresh.setOnRefreshListener { viewModel.onRefresh() }
        org.kde.kdeconnect.ui.views.ViewBuilders.unfocusableScroll(binding.scroll)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pairingUiState.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Keypad soft keys (vela-dpad model): LEFT = Options (this screen's menu plus the drawer's Settings /
        // About, since the toolbar is hidden), RIGHT = Refresh
        val main = mActivity as? MainActivity ?: return
        KeypadSoftkeys.bind(
            main,
            KeypadSoftkeys.menuOptionsKey(main, listOf(
                SoftkeyOption(getString(R.string.settings)) { main.openSettings() },
                SoftkeyOption(getString(R.string.about)) { main.openAbout() },
            ), exclude = setOf(R.id.menu_refresh)),
            SoftKey(getString(R.string.refresh)) { viewModel.onRefresh() },
        )
    }

    fun hasRequestedPermission(permission: String): Boolean {
        return context
            ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getBoolean(permission, false)
            ?: false
    }

    fun markPermissionRequested(permission: String) {
        context
            ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit {
                putBoolean(permission, true)
            }
    }

    private var renderedOnce = false

    /** Builds the list from the state: explanations, then connected / available / remembered devices. */
    private fun render(state: PairingUiState) {
        if (!hasBinding) return
        val list = binding.list
        val focusedId = mActivity?.currentFocus?.tag as? String
        binding.refresh.isRefreshing = state.isRefreshing
        list.removeAllViews()

        addExplanations(list, state)

        val all = state.connected + state.available + state.remembered
        list.addView(sectionHeader(getString(R.string.category_connected_devices)))
        if (state.connected.isEmpty()) {
            list.addView(TextView(requireContext()).apply {
                text = getString(R.string.device_list_empty)
                textAppearance(this, com.google.android.material.R.attr.textAppearanceBodySmall)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
            })
        } else {
            list.addView(spacer(4))
            state.connected.forEach { list.addView(deviceCard(it)) }
        }
        if (state.available.isNotEmpty()) {
            list.addView(sectionHeader(getString(R.string.category_not_paired_devices)))
            list.addView(spacer(4))
            state.available.forEach { list.addView(deviceCard(it)) }
        }
        if (state.remembered.isNotEmpty()) {
            list.addView(sectionHeader(getString(R.string.category_remembered_devices)))
            list.addView(spacer(4))
            state.remembered.forEach { list.addView(deviceCard(it)) }
        }

        // Keep the D-pad focus on the same device across updates; keypad phones open with the first device focused
        val target = focusedId?.let { list.findViewWithTag<View>(it) }
            ?: if (!renderedOnce || mActivity?.currentFocus == null) all.firstOrNull()?.let { list.findViewWithTag<View>(it.id) } else null
        if (target != null && KeypadSoftkeys.isKeypadDevice(requireContext())) target.requestFocus()
        renderedOnce = true
    }

    private fun addExplanations(list: LinearLayout, state: PairingUiState) {
        if (!state.hasLocalNetworkPermission) {
            list.addView(explanationRow(getString(R.string.pairing_missing_local_network_permission), R.drawable.ic_warning) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) onLocalNetworkPermissionClick()
            })
        }
        if (!state.hasNotificationsPermission) {
            list.addView(explanationRow(getString(R.string.no_notifications), R.drawable.ic_warning) { onNotificationSettingsClick() })
        }
        if (!state.hasLocalNetworkPermission || !state.hasNotificationsPermission) return

        if (state.hasDuplicateNames) {
            list.addView(explanationRow(getString(R.string.pairing_duplicate_names), R.drawable.ic_warning))
        }
        val someDevicesReachable = state.available.isNotEmpty() || state.connected.isNotEmpty()
        if (someDevicesReachable || state.isWifiAvailable) {
            if (state.isTrustedNetwork) {
                list.addView(explanationRow(getString(R.string.pairing_description)))
            } else {
                list.addView(explanationRow(getString(R.string.on_non_trusted_message), R.drawable.ic_warning))
            }
        } else {
            list.addView(explanationRow(getString(R.string.no_wifi), R.drawable.ic_wifi) {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            })
        }
    }

    private fun explanationRow(text: String, icon: Int? = null, onClick: (() -> Unit)? = null): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            if (icon != null) {
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    ImageViewCompat.setImageTintList(this, android.content.res.ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant)))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            }
            addView(TextView(context).apply {
                this.text = text
                textAppearance(this, com.google.android.material.R.attr.textAppearanceBodyMedium)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (onClick != null) {
                isFocusable = true
                // Keypad phones start in touch mode until their first key press: stay focusable then too
                isFocusableInTouchMode = org.kde.kdeconnect.ui.keypad.KeypadSoftkeys.isKeypadDevice(context)
                isClickable = true
                setBackgroundResource(attrRes(android.R.attr.selectableItemBackground))
                setOnClickListener { onClick() }
            }
        }

    private fun sectionHeader(title: String): View = ViewBuilders.sectionHeader(requireContext(), title)

    private fun spacer(heightDp: Int): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun deviceCard(device: DeviceUiModel): View =
        ViewBuilders.deviceCard(requireContext(), device.id, device.icon, device.name,
            if (device.summaryRes > 0) getString(device.summaryRes) else null
        ) { viewModel.getDeviceById(device.id)?.let(::deviceClicked) }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }

    private fun onNotificationSettingsClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasRequestedBefore = hasRequestedPermission(Manifest.permission.POST_NOTIFICATIONS)
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), Manifest.permission.POST_NOTIFICATIONS)
            if (hasRequestedBefore && !shouldShowRationale) {
                openAppDetailsSettings()
            } else {
                markPermissionRequested(Manifest.permission.POST_NOTIFICATIONS)
                notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            openAppDetailsSettings()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    private fun onLocalNetworkPermissionClick() {
        val hasRequestedBefore = hasRequestedPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), Manifest.permission.ACCESS_LOCAL_NETWORK)
        if (hasRequestedBefore && !shouldShowRationale) {
            openAppDetailsSettings()
        } else {
            markPermissionRequested(Manifest.permission.ACCESS_LOCAL_NETWORK)
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    private fun dp(v: Int): Int = ViewBuilders.dp(requireContext(), v)

    private fun color(attr: Int): Int = ViewBuilders.color(requireContext(), attr)

    private fun attrRes(attr: Int): Int = ViewBuilders.attrRes(requireContext(), attr)

    private fun textAppearance(view: TextView, attr: Int) {
        ViewBuilders.styled(view, attr)
    }

    private val notificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                updatePermissionsHeader()
                KdeConnect.getInstance().reloadAllDevicePlugins()
            }
        }

    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                updatePermissionsHeader()
                ForceRefreshConnections(requireContext())
            }
        }

    private val appSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updatePermissionsHeader()
        }

    private fun openAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", requireContext().packageName, null)
        )
        appSettingsLauncher.launch(intent)
    }

    private fun updateDeviceList() {
        if (!isAdded) {
            //Fragment is not attached to an activity. We will crash if we try to do anything here.
            return
        }

        if (listRefreshCalledThisFrame) {
            // This makes sure we don't try to call list.getFirstVisiblePosition()
            // twice per frame, because the second time the list hasn't been drawn
            // yet and it would always return 0.
            return
        }
        listRefreshCalledThisFrame = true

        //Check if we're on Wi-Fi/Local network. If we still see a device, don't do anything special
        val service = instance
        if (service == null) {
            updateConnectivityInfoHeader(isConnectedToNonCellularNetwork = true)
        } else {
            service.isConnectedToNonCellularNetwork.observe(
                viewLifecycleOwner,
                ::updateConnectivityInfoHeader
            )
        }

        updatePermissionsHeader()

        try {
            val allDevices = KdeConnect.getInstance().devices.values.filter {
                it.isReachable || it.isPaired
            }

            viewModel.updateDevices(devices = allDevices)
        } catch (_: IllegalStateException) {
            // Ignore: The activity was closed while we were trying to update it
        } finally {
            listRefreshCalledThisFrame = false
        }
    }

    private fun updateConnectivityInfoHeader(isConnectedToNonCellularNetwork: Boolean) {
        viewModel.updateConnectivity(
            isWifiAvailable = isConnectedToNonCellularNetwork,
            isTrustedNetwork = isTrustedNetwork(requireContext())
        )
    }

    fun updatePermissionsHeader() {
        val hasNotificationsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PERMISSION_GRANTED

        val hasLocalNetworkPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN
                || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_LOCAL_NETWORK) == PERMISSION_GRANTED

        viewModel.updatePermissions(
            hasNotificationsPermission = hasNotificationsPermission,
            hasLocalNetworkPermission = hasLocalNetworkPermission,
        )
    }

    override fun onDestroyView() {
        mActivity?.let { KeypadSoftkeys.clear(it) }
        renderedOnce = false
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        KdeConnect.getInstance().addDeviceListChangedCallback("PairingFragment") {
            mActivity?.runOnUiThread { this.updateDeviceList() }
        }
        ForceRefreshConnections(requireContext()) // force a network re-discover
        updateDeviceList()
    }

    override fun onStop() {
        KdeConnect.getInstance().removeDeviceListChangedCallback("PairingFragment")
        super.onStop()
    }

    fun deviceClicked(device: Device) {
        (mActivity as? MainActivity)?.onDeviceSelected(
            device.deviceId,
            !device.isPaired || !device.isReachable
        )
    }

    companion object {
        private const val PREFS_NAME = "permission_prefs"
    }
}
