package com.ultra.assistant.control

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

data class InstalledAppInfo(
    val label: String,
    val packageName: String
)

class AppController(context: Context) {
    private val appContext = context.applicationContext
    private val pm = appContext.packageManager

    fun listInstalledApps(): List<InstalledAppInfo> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(query, PackageManager.MATCH_ALL)
                .distinctBy { it.activityInfo.packageName }
                .map { info ->
                    InstalledAppInfo(
                        label = info.loadLabel(pm).toString(),
                        packageName = info.activityInfo.packageName
                    )
                }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    fun findApp(query: String): InstalledAppInfo? {
        val clean = query.trim().lowercase()
        if (clean.isBlank()) return null
        val apps = listInstalledApps()
        val aliases = mapOf(
            "yt" to "youtube",
            "ইউটিউব" to "youtube",
            "यूट्यूब" to "youtube",
            "yt music" to "youtube music",
            "fb" to "facebook",
            "ফেসবুক" to "facebook",
            "फेसबुक" to "facebook",
            "হোয়াটসঅ্যাপ" to "whatsapp",
            "व्हाट्सएप" to "whatsapp",
            "ইনস্টাগ্রাম" to "instagram",
            "ইন্সটাগ্রাম" to "instagram",
            "জিমেইল" to "gmail",
            "ম্যাপস" to "maps",
            "প্লে স্টোর" to "play store",
            "ফোন" to "phone",
            "মেসেজ" to "messages",
            "ঘড়ি" to "clock",
            "ক্রোম" to "chrome",
            "ক্যামেরা" to "camera",
            "সেটিংস" to "settings",
            "सेटिंग्स" to "settings",
            "ক্যালকুলেটর" to "calculator"
        )
        val normalized = aliases[clean] ?: clean
        return apps.firstOrNull { it.label.lowercase() == normalized || it.packageName.lowercase() == normalized }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(normalized) }
            ?: apps.firstOrNull { it.label.lowercase().contains(normalized) || it.packageName.lowercase().contains(normalized) }
    }

    fun isAppAvailable(query: String): Boolean = findApp(query) != null

    fun launchApp(query: String): Result<InstalledAppInfo> {
        val target = findApp(query)
            ?: return Result.failure(IllegalStateException("App '$query' is not installed on this device."))
        val launchIntent = pm.getLaunchIntentForPackage(target.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return Result.failure(IllegalStateException("No launchable activity found for ${target.label}."))
        return runCatching {
            appContext.startActivity(launchIntent)
            target
        }
    }

    fun openAppInfo(packageName: String): Boolean {
        return runCatching {
            appContext.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    fun openWifiPanel(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panel = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (panel.resolveActivity(pm) != null) {
                return runCatching { appContext.startActivity(panel); true }.getOrDefault(false)
            }
        }
        return openSettingsAction(Settings.ACTION_WIFI_SETTINGS)
    }

    fun openVolumePanel(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panel = Intent(Settings.Panel.ACTION_VOLUME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (panel.resolveActivity(pm) != null) {
                return runCatching { appContext.startActivity(panel); true }.getOrDefault(false)
            }
        }
        return openSettingsAction(Settings.ACTION_SOUND_SETTINGS)
    }

    fun openDeviceSettings(target: String): Result<String> {
        val key = target.trim().lowercase()
        return when {
            key in setOf("wifi", "wi-fi", "internet", "connectivity") -> {
                if (openWifiPanel()) Result.success("Opened Wi-Fi & internet connectivity panel.")
                else Result.failure(IllegalStateException("Unable to open Wi-Fi settings."))
            }
            key in setOf("bluetooth", "bt") -> {
                if (openSettingsAction(Settings.ACTION_BLUETOOTH_SETTINGS)) Result.success("Opened Bluetooth settings.")
                else Result.failure(IllegalStateException("Unable to open Bluetooth settings."))
            }
            key in setOf("network", "wireless", "mobile_data", "data") -> {
                if (openSettingsAction(Settings.ACTION_WIRELESS_SETTINGS)) Result.success("Opened network & wireless settings.")
                else Result.failure(IllegalStateException("Unable to open network settings."))
            }
            key in setOf("sound", "volume", "audio") -> {
                if (openVolumePanel()) Result.success("Opened sound & volume panel.")
                else Result.failure(IllegalStateException("Unable to open sound settings."))
            }
            key in setOf("brightness", "display", "screen") -> {
                if (openSettingsAction(Settings.ACTION_DISPLAY_SETTINGS)) Result.success("Opened display & brightness settings.")
                else Result.failure(IllegalStateException("Unable to open display settings."))
            }
            key in setOf("notifications", "notification") -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Settings.ACTION_APP_NOTIFICATION_SETTINGS
                } else {
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                }
                val intent = Intent(action).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    appContext.startActivity(intent)
                    "Opened notification settings."
                }.recoverCatching {
                    openSettingsAction(Settings.ACTION_SETTINGS)
                    "Opened system settings."
                }
            }
            key in setOf("battery", "power") -> {
                if (openSettingsAction(Settings.ACTION_BATTERY_SAVER_SETTINGS)) Result.success("Opened battery settings.")
                else Result.failure(IllegalStateException("Unable to open battery settings."))
            }
            else -> {
                if (openSettingsAction(Settings.ACTION_SETTINGS)) Result.success("Opened Android system settings.")
                else Result.failure(IllegalStateException("Unable to open system settings."))
            }
        }
    }

    fun openSettingsAction(action: String): Boolean {
        return runCatching {
            appContext.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}
