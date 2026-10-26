/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2018 Simon Redman <simon@ergotech.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.helpers

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.PhoneLookup
import android.util.Base64
import android.util.Base64OutputStream
import android.util.Log
import java.io.ByteArrayOutputStream
import androidx.core.net.toUri

/**
 * Helper object for contacts-related operations.
 */
object ContactsHelper {
    /**
     * The tag used for logging.
     */
    private const val TAG: String = "ContactsHelper"

    data class PhoneNumberLookupResult(
        val name: String? = null,
        val photoId: String? = null
    )

    /**
     * Lookup the name and photoID of a contact given a phone number.
     *
     * Only the first match is returned.
     *
     * @param context the context running the request.
     * @param number the phone number to lookup.
     *
     * @return a map containing the `name` and `photoID` of the contact, or an empty map if the contact could not be found.
     */
    fun phoneNumberLookup(
        context: Context,
        number: String
    ): PhoneNumberLookupResult {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val columns = arrayOf(
            PhoneLookup.DISPLAY_NAME,
            PhoneLookup.PHOTO_URI,
        )
        try {
            context.contentResolver.query(uri, columns, null, null, null).use { cursor ->
                // Take the first match only
                if (cursor != null && cursor.moveToFirst()) {
                    val name = cursor.getColumnIndex(PhoneLookup.DISPLAY_NAME).takeIf { it != -1 }?.let {
                        cursor.getString(it)
                    }
                    val photoId = cursor.getColumnIndex(PhoneLookup.PHOTO_URI).takeIf { it != -1 }?.let {
                        cursor.getString(it)
                    }
                    return PhoneNumberLookupResult(name, photoId)
                }
            }
        } catch (ignore: Exception) {
        }
        return PhoneNumberLookupResult()
    }

    /**
     * Get the base64 encoded photo for a contact.
     *
     * @param context the context running the request.
     * @param photoId the photoId of the contact.
     *
     * @return the base64 encoded photo, or null if the photo could not be encoded.
     */
    fun photoId64Encoded(
        context: Context,
        photoId: String
    ): String? {
        val photoUri = photoId.toUri()
        val encodedPhoto = ByteArrayOutputStream()
        return try {
            context.contentResolver.openInputStream(photoUri)!!.use { input ->
                Base64OutputStream(encodedPhoto, Base64.DEFAULT).use { output ->
                    input.copyTo(output, 1024)
                }
            }
            encodedPhoto.toString()
        } catch (ex: Exception) {
            Log.e(TAG, "Error encoding photo", ex)
            null
        }
    }

    /**
     * Get VCards using serial database lookups. This is tragically slow, so call only when needed.
     *
     * There is a faster API specified using ContactsContract.Contacts.CONTENT_MULTI_VCARD_URI,
     * but there does not seem to be a way to figure out which ID resulted in which VCard using that API.
     *
     * @param context [android.content.Context] running the request.
     * @param ids collection of uIDs to look up.
     * @return map of uIDs to the corresponding VCard.
     */
    private fun getVCardsSlow(context: Context, ids: Collection<uID>): Map<uID, VCardBuilder> {
        return ids.mapNotNull { id ->
            val lookupKey = id.toString()
            val vcardURI =
                Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)

            try {
                context.contentResolver.openInputStream(vcardURI).use { input ->
                    if (input == null) {
                        Log.w("Contacts", "ContentResolver did not give us a stream for the VCard for uID $id")
                        return@mapNotNull null
                    }
                    id to VCardBuilder(input.bufferedReader().readText())
                }
            } catch (e: Exception) {
                Log.e("Contacts", "Exception while fetching vcards", e)
                null
            }
        }.toMap()
    }

    /**
     * Get the VCard for every specified raw contact ID.
     *
     * @param context [android.content.Context] running the request.
     * @param ids collection of raw contact IDs to look up.
     * @return map of raw contact IDs to the corresponding VCard.
     */
    fun getVCardsForContactIDs(context: Context, ids: Collection<uID>): Map<uID, VCardBuilder> {
        val fast = try {
            getVCardsFromData(context, ids)
        } catch (e: Exception) {
            Log.e(TAG, "Building vCards from the contact data failed, asking the provider for each one", e)
            emptyMap()
        }
        val missing = ids.filter { it !in fast }
        return if (missing.isEmpty()) fast else fast + getVCardsSlow(context, missing)
    }

    /**
     * Builds the vCards from one query over the contact data table, instead of asking the provider to compose
     * one vCard per contact (a round trip each, ~0.3 s per contact on a slow phone). Carries what the desktop uses:
     * name, phone numbers, email addresses, organisation, nickname and the contact photo thumbnail.
     */
    private fun getVCardsFromData(context: Context, ids: Collection<uID>): Map<uID, VCardBuilder> {
        if (ids.isEmpty()) return emptyMap()
        val wanted = ids.associateBy { it.toString() }
        val mimeTypes = listOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
        )
        val projection = arrayOf(
            ContactsContract.Data.LOOKUP_KEY, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4, ContactsContract.Data.DATA5, ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA15,
        )
        val keys = wanted.keys.toList()
        val selection = ContactsContract.Data.MIMETYPE + " IN (" + mimeTypes.joinToString(",") { "?" } + ") AND " +
            ContactsContract.Data.LOOKUP_KEY + " IN (" + keys.joinToString(",") { "?" } + ")"
        val cards = LinkedHashMap<String, VCardData>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, projection, selection, (mimeTypes + keys).toTypedArray(), null
        )?.use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) ?: continue
                val card = cards.getOrPut(key) { VCardData() }
                if (card.displayName == null) card.displayName = c.getString(2)
                when (c.getString(1)) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE ->
                        // N: family;given;middle;prefix;suffix
                        // (DATA3 family, DATA2 given, DATA5 middle, DATA4 prefix, DATA6 suffix)
                        card.name = listOf(5, 4, 7, 6, 8).map { col -> c.getString(col).orEmpty() }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> c.getString(3)?.let { number ->
                        val type = when (c.getInt(4)) {
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "CELL"
                            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "HOME"
                            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "WORK"
                            else -> "VOICE"
                        }
                        card.lines.add("TEL;TYPE=$type:" + escape(number))
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> c.getString(3)?.let { email ->
                        card.lines.add("EMAIL;TYPE=INTERNET:" + escape(email))
                    }
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        c.getString(3)?.let { card.lines.add("ORG:" + escape(it)) }
                        c.getString(6)?.let { card.lines.add("TITLE:" + escape(it)) }
                    }
                    ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> c.getString(3)?.let {
                        card.lines.add("NICKNAME:" + escape(it))
                    }
                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE -> c.getBlob(9)?.let { photo ->
                        card.lines.add("PHOTO;ENCODING=b;TYPE=JPEG:" + Base64.encodeToString(photo, Base64.NO_WRAP))
                    }
                }
            }
        }
        val toReturn = HashMap<uID, VCardBuilder>()
        for ((key, card) in cards) {
            val id = wanted[key] ?: continue
            val fn = card.displayName ?: card.name?.let { listOf(it[1], it[2], it[0]).filter(String::isNotEmpty).joinToString(" ") } ?: ""
            val n = card.name ?: listOf("", fn, "", "", "")
            val vcard = StringBuilder()
                .append("BEGIN:VCARD\r\n")
                .append("VERSION:3.0\r\n")
                .append("N:").append(n.joinToString(";") { escape(it) }).append("\r\n")
                .append("FN:").append(escape(fn)).append("\r\n")
            card.lines.distinct().forEach { vcard.append(it).append("\r\n") }
            vcard.append(VCardBuilder.VCARD_END)
            toReturn[id] = VCardBuilder(vcard.toString())
        }
        return toReturn
    }

    private class VCardData {
        var displayName: String? = null
        var name: List<String>? = null
        val lines = mutableListOf<String>()
    }

    /** vCard 3.0 text value escaping (RFC 2426) */
    private fun escape(value: String): String = value
        .replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\r\n", "\\n").replace("\n", "\\n")

    /**
     * Get the last-modified timestamp for every contact in the database.
     *
     * @param context [android.content.Context] running the request.
     * @return map of contact uID to last-modified timestamp.
     */
    fun getAllContactTimestamps(context: Context): Map<uID, Long> {
        val projection =
            arrayOf(uID.COLUMN, ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)

        val databaseValues = accessContactsDatabase(context, projection, null, null, null)

        val timestamps: Map<uID, Long> = databaseValues.keys.associateWith { contactId ->
            val data = databaseValues[contactId]!!
            data[ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP]!!.toLong()
        }

        return timestamps
    }

    /**
     * Get the last-modified timestamp for the specified contact.
     *
     * @param context [android.content.Context] running the request.
     * @param contactID contact uID to read.
     * @throws ContactNotFoundException if the given ID for some reason does not match a contact.
     * @return last-modified timestamp of the contact.
     */
    @Throws(ContactNotFoundException::class)
    fun getContactTimestamp(context: Context, contactID: uID): Long {
        val projection =
            arrayOf(uID.COLUMN, ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)
        val selection = uID.COLUMN + " = ?"
        val selectionArgs = arrayOf(contactID.toString())

        val databaseValue =
            accessContactsDatabase(context, projection, selection, selectionArgs, null)

        if (databaseValue.isEmpty()) {
            throw ContactNotFoundException("Querying for contact with id $contactID returned no results.")
        }

        if (databaseValue.size != 1) {
            Log.w(
                TAG,
                "Received an improper number of return values from the database in getContactTimestamp: ${databaseValue.size}"
            )
        }

        val timestamp =
            databaseValue[contactID]!![ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP]!!.toLong()

        return timestamp
    }

    /**
     * Return a mapping of contact IDs to a map of the requested data from the Contacts database.
     *
     * @param context [android.content.Context] running the request.
     * @param projection list of column names to extract, defined in [ContactsContract.Contacts], must contain [uID.COLUMN].
     * @param selection parameterizable filter to use with the [ContentResolver] query.
     * @param selectionArgs parameters for selection.
     * @param sortOrder sort order to request from the [ContentResolver] query.
     * @return map of contact uIDs to desired values, which are a mapping of column names to the data contained there.
     */
    private fun accessContactsDatabase(
        context: Context,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Map<uID, Map<String, String>> {
        val contactsUri = ContactsContract.Contacts.CONTENT_URI

        val toReturn = HashMap<uID, Map<String, String>>()

        context.contentResolver.query(
            contactsUri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        ).use { contactsCursor ->
            if (contactsCursor != null && contactsCursor.moveToFirst()) {
                do {
                    val requestedData: MutableMap<String, String> = HashMap()

                    val uIDIndex = contactsCursor.getColumnIndexOrThrow(uID.COLUMN)
                    val uID = uID(contactsCursor.getString(uIDIndex)!!)

                    // For each column, collect the data from that column
                    for (column in projection) {
                        val index = contactsCursor.getColumnIndex(column)
                        if (index == -1) {
                            // This contact didn't have the requested column? Something is very wrong.
                            // If you are experiencing this, please open a bug report indicating how you got here
                            Log.e(TAG, "Got a contact which does not have a requested column")
                            continue
                        }
                        // Since we might be getting various kinds of data, Object is the best we can do
                        val data = contactsCursor.getString(index)

                        requestedData[column] = data
                    }

                    toReturn[uID] = requestedData
                } while (contactsCursor.moveToNext())
            }
        }
        return toReturn
    }

    /**
     * This is a cheap ripoff of com.android.vcard.VCardBuilder.
     *
     * Maybe in the future that library will be made public and we can switch to using that!
     *
     * The main similarity is the usage of .toString() to produce the finalized VCard and the
     * usage of .appendLine(String, String) to add stuff to the vcard.
     *
     * @param vcard the vcard to build upon.
     */
    class VCardBuilder internal constructor(vcard: String) {
        private val vcardBody: StringBuilder = StringBuilder(
            // Remove the end tag. We will add it back on in .toString()
            // Throws if VCARD_END is missing, so a malformed vcard fails closed rather than being silently accepted.
            vcard.substring(0, vcard.indexOf(VCARD_END))
        )

        /**
         * Appends one line with a given property name and value.
         *
         * Please note that this method does not check the validity of the property name and value.
         * So, you need to make sure that the property name and value are valid.
         *
         * @param propertyName the name of the property to append.
         * @param rawValue the value of the property to append.
         */
        fun appendLine(propertyName: String, rawValue: String) {
            vcardBody.append(propertyName)
                .append(VCARD_DATA_SEPARATOR)
                .append(rawValue)
                .append("\n")
        }

        /**
         * Converts the VCard to standard VCard format.
         *
         * Please note that this method does not check the validity of the VCard.
         * So, you need to make sure that the VCard is valid.
         *
         * @return the VCard in standard VCard format.
         */
        override fun toString(): String = vcardBody.toString() + VCARD_END

        companion object {
            const val VCARD_END: String = "END:VCARD" // Written to terminate the vcard
            const val VCARD_DATA_SEPARATOR: String = ":"
        }
    }

    /**
     * Essentially a typedef of the type used for a unique identifier.
     *
     * @param contactLookupKey the lookup key of the contact.
     * We use the LOOKUP_KEY column of the Contacts table as a unique ID, since that's what it's.
     */
    @Suppress("ClassName")
    class uID(val contactLookupKey: String) {
        override fun toString(): String = this.contactLookupKey

        override fun hashCode(): Int = contactLookupKey.hashCode()

        override fun equals(other: Any?): Boolean {
            if (other is uID) {
                return contactLookupKey == other.contactLookupKey
            }
            return contactLookupKey == other
        }

        companion object {
            /**
             * Which Contacts column this uID is pulled from
             */
            const val COLUMN: String = ContactsContract.Contacts.LOOKUP_KEY
        }
    }

    /**
     * Exception to indicate that a specified contact was not found.
     */
    class ContactNotFoundException : Exception {
        constructor(contactId: uID) : super("Unable to find contact with ID $contactId")

        constructor(message: String?) : super(message)
    }
}
