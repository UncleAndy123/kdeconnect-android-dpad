/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins

import android.content.Context
import android.util.Log
import org.kde.kdeconnect.Device
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

object PluginFactory {
    annotation class LoadablePlugin  //Annotate plugins with this so PluginFactory finds them

    private var pluginInfoTask: FutureTask<Map<String, PluginInfo>> =
        FutureTask(Callable<Map<String, PluginInfo>> { emptyMap() }).also { it.run() }

    // Building the plugin info creates one instance of every plugin class (class loading and verification for all of
    // them), which costs about a second of app start on a slow phone. It is built on a background thread instead,
    // started first thing in Application.onCreate; anything that needs it waits for it (the device list doesn't).
    private val pluginInfo: Map<String, PluginInfo>
        get() = pluginInfoTask.get()

    fun initPluginInfo(context: Context) {
        val appContext = context.applicationContext ?: context
        pluginInfoTask = FutureTask(Callable<Map<String, PluginInfo>> {
            try {
                com.albertvaka.classindexksp.LoadablePlugin
                    .asSequence()
                    .map { it.java.getDeclaredConstructor().newInstance() as Plugin }
                    .onEach { it.setContext(appContext, null) }
                    .associate { Pair(it.pluginKey, PluginInfo(it)) }
                    .also { Log.i("PluginFactory", "Loaded ${it.size} plugins") }
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        })
        Thread(pluginInfoTask, "PluginInfo").start()
    }

    // The keys come straight from the class index, without waiting for the plugin info
    val availablePlugins: Set<String>
        get() = com.albertvaka.classindexksp.LoadablePlugin.asSequence().map { Plugin.getPluginKey(it.java) }.toSet()
    val incomingCapabilities: Set<String>
        get() = pluginInfo.values.flatMap { plugin -> plugin.supportedPacketTypes }.toSet()
    val outgoingCapabilities: Set<String>
        get() = pluginInfo.values.flatMap { plugin -> plugin.outgoingPacketTypes }.toSet()

    fun getPluginInfo(pluginKey: String): PluginInfo = pluginInfo[pluginKey]!!

    fun sortPluginList(plugins: Collection<String>): List<String> {
        return plugins.sortedBy { pluginInfo[it]?.displayName }
    }

    fun instantiatePluginForDevice(context: Context, pluginKey: String, device: Device): Plugin? {
        try {
            val plugin = pluginInfo[pluginKey]?.instantiableClass?.getDeclaredConstructor()?.newInstance()?.apply { setContext(context, device) }
            return plugin
        } catch (e: Exception) {
            Log.e("PluginFactory", "Could not instantiate plugin: $pluginKey", e)
            return null
        }
    }

    fun pluginsForCapabilities(incoming: Set<String>, outgoing: Set<String>): Set<String> {
        fun hasCommonCapabilities(info: PluginInfo): Boolean =
            outgoing.any { it in info.supportedPacketTypes } ||
            incoming.any { it in info.outgoingPacketTypes }

        val (used, unused) = pluginInfo.entries.partition { hasCommonCapabilities(it.value) }

        for (pluginId in unused.map { it.key }) {
            Log.d("PluginFactory", "Won't load $pluginId because of unmatched capabilities")
        }

        return used.map { it.key }.toSet()
    }

    class PluginInfo private constructor(
        val displayName: String,
        val description: String,
        val isEnabledByDefault: Boolean,
        val hasSettings: Boolean,
        val listenToUnpaired: Boolean,
        supportedPacketTypes: Array<String>,
        outgoingPacketTypes: Array<String>,
        val instantiableClass: Class<out Plugin>,
    ) {
        internal constructor(p: Plugin) : this(p.displayName, p.description,
            p.isEnabledByDefault, p.hasSettings(), p.listensToUnpairedDevices(),
            p.supportedPacketTypes, p.outgoingPacketTypes, p.javaClass)

        val supportedPacketTypes: Set<String> = supportedPacketTypes.toSet()
        val outgoingPacketTypes: Set<String> = outgoingPacketTypes.toSet()
    }
}
