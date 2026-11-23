package com.ultra.assistant.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.ultra.assistant.permissions.PermissionManager

data class ContactMatch(
    val displayName: String,
    val phoneNumber: String
)

class PhoneContactsController(context: Context) {
    private val appContext = context.applicationContext

    fun findContactNumber(query: String): Result<ContactMatch> {
        val clean = query.trim()
        if (clean.isBlank()) {
            return Result.failure(IllegalArgumentException("Contact name or phone number is required."))
        }
        val digitsOnly = clean.filter { it.isDigit() || it == '+' }
        if (digitsOnly.length >= 3 && clean.none { it.isLetter() }) {
            return Result.success(ContactMatch(displayName = clean, phoneNumber = digitsOnly))
        }
        if (!PermissionManager.hasContacts(appContext)) {
            return Result.failure(IllegalStateException("Contacts permission is required to look up '$clean'."))
        }
        return runCatching {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$clean%")
            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0).orEmpty().ifBlank { clean }
                    val number = cursor.getString(1).orEmpty().trim()
                    if (number.isNotBlank()) {
                        return@runCatching ContactMatch(name, number)
                    }
                }
            }
            throw IllegalStateException("No phone number found in Contacts for '$clean'.")
        }
    }

    fun openDialer(number: String = ""): Result<String> {
        val clean = number.trim()
        val uri = if (clean.isNotBlank()) Uri.parse("tel:${Uri.encode(clean)}") else Uri.parse("tel:")
        val intent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            appContext.startActivity(intent)
            if (clean.isNotBlank()) "Opened phone dialer with $clean." else "Opened phone dialer."
        }
    }

    fun placeCallIfPermitted(number: String): Result<String> {
        val clean = number.trim()
        if (clean.isBlank()) return openDialer("")
        if (!PermissionManager.hasCallPhone(appContext)) {
            return openDialer(clean).map {
                "Opened phone dialer with $clean (grant Phone Call permission for direct calling)."
            }
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(clean)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            appContext.startActivity(intent)
            "Placing call to $clean."
        }
    }
}
