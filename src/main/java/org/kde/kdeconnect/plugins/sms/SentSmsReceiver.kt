/*
 * SPDX-FileCopyrightText: 2026 alltechdev <github@alltech.dev>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import org.kde.kdeconnect.KdeConnect

/**
 * Told by Android when an SMS sent from the computer has gone out.
 *
 * The computer shows a message it sent only once the phone echoes it back from the SMS database. KDE Connect is
 * not the default SMS app, so it may not write to that database: storing the sent message is up to Android, and
 * some phones don't do it (issue #3: the message reaches the recipient but never shows on the computer, nor in
 * the phone's own SMS app). So once the message has gone out, look for it; if the phone didn't store it, send
 * the computer the sent message ourselves.
 */
class SentSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // One callback per message part; only the first one that reports success acts
        if (resultCode != Activity.RESULT_OK) return
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        synchronized(handled) { if (!handled.add(token)) return; if (handled.size > 50) handled.remove(handled.first()) }
        val deviceId = intent.getStringExtra(EXTRA_DEVICE) ?: return
        val body = intent.getStringExtra(EXTRA_BODY) ?: return
        val addresses = intent.getStringArrayExtra(EXTRA_ADDRESSES) ?: return
        val sentAt = intent.getLongExtra(EXTRA_SENT_AT, System.currentTimeMillis())
        val messageUri = intent.getStringExtra("message_uri").orEmpty() // set by the SMS library when it could store it
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                check(app, deviceId, body, addresses, sentAt, messageUri)
            } catch (e: Exception) {
                Log.e(TAG, "Checking a sent SMS", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun check(context: Context, deviceId: String, body: String, addresses: Array<String>, sentAt: Long, messageUri: String) {
        if (messageUri.isNotEmpty() && messageUri != "content://sms/0") {
            // The library stored it (as "outbox"); mark it sent, as the library's own receiver would have
            try {
                val values = ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT) }
                if (context.contentResolver.update(Uri.parse(messageUri), values, null, null) > 0) return
            } catch (e: Exception) {
                Log.w(TAG, "Could not mark $messageUri sent", e)
            }
        }
        // Android stores it before it reports the message sent; allow a few seconds for slower phones
        val deadline = System.currentTimeMillis() + STORE_WAIT_MS
        while (true) {
            if (isStored(context, body, sentAt)) return
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(500)
        }
        Log.i(TAG, "Sent SMS not in the SMS database: echoing it to the computer ourselves")
        val plugin = KdeConnect.getInstance().getDevice(deviceId)?.getPlugin<SMSPlugin>() ?: return
        plugin.echoUnstoredSentMessage(body, addresses.toList(), sentAt)
    }

    private fun isStored(context: Context, body: String, sentAt: Long): Boolean = try {
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.BODY} = ? AND ${Telephony.Sms.DATE} >= ?",
            arrayOf(body, (sentAt - CLOCK_SLACK_MS).toString()), null
        )?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        // Can't read the database: assume it's there rather than show the message twice
        Log.w(TAG, "Could not query the SMS database", e)
        true
    }

    companion object {
        private const val TAG = "SentSmsReceiver"
        const val EXTRA_TOKEN = "kdeconnect.sms.sent.token"
        const val EXTRA_DEVICE = "kdeconnect.sms.sent.device"
        const val EXTRA_BODY = "kdeconnect.sms.sent.body"
        const val EXTRA_ADDRESSES = "kdeconnect.sms.sent.addresses"
        const val EXTRA_SENT_AT = "kdeconnect.sms.sent.at"
        private const val STORE_WAIT_MS = 5_000L
        private const val CLOCK_SLACK_MS = 120_000L
        private val handled = LinkedHashSet<String>()

        /** The intent the SMS library sends (as a broadcast) when the message has gone out */
        fun intent(context: Context, deviceId: String, body: String, addresses: List<String>): Intent {
            val now = System.currentTimeMillis()
            return Intent(context, SentSmsReceiver::class.java)
                .putExtra(EXTRA_TOKEN, "$deviceId:$now:${body.hashCode()}")
                .putExtra(EXTRA_DEVICE, deviceId)
                .putExtra(EXTRA_BODY, body)
                .putExtra(EXTRA_ADDRESSES, addresses.toTypedArray())
                .putExtra(EXTRA_SENT_AT, now)
        }
    }
}
