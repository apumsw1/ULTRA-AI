package com.ultra.assistant.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

data class AccessibleUiElement(
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val bounds: Rect
)

data class AccessibleScreenSnapshot(
    val packageName: String,
    val windowTitle: String,
    val elements: List<AccessibleUiElement>,
    val capturedAtMillis: Long = System.currentTimeMillis()
)

data class AccessibilityActionResult(
    val success: Boolean,
    val message: String,
    val affectedElement: String? = null
)

class UltraAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotBlank()) {
            _activePackage.value = pkg
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            captureCurrentSnapshot()?.let { _latestSnapshot.value = it }
        }
    }

    override fun onInterrupt() {
        // No-op: Android manages interruption lifecycle
    }

    override fun onDestroy() {
        _connected.value = false
        instanceRef = null
        super.onDestroy()
    }

    fun captureCurrentSnapshot(): AccessibleScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        if (isProtectedPackage(pkg)) {
            return AccessibleScreenSnapshot(
                packageName = pkg,
                windowTitle = "Protected Screen",
                elements = emptyList()
            )
        }
        val elements = mutableListOf<AccessibleUiElement>()
        collectNodes(root, elements, maxNodes = 80)
        val title = elements.firstOrNull { it.text.isNotBlank() }?.text ?: pkg
        return AccessibleScreenSnapshot(
            packageName = pkg,
            windowTitle = title,
            elements = elements
        )
    }

    fun clickByTextOrId(query: String, viewId: String = ""): AccessibilityActionResult {
        val root = rootInActiveWindow ?: return AccessibilityActionResult(false, "No active window available.")
        val pkg = root.packageName?.toString().orEmpty()
        if (isProtectedPackage(pkg)) {
            return AccessibilityActionResult(false, "Action blocked on protected system/security screen ($pkg).")
        }
        val target = findMatchingNode(root, query, viewId)
            ?: return AccessibilityActionResult(false, "No UI element found matching '$query'.")

        var clickableNode: AccessibilityNodeInfo? = target
        while (clickableNode != null && !clickableNode.isClickable) {
            clickableNode = clickableNode.parent
        }
        val nodeToClick = clickableNode ?: target
        val ok = nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val label = target.text?.toString() ?: target.contentDescription?.toString() ?: target.viewIdResourceName ?: query
        return if (ok) {
            AccessibilityActionResult(true, "Clicked '$label'.", label)
        } else {
            // Fallback to coordinate tap at node center if clickable hierarchy didn't accept ACTION_CLICK
            val rect = Rect()
            target.getBoundsInScreen(rect)
            if (!rect.isEmpty && performTap(rect.exactCenterX(), rect.exactCenterY())) {
                AccessibilityActionResult(true, "Tapped '$label' via gesture.", label)
            } else {
                AccessibilityActionResult(false, "Element '$label' did not accept click action.")
            }
        }
    }

    fun setTextOnField(targetQuery: String, textToEnter: String, viewId: String = ""): AccessibilityActionResult {
        val root = rootInActiveWindow ?: return AccessibilityActionResult(false, "No active window available.")
        val pkg = root.packageName?.toString().orEmpty()
        if (isProtectedPackage(pkg)) {
            return AccessibilityActionResult(false, "Typing blocked on protected screen ($pkg).")
        }
        val target = if (targetQuery.isBlank() && viewId.isBlank()) {
            findFirstEditableNode(root)
        } else {
            findMatchingNode(root, targetQuery, viewId)?.let { matched ->
                if (matched.isEditable) matched else findFirstEditableNode(matched) ?: findFirstEditableNode(root)
            }
        } ?: return AccessibilityActionResult(false, "No editable text field found on screen.")

        if (target.isPassword) {
            return AccessibilityActionResult(false, "Security rule: ULTRA AI refuses to inspect or type into password fields.")
        }

        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToEnter)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) target.hintText?.toString() else null
        val label = target.viewIdResourceName ?: hint ?: "text field"
        return if (ok) {
            AccessibilityActionResult(true, "Entered text into $label.", label)
        } else {
            AccessibilityActionResult(false, "Failed to set text on $label.")
        }
    }

    fun scrollScreen(direction: String): AccessibilityActionResult {
        val root = rootInActiveWindow ?: return AccessibilityActionResult(false, "No active window available.")
        val pkg = root.packageName?.toString().orEmpty()
        if (isProtectedPackage(pkg)) {
            return AccessibilityActionResult(false, "Scrolling blocked on protected screen ($pkg).")
        }
        val scrollable = findFirstScrollableNode(root)
            ?: return AccessibilityActionResult(false, "No scrollable container found on current screen.")
        val action = when (direction.lowercase()) {
            "up", "backward", "prev" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val ok = scrollable.performAction(action)
        return if (ok) {
            AccessibilityActionResult(true, "Scrolled $direction.")
        } else {
            AccessibilityActionResult(false, "Could not scroll $direction.")
        }
    }

    fun navigateBack(): AccessibilityActionResult {
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        return if (ok) {
            AccessibilityActionResult(true, "Navigated back.")
        } else {
            AccessibilityActionResult(false, "Global back action failed.")
        }
    }

    fun performTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibleUiElement>,
        maxNodes: Int
    ) {
        if (out.size >= maxNodes) return
        if (node.isPassword) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out += AccessibleUiElement(
                text = text.take(120),
                contentDescription = desc.take(120),
                viewId = viewId,
                className = node.className?.toString()?.substringAfterLast('.').orEmpty(),
                clickable = node.isClickable,
                editable = node.isEditable,
                scrollable = node.isScrollable,
                bounds = rect
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, out, maxNodes)
        }
    }

    private fun findMatchingNode(
        node: AccessibilityNodeInfo,
        query: String,
        viewId: String
    ): AccessibilityNodeInfo? {
        if (node.isPassword) return null
        val q = query.trim()
        val idMatch = viewId.isNotBlank() && node.viewIdResourceName?.contains(viewId, ignoreCase = true) == true
        val textMatch = q.isNotBlank() && (
            node.text?.toString()?.contains(q, ignoreCase = true) == true ||
                node.contentDescription?.toString()?.contains(q, ignoreCase = true) == true ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    node.hintText?.toString()?.contains(q, ignoreCase = true) == true)
            )
        if (idMatch || textMatch) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findMatchingNode(child, query, viewId)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && !node.isPassword && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun isProtectedPackage(pkg: String): Boolean {
        return pkg in setOf(
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.android.systemui"
        )
    }

    companion object {
        @Volatile
        private var instanceRef: WeakReference<UltraAccessibilityService>? = null

        private val _connected = MutableStateFlow(false)
        val connectedFlow: StateFlow<Boolean> = _connected.asStateFlow()

        private val _activePackage = MutableStateFlow("")
        val activePackageFlow: StateFlow<String> = _activePackage.asStateFlow()

        private val _latestSnapshot = MutableStateFlow<AccessibleScreenSnapshot?>(null)
        val latestSnapshotFlow: StateFlow<AccessibleScreenSnapshot?> = _latestSnapshot.asStateFlow()

        fun isServiceConnected(): Boolean = instanceRef?.get() != null && _connected.value

        fun getInstance(): UltraAccessibilityService? = instanceRef?.get()
    }
}
