package com.ultra.assistant.control

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.ultra.assistant.admin.UltraDeviceAdminReceiver

data class DeviceInfoReport(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val isDeviceAdminActive: Boolean,
    val isDeviceOwner: Boolean
)

class DeviceManagementController(context: Context) {
    private val appContext = context.applicationContext
    private val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(appContext, UltraDeviceAdminReceiver::class.java)

    fun isAdminActive(): Boolean = dpm.isAdminActive(adminComponent)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(appContext.packageName)

    fun getDeviceInfo(): DeviceInfoReport = DeviceInfoReport(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        androidVersion = Build.VERSION.RELEASE ?: "Unknown",
        sdkInt = Build.VERSION.SDK_INT,
        isDeviceAdminActive = isAdminActive(),
        isDeviceOwner = isDeviceOwner()
    )

    fun lockScreenIfAuthorized(): Result<String> {
        if (!isAdminActive()) {
            return Result.failure(
                IllegalStateException("Device Administrator is not enabled. Enable it voluntarily in Settings > Security to allow screen locking.")
            )
        }
        return runCatching {
            dpm.lockNow()
            "Device screen locked via authorized DevicePolicyManager."
        }
    }

    fun explainAdminVsOwner(): String {
        return "Device Admin (user-activated in Settings) grants only declared policies like explicit screen lock (force-lock). It does NOT grant unrestricted system control. Device Owner requires enterprise provisioning on an unmanaged device."
    }
}
