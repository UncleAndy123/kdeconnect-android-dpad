/*
 * SPDX-FileCopyrightText: 2023 Dmitry Yudin <dgyudin@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.compose

import org.kde.kdeconnect.ui.keypad.keypadIconButtonRing
import org.kde.kdeconnect.ui.keypad.SoftKey
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.TopAppBarDefaults
import org.kde.kdeconnect.ui.keypad.KeypadSoftkeys
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kde.kdeconnect_tp.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KdeTopAppBar(
    title: String = stringResource(R.string.kde_connect),
    subTitle: String? = null,
    navIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    navIconDescription: String = "",
    navIconOnClick: () -> Unit, // = { onBackPressedDispatcher.onBackPressed() }
    actions: @Composable (RowScope.() -> Unit) = {},
    // Keypad phones (vela-dpad model): the screen's soft keys. When it binds any, they carry the
    // bar's actions and hardware BACK is back, so the bar is dropped to free the screen.
    softkeyLeft: SoftKey? = null,
    softkeyRight: SoftKey? = null,
) {
    if ((softkeyLeft != null || softkeyRight != null) && KeypadSoftkeys.Keys(softkeyLeft, softkeyRight)) return
    val tiny = isTinyScreen()
    TopAppBar(
        // 64dp is a fifth of a 320px keypad-phone screen
        expandedHeight = if (tiny) 48.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
        navigationIcon = {
            IconButton(onClick = navIconOnClick, modifier = Modifier.keypadIconButtonRing(), content = { Icon(navIcon, navIconDescription) })
        },
        title = {
            if (subTitle == null) {
                Text(
                    title,
                    maxLines = if (tiny) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    // Commented for now because the MDC and androidx toolbars don't set this either
                    // https://github.com/material-components/material-components-android/issues/4073
                    // https://github.com/androidx/androidx/blob/androidx-main/appcompat/appcompat/src/main/res/layout/abc_action_bar_title_item.xml
                    // modifier = Modifier.semantics { heading() }
                )
            } else {
                Column {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        actions = actions
    )
}
