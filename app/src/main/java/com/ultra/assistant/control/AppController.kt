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
        return apps.firstOrNull { it.label.lowercase() == clean || it.packageName.lowercase() == clean }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(clean) }
            ?: apps.firstOrNull { it.label.lowercase().contains(clean) || it.packageName.lowercase().contains(clean) }
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

    fun openSettingsAction(action: String): Boolean {
        return runCatching {
            appContext.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}
