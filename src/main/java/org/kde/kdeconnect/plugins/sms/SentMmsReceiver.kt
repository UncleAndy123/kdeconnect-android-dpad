/*
 * SPDX-FileCopyrightText: 2026 alltechdev <github@alltech.dev>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.SendReq
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Told by Android when an MMS sent from the computer has gone out.
 *
 * KDE Connect isn't the default SMS app, so saving the MMS it sends is up to Android, and some phones (e.g. Kyocera
 * on Android 7) don't: the MMS reaches the recipient but shows neither in the phone's SMS app nor on the computer
 * (which only sees what is in the phone's message database). So once it has gone out, look for it, and if Android
 * didn't save it, save it ourselves; the computer then gets it like any other message. Saving needs
 * `adb shell appops set com.android.cts.kdeconnect WRITE_SMS allow` (it covers MMS too).
 */
class SentMmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val pending = this.pending.remove(token)
        if (resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "MMS not sent (result $resultCode)")
            pending?.file?.delete()
            return
        }
        if (pending == null) {
            Log.w(TAG, "MMS sent, but its details were lost (app restarted?): can't check it was saved")
            return
        }
        val app = context.applicationContext
        val async = goAsync()
        Thread {
            try {
                check(app, pending)
            } catch (e: Exception) {
                Log.e(TAG, "Checking a sent MMS", e)
            } finally {
                pending.file.delete()
                async.finish()
            }
        }.start()
    }

    private fun check(context: Context, sent: Pending) {
        // Android saves it before it reports it sent; allow a few seconds for slower phones
        val deadline = System.currentTimeMillis() + STORE_WAIT_MS
        while (true) {
            if (isStored(context, sent.dateSeconds)) return
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(500)
        }
        Log.i(TAG, "Sent MMS not in the message database: saving it ourselves")
        try {
            val uri = PduPersister.getPduPersister(context)
                .persist(sent.req, Telephony.Mms.Sent.CONTENT_URI, true, sent.group, null)
            // Android answers a write it refused (no WRITE_SMS app-op) with an id of 0, not an error
            if ((uri?.lastPathSegment?.toLongOrNull() ?: 0L) <= 0L) refused(context)
        } catch (e: Exception) {
            Log.w(TAG, "Could not save the sent MMS", e)
            refused(context)
        }
    }

    private fun refused(context: Context) =
        Log.i(TAG, "Android refused to save the sent MMS; allow it with: adb shell appops set ${context.packageName} WRITE_SMS allow")

    private fun isStored(context: Context, dateSeconds: Long): Boolean = try {
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.MESSAGE_BOX} = ? AND ${Telephony.Mms.DATE} >= ?",
            arrayOf(Telephony.Mms.MESSAGE_BOX_SENT.toString(), (dateSeconds - CLOCK_SLACK_S).toString()), null
        )?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        // Can't read the database: assume it's there rather than save it twice
        Log.w(TAG, "Could not query the MMS database", e)
        true
    }

    private class Pending(val req: SendReq, val group: Boolean, val file: File, val dateSeconds: Long)

    companion object {
        private const val TAG = "SentMmsReceiver"
        private const val EXTRA_TOKEN = "kdeconnect.mms.sent.token"
        private const val STORE_WAIT_MS = 5_000L
        private const val CLOCK_SLACK_S = 5L
        private val pending = ConcurrentHashMap<String, Pending>()
        private val requestCodes = AtomicInteger(0)

        /** The intent Android sends when [req] has gone out (or failed); it then also deletes [file] */
        fun pendingIntent(context: Context, req: SendReq, group: Boolean, file: File): PendingIntent {
            val token = "${System.nanoTime()}:${file.name}"
            pending[token] = Pending(req, group, file, req.date)
            val intent = Intent(context, SentMmsReceiver::class.java).putExtra(EXTRA_TOKEN, token)
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE_BASE + requestCodes.incrementAndGet() % 1000, intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private const val REQUEST_CODE_BASE = 0x4D4D0000
    }
}
