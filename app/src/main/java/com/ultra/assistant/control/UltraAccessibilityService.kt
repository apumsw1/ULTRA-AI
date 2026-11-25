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
    val isEmailScreen: Boolean = false,
    val hasSearchField: Boolean = false,
    val hasSearchButton: Boolean = false,
    val emailSummary: String = "",
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

    @Volatile
    private var lastSnapshotEventMs: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotBlank() && _activePackage.value != pkg) {
            _activePackage.value = pkg
        }
        // Only capture passive snapshot on actual window transitions, throttled >= 1200ms,
        // never on high-frequency TYPE_WINDOW_CONTENT_CHANGED ticks while scrolling or typing.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val now = System.currentTimeMillis()
            if (shouldRefreshPassiveSnapshot(lastSnapshotEventMs, now)) {
                lastSnapshotEventMs = now
                captureCurrentSnapshot()?.let { _latestSnapshot.value = it }
            }
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
        collectNodes(root, elements, maxNodes = 100)
        val title = elements.firstOrNull { it.text.isNotBlank() }?.text ?: pkg
        val isEmail = isEmailPackageOrScreen(pkg, elements)
        val hasSearchField = elements.any { elem ->
            elem.editable && listOf(elem.text, elem.contentDescription, elem.viewId)
                .any { it.contains("search", ignoreCase = true) || it.contains("সার্চ", ignoreCase = true) }
        }
        val hasSearchButton = elements.any { elem ->
            elem.clickable && listOf(elem.text, elem.contentDescription, elem.viewId)
                .any { it.contains("search", ignoreCase = true) || it.contains("সার্চ", ignoreCase = true) }
        }
        val emailSummary = if (isEmail) buildEmailSummary(elements) else ""
        return AccessibleScreenSnapshot(
            packageName = pkg,
            windowTitle = title,
            elements = elements,
            isEmailScreen = isEmail,
            hasSearchField = hasSearchField,
            hasSearchButton = hasSearchButton,
            emailSummary = emailSummary
        )
    }

    fun readScreenSummaryAloud(): AccessibilityActionResult {
        val snap = captureCurrentSnapshot()
            ?: return AccessibilityActionResult(false, "No readable active window is available.")
        if (snap.elements.isEmpty()) {
            return AccessibilityActionResult(false, "Current screen (${snap.packageName}) is protected or has no readable text.")
        }
        if (snap.isEmailScreen && snap.emailSummary.isNotBlank()) {
            return AccessibilityActionResult(
                success = true,
                message = snap.emailSummary,
                affectedElement = snap.packageName
            )
        }
        val lines = snap.elements
            .mapNotNull { elem ->
                val raw = elem.text.ifBlank { elem.contentDescription }.trim()
                raw.takeIf { it.length >= 2 }
            }
            .distinct()
            .take(14)
        val body = if (lines.isEmpty()) {
            "On ${snap.windowTitle}, ${snap.elements.size} interactive controls are visible."
        } else {
            lines.joinToString(". ")
        }
        return AccessibilityActionResult(
            success = true,
            message = "Screen (${snap.windowTitle}): $body",
            affectedElement = snap.packageName
        )
    }

    fun clickNextInApp(): AccessibilityActionResult {
        val root = rootInActiveWindow ?: return AccessibilityActionResult(false, "No active window available.")
        val pkg = root.packageName?.toString().orEmpty()
        if (isProtectedPackage(pkg)) {
            return AccessibilityActionResult(false, "Action blocked on protected screen ($pkg).")
        }
        val nextCandidates = listOf("Next", "Older", "Skip", "পরের", "পরবর্তী", "अगला")
        for (label in nextCandidates) {
            val match = findMatchingNode(root, label, "")
            if (match != null) {
                return clickByTextOrId(label)
            }
        }
        return scrollScreen("down")
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
            val rect = Rect()
            target.getBoundsInScreen(rect)
            if (!rect.isEmpty && performTap(rect.exactCenterX(), rect.exactCenterY())) {
                AccessibilityActionResult(true, "Tapped '$label' via gesture.", label)
            } else {
                AccessibilityActionResult(false, "Element '$label' did not accept click action.")
            }
        }
    }

    fun setTextOnField(
        targetQuery: String,
        textToEnter: String,
        viewId: String = "",
        submit: Boolean = false
    ): AccessibilityActionResult {
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
            } ?: findFirstEditableNode(root)
        } ?: return AccessibilityActionResult(false, "No editable text field found on screen.")

        if (target.isPassword) {
            return AccessibilityActionResult(false, "Security rule: ULTRA AI refuses to inspect or type into password fields.")
        }

        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToEnter)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) target.hintText?.toString() else null
        val label = target.viewIdResourceName ?: hint ?: "text field"
        if (!ok) {
            return AccessibilityActionResult(false, "Failed to set text on $label.")
        }
        var submittedNote = ""
        if (submit) {
            val imeSubmitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } else {
                false
            }
            if (imeSubmitted) {
                submittedNote = " and submitted"
            } else {
                val submitNode = listOf("Search", "Send", "Go", "Submit", "Done", "খুঁজুন", "সার্চ", "खोजें")
                    .firstNotNullOfOrNull { btnLabel -> findMatchingNode(root, btnLabel, "") }
                if (submitNode != null) {
                    var clickTarget: AccessibilityNodeInfo? = submitNode
                    while (clickTarget != null && !clickTarget.isClickable) {
                        clickTarget = clickTarget.parent
                    }
                    if ((clickTarget ?: submitNode).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        submittedNote = " and clicked submit"
                    }
                }
            }
        }
        return AccessibilityActionResult(true, "Entered text into $label$submittedNote.", label)
    }

    fun scrollScreen(direction: String): AccessibilityActionResult {
        val root = rootInActiveWindow ?: return AccessibilityActionResult(false, "No active window available.")
        val pkg = root.packageName?.toString().orEmpty()
        if (isProtectedPackage(pkg)) {
            return AccessibilityActionResult(false, "Scrolling blocked on protected screen ($pkg).")
        }
        val dirLower = direction.lowercase().trim()
        val isHorizontal = dirLower in setOf("left", "right")
        val isBackward = dirLower in setOf("up", "backward", "prev", "left")
        val action = if (isBackward) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val scrollable = findBestScrollableNode(root, preferHorizontal = isHorizontal)
        if (scrollable != null && scrollable.performAction(action)) {
            return AccessibilityActionResult(true, "Scrolled $dirLower.")
        }
        if (performScrollGesture(dirLower)) {
            return AccessibilityActionResult(true, "Scrolled $dirLower via gesture.")
        }
        return AccessibilityActionResult(false, "Could not scroll $dirLower on current screen.")
    }

    fun navigateBack(): AccessibilityActionResult {
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        return if (ok) {
            AccessibilityActionResult(true, "Navigated back.")
        } else {
            AccessibilityActionResult(false, "Global back action failed.")
        }
    }

    fun performSystemNavigation(target: String): AccessibilityActionResult {
        val normalized = target.lowercase().trim()
        val action = when (normalized) {
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent", "apps", "overview" -> GLOBAL_ACTION_RECENTS
            "notifications", "notification_shade" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings", "quicksettings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen", "lockscreen", "lock" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    GLOBAL_ACTION_LOCK_SCREEN
                } else {
                    return AccessibilityActionResult(false, "Lock screen via Accessibility requires Android 9+.")
                }
            }
            else -> GLOBAL_ACTION_BACK
        }
        val ok = performGlobalAction(action)
        return if (ok) {
            AccessibilityActionResult(true, "Performed system navigation: $normalized.")
        } else {
            AccessibilityActionResult(false, "System navigation '$normalized' failed.")
        }
    }

    fun performTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun performScrollGesture(direction: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat().coerceAtLeast(320f)
        val h = metrics.heightPixels.toFloat().coerceAtLeast(640f)
        val dir = direction.lowercase().trim()
        val path = Path()
        when (dir) {
            "left" -> {
                val cy = h * 0.5f
                path.moveTo(w * 0.22f, cy)
                path.lineTo(w * 0.78f, cy)
            }
            "right" -> {
                val cy = h * 0.5f
                path.moveTo(w * 0.78f, cy)
                path.lineTo(w * 0.22f, cy)
            }
            "up", "backward", "prev" -> {
                val cx = w * 0.5f
                path.moveTo(cx, h * 0.30f)
                path.lineTo(cx, h * 0.74f)
            }
            else -> {
                val cx = w * 0.5f
                path.moveTo(cx, h * 0.74f)
                path.lineTo(cx, h * 0.30f)
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 280L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun isEmailPackageOrScreen(pkg: String, elements: List<AccessibleUiElement>): Boolean {
        val pkgLower = pkg.lowercase()
        if (pkgLower in setOf(
                "com.google.android.gm",
                "com.microsoft.office.outlook",
                "com.yahoo.mobile.client.android.mail",
                "com.samsung.android.email.provider",
                "com.fsck.k9",
                "net.thunderbird.android"
            ) || pkgLower.contains("mail")
        ) {
            return true
        }
        val combinedLabels = elements.joinToString(" ") { "${it.text} ${it.contentDescription}" }.lowercase()
        return (combinedLabels.contains("inbox") || combinedLabels.contains("compose")) &&
            (combinedLabels.contains("reply") || combinedLabels.contains("unread") || combinedLabels.contains("mail"))
    }

    private fun buildEmailSummary(elements: List<AccessibleUiElement>): String {
        val textBlocks = elements
            .mapNotNull { elem ->
                elem.text.ifBlank { elem.contentDescription }.trim().takeIf { it.length >= 2 }
            }
            .distinct()
            .filterNot { label ->
                label.equals("Search in mail", ignoreCase = true) ||
                    label.equals("Navigate up", ignoreCase = true)
            }
            .take(12)
        return if (textBlocks.isEmpty()) {
            "Email screen is open, but no visible message lines are readable yet."
        } else {
            "Email screen summary: ${textBlocks.joinToString(". ")}"
        }
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibleUiElement>,
        maxNodes: Int
    ) {
        if (out.size >= maxNodes) return
        if (node.isPassword) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (node.isVisibleToUser && !rect.isEmpty) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable) {
                out += AccessibleUiElement(
                    text = text.take(140),
                    contentDescription = desc.take(140),
                    viewId = viewId,
                    className = node.className?.toString()?.substringAfterLast('.').orEmpty(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    bounds = rect
                )
            }
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
        val q = query.trim()
        val queryTokens = q.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0

        fun evaluate(current: AccessibilityNodeInfo) {
            if (current.isPassword) return
            val resId = current.viewIdResourceName.orEmpty()
            val text = current.text?.toString()?.trim().orEmpty()
            val desc = current.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                current.hintText?.toString()?.trim().orEmpty()
            } else {
                ""
            }

            var score = 0
            if (viewId.isNotBlank() && resId.contains(viewId, ignoreCase = true)) {
                score = maxOf(score, if (resId.equals(viewId, ignoreCase = true)) 120 else 95)
            }
            if (q.isNotBlank()) {
                val candidates = listOf(text, desc, hint)
                for (candidate in candidates) {
                    if (candidate.isBlank()) continue
                    when {
                        candidate.equals(q, ignoreCase = true) -> score = maxOf(score, 110)
                        candidate.contains(q, ignoreCase = true) -> score = maxOf(score, 85)
                        queryTokens.isNotEmpty() -> {
                            val lowered = candidate.lowercase()
                            val matchedTokens = queryTokens.count { lowered.contains(it) }
                            if (matchedTokens > 0) {
                                val ratioScore = (matchedTokens * 65) / queryTokens.size
                                score = maxOf(score, ratioScore)
                            }
                        }
                    }
                }
                if (score == 0 && resId.contains(q, ignoreCase = true)) {
                    score = 70
                }
            }
            if (score > 0) {
                if (current.isVisibleToUser) score += 25
                if (current.isClickable || current.isEditable) score += 8
                if (score > bestScore) {
                    bestScore = score
                    bestNode = current
                }
            }
            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                evaluate(child)
            }
        }

        evaluate(node)
        return bestNode
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

    private fun findBestScrollableNode(
        root: AccessibilityNodeInfo,
        preferHorizontal: Boolean = false
    ): AccessibilityNodeInfo? {
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Long.MIN_VALUE

        fun scan(current: AccessibilityNodeInfo) {
            if (current.isScrollable && current.isVisibleToUser) {
                val rect = Rect()
                current.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    val area = rect.width().toLong() * rect.height().toLong()
                    val orientationBonus = if (preferHorizontal) {
                        if (rect.width() >= rect.height() * 0.85f) 1_000_000_000L else 0L
                    } else {
                        if (rect.height() >= rect.width() * 0.85f) 1_000_000_000L else 0L
                    }
                    val score = orientationBonus + area
                    if (score > bestScore) {
                        bestScore = score
                        bestNode = current
                    }
                }
            }
            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                scan(child)
            }
        }

        scan(root)
        return bestNode
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

        const val PASSIVE_SNAPSHOT_DEBOUNCE_MS = 1200L

        fun shouldRefreshPassiveSnapshot(lastCaptureMs: Long, nowMs: Long): Boolean {
            return (nowMs - lastCaptureMs) >= PASSIVE_SNAPSHOT_DEBOUNCE_MS
        }

        fun isServiceConnected(): Boolean = instanceRef?.get() != null && _connected.value

        fun getInstance(): UltraAccessibilityService? = instanceRef?.get()
    }
}
