package com.ultra.assistant.permissions

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ultra.assistant.admin.UltraDeviceAdminReceiver
import com.ultra.assistant.control.UltraAccessibilityService

data class PermissionAuditSnapshot(
    val microphoneGranted: Boolean,
    val notificationsGranted: Boolean,
    val overlayGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val notificationListenerEnabled: Boolean,
    val deviceAdminActive: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val isDefaultSmsHandler: Boolean
)

object PermissionManager {
    fun missing(context: Context): List<String> {
        val requested = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            requested += Manifest.permission.POST_NOTIFICATIONS
        }
        return requested.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasMicrophone(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasNotifications(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun hasOverlay(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    fun isAccessibilityEnabled(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedComponent = ComponentName(context, UltraAccessibilityService::class.java)
        enabledServices.any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo
            serviceInfo?.packageName == expectedComponent.packageName &&
                serviceInfo.name == expectedComponent.className
        } || UltraAccessibilityService.isServiceConnected()
    }.getOrDefault(UltraAccessibilityService.isServiceConnected())

    fun isNotificationListenerEnabled(context: Context): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    fun isDeviceAdminActive(context: Context): Boolean = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        dpm.isAdminActive(ComponentName(context, UltraDeviceAdminReceiver::class.java))
    }.getOrDefault(false)

    fun isBatteryOptimizationIgnored(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    fun isDefaultSmsApp(context: Context): Boolean = runCatching {
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }.getOrDefault(false)

    fun snapshot(context: Context): PermissionAuditSnapshot = PermissionAuditSnapshot(
        microphoneGranted = hasMicrophone(context),
        notificationsGranted = hasNotifications(context),
        overlayGranted = hasOverlay(context),
        accessibilityEnabled = isAccessibilityEnabled(context),
        notificationListenerEnabled = isNotificationListenerEnabled(context),
        deviceAdminActive = isDeviceAdminActive(context),
        batteryOptimizationIgnored = isBatteryOptimizationIgnored(context),
        isDefaultSmsHandler = isDefaultSmsApp(context)
    )

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun notificationListenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun deviceAdminActivateIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, UltraDeviceAdminReceiver::class.java))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Optional: Enables explicit voice/button lock-screen action only. Can be revoked anytime in Security Settings."
            )
        }

    fun securitySettingsIntent(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
}
