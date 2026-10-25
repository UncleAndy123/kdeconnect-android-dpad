/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.base

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import org.kde.kdeconnect.ui.keypad.AdaptiveDensity

/** Base for every activity: gives tiny feature-phone screens enough logical width to lay out. */
abstract class KdeActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AdaptiveDensity.wrap(newBase))
    }
}
