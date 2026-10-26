/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.digitizer

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DigitizerPluginTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun plugin() = DigitizerPlugin().apply { setContext(context, null) }

    @After
    fun cleanup() {
        Settings.Global.putInt(context.contentResolver, "kdeconnect_force_dpad", 0)
    }

    @Test
    fun enabledByDefaultOnKeypadPhones() {
        Settings.Global.putInt(context.contentResolver, "kdeconnect_force_dpad", 1)
        assertTrue(plugin().isEnabledByDefault)
    }

    @Test
    fun touchPhonesKeepUpstreamDefault() {
        // A normal-size touch phone: upstream only enables it on tablets
        assertFalse(plugin().isEnabledByDefault)
    }
}
