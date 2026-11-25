package com.ultra.assistant.control

import android.content.Context
import com.ultra.assistant.permissions.PermissionManager

class ScreenController(private val context: Context) {

    fun isReady(): Boolean =
        PermissionManager.isAccessibilityEnabled(context) && UltraAccessibilityService.getInstance() != null

    fun inspectScreen(): Result<AccessibleScreenSnapshot> {
        val service = UltraAccessibilityService.getInstance()
            ?: return Result.failure(
                IllegalStateException("Accessibility Service is not enabled. Enable ULTRA AI in Android Accessibility Settings to inspect screen content.")
            )
        val snapshot = service.captureCurrentSnapshot()
            ?: return Result.failure(IllegalStateException("Unable to read the current active window."))
        return Result.success(snapshot)
    }

    fun describeScreenConcise(maxNodes: Int = 34): Result<String> {
        return inspectScreen().map { snap ->
            if (snap.elements.isEmpty()) {
                "Active package: ${snap.packageName} (${snap.windowTitle}). No readable controls exposed."
            } else {
                val preview = snap.elements.take(maxNodes).joinToString("; ") { elem ->
                    val label = elem.text.ifBlank { elem.contentDescription }.ifBlank { elem.viewId.substringAfterLast('/') }
                    val flags = buildList {
                        if (elem.clickable) add("clickable")
                        if (elem.editable) add("editable")
                        if (elem.scrollable) add("scrollable")
                    }.joinToString(",")
                    "[$label${if (flags.isNotBlank()) " ($flags)" else ""}]"
                }
                val extraHints = buildList {
                    if (snap.isEmailScreen) add("EmailScreen")
                    if (snap.hasSearchField) add("SearchFieldPresent")
                    if (snap.hasSearchButton) add("SearchButtonPresent")
                }.joinToString(", ")
                val hintPrefix = if (extraHints.isNotBlank()) " | Flags: $extraHints" else ""
                "Package: ${snap.packageName} | Window: ${snap.windowTitle}$hintPrefix | Controls (${snap.elements.size}): $preview"
            }
        }
    }

    fun readScreenAloud(): AccessibilityActionResult {
        val service = UltraAccessibilityService.getInstance()
            ?: return AccessibilityActionResult(false, "Accessibility Service is disabled. Please enable it in Settings.")
        return service.readScreenSummaryAloud()
    }

    fun clickNextInApp(): AccessibilityActionResult {
        val service = UltraAccessibilityService.getInstance()
            ?: return AccessibilityActionResult(false, "Accessibility Service is disabled. Please enable it in Settings.")
        return service.clickNextInApp()
    }

    fun isCurrentlyInEmailApp(): Boolean {
        return UltraAccessibilityService.latestSnapshotFlow.value?.isEmailScreen == true ||
            UltraAccessibilityService.activePackageFlow.value.lowercase().let {
                it.contains("gm") || it.contains("mail") || it.contains("outlook")
            }
    }

    fun clickElement(text: String, viewId: String = ""): AccessibilityActionResult {
        val service = UltraAccessibilityService.getInstance()
            ?: return AccessibilityActionResult(false, "Accessibility Service is disabled. Please enable it in Settings.")
        return service.clickByTextOrId(text, viewId)
    }

    fun typeText(target: String, text: String, viewId: String = "", submit: Boolean = false): AccessibilityActionResult {
        val service = UltraAccessibilityService.getInstance()
            ?: return AccessibilityActionResult(false, "Accessibility Service is disabled. Please enable it in Settings.")
        return service.setTextOnField(target, text, viewId, submit)
    }

    fun scroll(direction: String): AccessibilityActionResult {
        val service = UltraAccessibilityService.getInstance()
            ?: return AccessibilityActionResult(false, "Accessibility Service is disabled. Please enable it in Settings.")
        return service.scrollScreen(direction)
    }

    fun pressBack(): AccessibilityActionResult {
        val service = UltraAccessibilityService.getInstance()
            ?: return AccessibilityActionResult(false, "Accessibility Service is disabled. Please enable it in Settings.")
        return service.navigateBack()
    }

    fun navigateSystem(target: String): AccessibilityActionResult {
        val service = UltraAccessibilityService.getInstance()
            ?: return AccessibilityActionResult(false, "Accessibility Service is disabled. Please enable it in Settings.")
        return service.performSystemNavigation(target)
    }
}
