package com.ultra.assistant.control

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ultra.assistant.memory.PrivacyGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NotificationCategory {
    MESSAGE,
    ALERT,
    REMINDER,
    PROMO,
    SYSTEM
}

data class CapturedNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val category: NotificationCategory,
    val sensitiveRedacted: Boolean,
    val postedAtMillis: Long
)

object NotificationRepository {
    private val _notifications = MutableStateFlow<List<CapturedNotification>>(emptyList())
    val notificationsFlow: StateFlow<List<CapturedNotification>> = _notifications.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnectedFlow: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    fun setConnected(connected: Boolean) {
        _listenerConnected.value = connected
    }

    fun recordNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (rawTitle.isBlank() && rawText.isBlank()) return

        val hasSensitive = PrivacyGuard.containsSensitiveData("$rawTitle $rawText")
        val safeTitle = if (hasSensitive) PrivacyGuard.redact(rawTitle) else rawTitle
        val safeText = if (hasSensitive) PrivacyGuard.redact(rawText) else rawText
        val category = classify(sbn.packageName, rawTitle, rawText, sbn.notification.category)

        val item = CapturedNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            title = safeTitle.take(120),
            text = safeText.take(300),
            category = category,
            sensitiveRedacted = hasSensitive,
            postedAtMillis = sbn.postTime
        )
        _notifications.value = (listOf(item) + _notifications.value.filterNot { it.key == item.key }).take(20)
    }

    fun removeNotification(key: String) {
        _notifications.value = _notifications.value.filterNot { it.key == key }
    }

    fun clearAll() {
        _notifications.value = emptyList()
    }

    private fun classify(
        pkg: String,
        title: String,
        text: String,
        androidCategory: String?
    ): NotificationCategory {
        val combined = "$pkg $title $text".lowercase()
        return when {
            androidCategory == Notification.CATEGORY_MESSAGE ||
                combined.contains("whatsapp") ||
                combined.contains("sms") ||
                combined.contains("messaging") ||
                combined.contains("telegram") -> NotificationCategory.MESSAGE
            androidCategory == Notification.CATEGORY_REMINDER ||
                androidCategory == Notification.CATEGORY_ALARM ||
                combined.contains("reminder") ||
                combined.contains("alarm") -> NotificationCategory.REMINDER
            androidCategory == Notification.CATEGORY_ERROR ||
                combined.contains("security") ||
                combined.contains("alert") ||
                combined.contains("warning") -> NotificationCategory.ALERT
            androidCategory == Notification.CATEGORY_PROMO ||
                combined.contains("offer") ||
                combined.contains("discount") ||
                combined.contains("sale") -> NotificationCategory.PROMO
            else -> NotificationCategory.SYSTEM
        }
    }
}

class UltraNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationRepository.setConnected(true)
        runCatching {
            activeNotifications?.forEach { NotificationRepository.recordNotification(it) }
        }
    }

    override fun onListenerDisconnected() {
        NotificationRepository.setConnected(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        NotificationRepository.recordNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        NotificationRepository.removeNotification(sbn.key)
    }
}
