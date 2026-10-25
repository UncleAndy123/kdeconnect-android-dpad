/*
 * SPDX-FileCopyrightText: 2023 Dmitry Yudin <dgyudin@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.compose

import android.annotation.SuppressLint
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import org.kde.kdeconnect_tp.R

@Composable
fun KdeTextField(modifier: Modifier = Modifier, input: MutableState<String>, label: String) {
    var value by rememberSaveable { input }
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        // Compose only lets arrows leave a text field for SOURCE_DPAD keys; keypad phones
        // may report as a keyboard, which would trap focus in the field.
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                else -> false
            }
        },
        value = value,
        onValueChange = { userInput -> value = userInput },
        label = { Text(label) },
    )
}

@SuppressLint("UnrememberedMutableState")
@Preview
@Composable
fun Preview() {
    KdeTextField(
        input = mutableStateOf("John Doe"),
        label = stringResource(R.string.click_here_to_type),
    )
}
