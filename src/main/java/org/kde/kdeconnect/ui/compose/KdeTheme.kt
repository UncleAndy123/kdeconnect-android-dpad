/*
 * SPDX-FileCopyrightText: 2024 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.compose

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import org.kde.kdeconnect.ui.keypad.AdaptiveDensity
import org.kde.kdeconnect.ui.keypad.rememberKeypadDevice

/** True on tiny screens such as 240x320 keypad flip phones (also after AdaptiveDensity scaled them). */
@Composable
fun isTinyScreen(): Boolean {
    LocalConfiguration.current // re-read on configuration changes
    return AdaptiveDensity.isSmallScreen(LocalContext.current)
}

// Keypad devices: the orange focus ring (keypadFocusRing) is the focus signal, so drop Material's
// grey focus layer instead of doubling up (as vela-dpad does). Touch phones keep the defaults.
@OptIn(ExperimentalMaterial3Api::class)
private val keypadRipple = RippleConfiguration(
    rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0f, hoveredAlpha = 0.08f, pressedAlpha = 0.10f)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KdeTheme(context : Context, content: @Composable () -> Unit) {
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        when (isSystemInDarkTheme()) {
            true -> dynamicDarkColorScheme(context)
            false -> dynamicLightColorScheme(context)
        }
    } else {
        when (isSystemInDarkTheme()) {
            true -> darkColorScheme()
            false -> lightColorScheme()
        }
    }
    val keypad = rememberKeypadDevice()
    MaterialTheme(colorScheme = colorScheme) {
        if (keypad) {
            CompositionLocalProvider(LocalRippleConfiguration provides keypadRipple, content = content)
        } else {
            content()
        }
    }
}
