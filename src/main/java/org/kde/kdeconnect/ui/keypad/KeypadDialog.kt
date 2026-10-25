/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.keypad

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

/** A dialog button: [filled] is the higher-emphasis (confirm) action. */
class KeypadDialogButton(val text: String, val onClick: () -> Unit, val filled: Boolean = false)

/**
 * D-pad-first dialog, ported from vela-dpad's VelaDialog. A Material AlertDialog opens with its
 * window focused but no button Compose-focused, and nothing can pre-place that focus; a raw Dialog
 * with explicitly `.focusable()` buttons can. So this lands already focused (on [autoFocusIndex],
 * or on nothing when the body has its own focus target such as a text field), OK activates, arrows
 * move, BACK dismisses. The soft-key bar hides while it is up (KeypadSoftkeys watches window
 * focus). Styled like Material's AlertDialog; tighter on feature-phone screens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeypadDialog(
    onDismissRequest: () -> Unit,
    title: String,
    buttons: List<KeypadDialogButton>,
    autoFocusIndex: Int? = 0,
    text: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            // Cap at 90% and scroll the body so the buttons never fall off a small screen.
            // AdaptiveDensity.applied is the only correct small-screen test (it inflates dp).
            val compact = AdaptiveDensity.applied
            Column(
                Modifier
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                    .padding(if (compact) 16.dp else 24.dp),
            ) {
                Text(
                    title,
                    style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        text()
                    }
                }
                Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
                // FlowRow: buttons that don't fit on one line wrap instead of breaking mid-word
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    buttons.forEachIndexed { i, button ->
                        DialogButton(button.text, button.onClick, autoFocus = i == autoFocusIndex, filled = button.filled)
                    }
                }
            }
        }
    }
}

/** A directly-`.focusable()` text button: the only thing requestFocus lands on inside a raw Dialog
 *  (a Material TextButton's nested focusable doesn't). OK and touch tap both fire [onClick]. */
@Composable
private fun DialogButton(text: String, onClick: () -> Unit, autoFocus: Boolean, filled: Boolean) {
    val requester = remember { FocusRequester() }
    val keypad = rememberKeypadDevice()
    if (autoFocus) {
        LaunchedEffect(keypad) {
            if (keypad) repeat(30) {
                if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(50)
            }
        }
    }
    // The ring, the pill fill and the padding share one shape so the ring hugs the button
    val shape = if (filled) CircleShape else RoundedCornerShape(20.dp)
    Text(
        text,
        color = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .focusRequester(requester)
            .keypadFocusRing(shape)
            .then(if (filled) Modifier.background(MaterialTheme.colorScheme.primary, shape) else Modifier)
            .onKeyEvent { ev ->
                if ((ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter) && ev.type == KeyEventType.KeyUp) {
                    onClick(); true
                } else {
                    false
                }
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = if (filled) 24.dp else 12.dp, vertical = 10.dp),
    )
}
