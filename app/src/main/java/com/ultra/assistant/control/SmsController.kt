package com.ultra.assistant.control

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager

data class SmsCapabilityReport(
    val hasTelephony: Boolean,
    val canOpenDraftApp: Boolean,
    val isDefaultSmsHandler: Boolean
)

data class SmsExecutionResult(
    val success: Boolean,
    val mode: String, // "DRAFT_OPENED", "SENT_DIRECT", "FAILED"
    val message: String
)

class SmsController(context: Context) {
    private val appContext = context.applicationContext

    fun capabilities(): SmsCapabilityReport {
        val pm = appContext.packageManager
        val hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val draftIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
        val canOpenDraft = draftIntent.resolveActivity(pm) != null
        val isDefault = Telephony.Sms.getDefaultSmsPackage(appContext) == appContext.packageName
        return SmsCapabilityReport(
            hasTelephony = hasTelephony,
            canOpenDraftApp = canOpenDraft,
            isDefaultSmsHandler = isDefault
        )
    }

    fun prepareDraftIntent(recipient: String, body: String): SmsExecutionResult {
        val cleanRecipient = recipient.trim()
        val cleanBody = body.trim()
        if (cleanBody.isBlank()) {
            return SmsExecutionResult(false, "FAILED", "SMS body cannot be empty.")
        }
        val uri = if (cleanRecipient.isNotBlank()) {
            Uri.parse("smsto:${Uri.encode(cleanRecipient)}")
        } else {
            Uri.parse("smsto:")
        }
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", cleanBody)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            appContext.startActivity(intent)
            SmsExecutionResult(
                success = true,
                mode = "DRAFT_OPENED",
                message = "Opened messaging app with prefilled SMS draft${if (cleanRecipient.isNotBlank()) " for $cleanRecipient" else ""}."
            )
        }.getOrElse { err ->
            SmsExecutionResult(
                success = false,
                mode = "FAILED",
                message = err.localizedMessage ?: "No SMS messaging app found on this device."
            )
        }
    }

    @Suppress("DEPRECATION")
    fun sendDirectIfAuthorized(recipient: String, body: String): SmsExecutionResult {
        val caps = capabilities()
        val hasSendSms = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!caps.isDefaultSmsHandler || !hasSendSms) {
            return SmsExecutionResult(
                success = false,
                mode = "FAILED",
                message = "Direct SMS sending requires ULTRA AI to be the authorized default SMS handler with SEND_SMS permission."
            )
        }
        if (recipient.isBlank() || body.isBlank()) {
            return SmsExecutionResult(false, "FAILED", "Recipient and message body are required.")
        }
        return runCatching {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appContext.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            } ?: return SmsExecutionResult(false, "FAILED", "SmsManager unavailable on this device.")

            val parts = smsManager.divideMessage(body.trim())
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(recipient.trim(), null, parts, null, null)
            } else {
                smsManager.sendTextMessage(recipient.trim(), null, body.trim(), null, null)
            }
            SmsExecutionResult(true, "SENT_DIRECT", "SMS dispatched to $recipient.")
        }.getOrElse { err ->
            SmsExecutionResult(false, "FAILED", err.localizedMessage ?: "Failed to send SMS.")
        }
    }
}
