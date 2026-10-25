/*
 * SPDX-FileCopyrightText: 2015 David Edmundson <kde@davidedmundson.co.uk>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mousepad

import android.view.inputmethod.BaseInputConnection

internal class KeyInputConnection(private val view: KeyListenerView, fullEditor: Boolean) :
    BaseInputConnection(view, fullEditor) {

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        view.sendChars(text)
        editable?.clear() // committed text replaces any pending composition
        return true
    }

    // Multi-tap / T9 keyboards compose text and then finish it instead of committing
    override fun finishComposingText(): Boolean {
        editable?.let { e ->
            val start = getComposingSpanStart(e)
            val end = getComposingSpanEnd(e)
            if (start in 0 until end) view.sendChars(e.subSequence(start, end).toString())
        }
        super.finishComposingText()
        editable?.clear()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val e = editable
        if (e != null && getComposingSpanStart(e) >= 0) {
            return super.deleteSurroundingText(beforeLength, afterLength) // edit the pending composition
        }
        repeat(beforeLength) { view.sendSpecialKey(1) } // Backspace
        repeat(afterLength) { view.sendSpecialKey(13) } // Delete
        return true
    }

}
