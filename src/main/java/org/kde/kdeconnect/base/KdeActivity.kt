/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.base

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.kde.kdeconnect.ui.keypad.AdaptiveDensity
import org.kde.kdeconnect.ui.keypad.KeypadSoftkeys
import org.kde.kdeconnect_tp.R

/** Base for every activity: gives tiny feature-phone screens enough logical width to lay out. */
abstract class KdeActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AdaptiveDensity.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Keypad phones: one focus signal (the orange ring), not the ring plus Material's grey layer
        if (KeypadSoftkeys.isKeypadDevice(this)) theme.applyStyle(R.style.KeypadNoFocusHighlight, true)
        super.onCreate(savedInstanceState)
    }
}
