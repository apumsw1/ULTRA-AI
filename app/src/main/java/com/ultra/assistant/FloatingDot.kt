package com.ultra.assistant

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class RadialMenuItemSpec(
    val id: String,
    val shortLabel: String,
    val glyph: String
)

data class RadialAnchor(
    val isLeftEdge: Boolean,
    val centerX: Float,
    val centerY: Float,
    val openRightward: Boolean
)

data class RadialItemPosition(
    val item: RadialMenuItemSpec,
    val x: Float,
    val y: Float,
    val angleDeg: Float,
    val visibilityWeight: Float
)

object RadialMenuLayoutMath {
    val defaultItems: List<RadialMenuItemSpec> = listOf(
        RadialMenuItemSpec("voice", "Voice", "🎙"),
        RadialMenuItemSpec("scan", "Scan", "⌖"),
        RadialMenuItemSpec("control", "Control", "⚙"),
        RadialMenuItemSpec("search", "Search", "⌕"),
        RadialMenuItemSpec("read", "Read", "🔊"),
        RadialMenuItemSpec("scroll", "Scroll", "⇵"),
        RadialMenuItemSpec("online", "Online", "☁"),
        RadialMenuItemSpec("offline", "Offline", "⚡"),
        RadialMenuItemSpec("settings", "Settings", "☰")
    )

    fun computeAnchor(
        dotCenterX: Float,
        dotCenterY: Float,
        screenWidth: Float,
        screenHeight: Float,
        menuRadiusPx: Float,
        edgeMarginPx: Float,
        topSafePx: Float,
        bottomSafePx: Float
    ): RadialAnchor {
        val isLeft = dotCenterX <= (screenWidth / 2f)
        val clampedY = dotCenterY.coerceIn(
            menuRadiusPx + topSafePx,
            (screenHeight - menuRadiusPx - bottomSafePx).coerceAtLeast(menuRadiusPx + topSafePx)
        )
        val anchorX = if (isLeft) edgeMarginPx else (screenWidth - edgeMarginPx)
        return RadialAnchor(
            isLeftEdge = isLeft,
            centerX = anchorX,
            centerY = clampedY,
            openRightward = isLeft
        )
    }

    fun computeItemPositions(
        items: List<RadialMenuItemSpec>,
        anchor: RadialAnchor,
        orbitRadiusX: Float,
        orbitRadiusY: Float,
        rotationOffsetDeg: Float
    ): List<RadialItemPosition> {
        if (items.isEmpty()) return emptyList()
        // Base semicircle spans 180 degrees facing inward; items space along a 240° virtual track so wheel rotation feels rich
        val stepDeg = 28f
        val baseMid = if (anchor.openRightward) 0f else 180f
        val startOffset = -((items.size - 1) * stepDeg) / 2f
        return items.mapIndexed { idx, spec ->
            val rawAngle = baseMid + startOffset + (idx * stepDeg) + rotationOffsetDeg
            val relToInward = normalizeSignedAngle(rawAngle - baseMid)
            val rad = Math.toRadians(rawAngle.toDouble())
            val x = anchor.centerX + (cos(rad) * orbitRadiusX).toFloat()
            val y = anchor.centerY + (sin(rad) * orbitRadiusY).toFloat()
            val visible = abs(relToInward) <= 95f
            val weight = if (!visible) 0f else (1f - (abs(relToInward) / 105f)).coerceIn(0.25f, 1f)
            RadialItemPosition(
                item = spec,
                x = x,
                y = y,
                angleDeg = rawAngle,
                visibilityWeight = weight
            )
        }.filter { it.visibilityWeight > 0f }
    }

    private fun normalizeSignedAngle(angleDeg: Float): Float {
        var a = angleDeg % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }
}

class FloatingDot(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var bubbleView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var radialMenuView: SemicircularRadialOverlayView? = null
    private var radialParams: WindowManager.LayoutParams? = null

    private var edgeGlowView: SystemEdgeGlowOverlayView? = null

    private var isExpanded = false
    private var currentState = "IDLE"
    private var sessionActive = false

    // Remembered session edge & vertical position
    private var snappedLeftEdge = false
    private var savedBubbleY = -1

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics
    )

    private fun screenWidthPx(): Int = context.resources.displayMetrics.widthPixels.coerceAtLeast(720)
    private fun screenHeightPx(): Int = context.resources.displayMetrics.heightPixels.coerceAtLeast(1280)

    private fun overlayWindowType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (bubbleView != null) {
            renderBubble()
            return
        }

        val bubbleSize = dp(54f).toInt()
        val orb = TextView(context).apply {
            text = "U"
            contentDescription = "ULTRA AI Floating Dot"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            elevation = dp(10f)
        }

        val defaultY = if (savedBubbleY > 0) savedBubbleY else (screenHeightPx() * 0.36f).toInt()
        savedBubbleY = defaultY

        val p = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (snappedLeftEdge) dp(10f).toInt() else (screenWidthPx() - bubbleSize - dp(10f).toInt())
            y = defaultY
        }

        attachBubbleDragAndTap(orb, p, bubbleSize)

        runCatching {
            wm.addView(orb, p)
            bubbleView = orb
            bubbleParams = p
            renderBubble()
        }
    }

    fun updateState(
        state: String,
        snippet: String? = null,
        isVoiceSessionActive: Boolean = VoiceAssistantService.isSessionListening,
        edgeGlowEnabled: Boolean = false
    ) {
        currentState = state
        sessionActive = isVoiceSessionActive
        bubbleView?.post {
            renderBubble()
            radialMenuView?.updateState(state, sessionActive)
        }
        updateSystemEdgeGlow(edgeGlowEnabled && state !in setOf("IDLE", "STOPPED"))
    }

    fun isShowing(): Boolean = bubbleView != null

    fun hide() {
        collapseRadialMenu()
        hideEdgeGlow()
        bubbleView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
        bubbleParams = null
        isExpanded = false
    }

    private fun updateSystemEdgeGlow(show: Boolean) {
        if (!show) {
            hideEdgeGlow()
            return
        }
        if (edgeGlowView == null) {
            val glow = SystemEdgeGlowOverlayView(context)
            val gp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            runCatching {
                wm.addView(glow, gp)
                edgeGlowView = glow
            }
        }
        edgeGlowView?.post { edgeGlowView?.updateState(currentState) }
    }

    private fun hideEdgeGlow() {
        edgeGlowView?.let {
            it.stopAnimation()
            runCatching { wm.removeView(it) }
        }
        edgeGlowView = null
    }

    private fun renderBubble() {
        val (fillColor, ringColor, glyph) = statePalette(currentState)
        bubbleView?.apply {
            text = glyph
            visibility = if (isExpanded) View.GONE else View.VISIBLE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fillColor)
                setStroke(dp(2.2f).toInt(), ringColor)
            }
        }
    }

    private fun expandRadialMenu() {
        if (isExpanded) return
        val bp = bubbleParams ?: return
        val bubbleSize = dp(54f)
        val dotCenterX = bp.x + bubbleSize / 2f
        val dotCenterY = bp.y + bubbleSize / 2f

        val sw = screenWidthPx().toFloat()
        val sh = screenHeightPx().toFloat()
        val anchor = RadialMenuLayoutMath.computeAnchor(
            dotCenterX = dotCenterX,
            dotCenterY = dotCenterY,
            screenWidth = sw,
            screenHeight = sh,
            menuRadiusPx = dp(148f),
            edgeMarginPx = dp(12f),
            topSafePx = dp(48f),
            bottomSafePx = dp(64f)
        )
        snappedLeftEdge = anchor.isLeftEdge

        val menuView = SemicircularRadialOverlayView(
            context = context,
            anchor = anchor,
            initialState = currentState,
            initialSessionActive = sessionActive,
            onCenterStartStop = {
                handleStartStopToggle()
            },
            onItemSelected = { itemId ->
                handleRadialAction(itemId)
                collapseRadialMenu()
            },
            onOutsideTap = {
                collapseRadialMenu()
            }
        )

        val mp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        runCatching {
            wm.addView(menuView, mp)
            radialMenuView = menuView
            radialParams = mp
            isExpanded = true
            renderBubble()
        }
    }

    private fun collapseRadialMenu() {
        radialMenuView?.let { runCatching { wm.removeView(it) } }
        radialMenuView = null
        radialParams = null
        isExpanded = false
        renderBubble()
    }

    private fun handleStartStopToggle() {
        val action = if (sessionActive || VoiceAssistantService.isSessionListening) {
            VoiceAssistantService.ACTION_PAUSE_SESSION
        } else {
            VoiceAssistantService.ACTION_START
        }
        runCatching {
            context.startService(Intent(context, VoiceAssistantService::class.java).setAction(action))
        }
    }

    private fun handleRadialAction(itemId: String) {
        when (itemId) {
            "voice" -> openMainRoute(UltraRoutes.VOICE)
            "control" -> openMainRoute(UltraRoutes.CONTROL)
            "search" -> openMainRoute(UltraRoutes.HOME)
            "settings" -> openMainRoute(UltraRoutes.SETTINGS)
            "scan" -> sendServiceAction(VoiceAssistantService.ACTION_SCAN_SCREEN)
            "read" -> sendServiceAction(VoiceAssistantService.ACTION_READ_SCREEN)
            "scroll" -> sendServiceAction(VoiceAssistantService.ACTION_SCROLL_SCREEN)
            "online" -> sendServiceAction(VoiceAssistantService.ACTION_SET_MODE_ONLINE)
            "offline" -> sendServiceAction(VoiceAssistantService.ACTION_SET_MODE_OFFLINE)
        }
    }

    private fun sendServiceAction(action: String) {
        runCatching {
            context.startService(Intent(context, VoiceAssistantService::class.java).setAction(action))
        }
    }

    private fun openMainRoute(route: String) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra("open_route", route)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachBubbleDragAndTap(
        handle: View,
        p: WindowManager.LayoutParams,
        bubbleSize: Int
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) + abs(dy) > dp(6f)) moved = true
                    val maxX = (screenWidthPx() - bubbleSize).coerceAtLeast(0)
                    val maxY = (screenHeightPx() - bubbleSize - dp(24f).toInt()).coerceAtLeast(dp(24f).toInt())
                    p.x = (startX + dx).coerceIn(0, maxX)
                    p.y = (startY + dy).coerceIn(dp(24f).toInt(), maxY)
                    runCatching { wm.updateViewLayout(handle, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        expandRadialMenu()
                    } else {
                        // Smooth edge-snap horizontally & remember position during session
                        val sw = screenWidthPx()
                        val centerX = p.x + bubbleSize / 2
                        snappedLeftEdge = centerX <= sw / 2
                        p.x = if (snappedLeftEdge) dp(10f).toInt() else (sw - bubbleSize - dp(10f).toInt())
                        savedBubbleY = p.y
                        runCatching { wm.updateViewLayout(handle, p) }
                    }
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        fun statePalette(state: String): Triple<Int, Int, String> = when (state.uppercase()) {
            "LISTENING", "CONNECTING" -> Triple(0xEE006B8E.toInt(), 0xFF38F2FF.toInt(), "◉")
            "USER_SPEAKING" -> Triple(0xEE008F9C.toInt(), 0xFF70FFF5.toInt(), "◉")
            "PROCESSING", "THINKING" -> Triple(0xEE4C2CB5.toInt(), 0xFFB89CFF.toInt(), "✦")
            "EXECUTING_ACTION", "VERIFYING" -> Triple(0xEE0D5F7D.toInt(), 0xFF34D399.toInt(), "⚡")
            "SPEAKING" -> Triple(0xEE0C7D60.toInt(), 0xFF34D399.toInt(), "♫")
            "INTERRUPTED", "RECONNECTING", "OFFLINE" -> Triple(0xEE7D5315.toInt(), 0xFFFFB347.toInt(), "U")
            "ERROR" -> Triple(0xEE7A1B2E.toInt(), 0xFFFF5C7C.toInt(), "!")
            else -> Triple(0xEE0D2A45.toInt(), 0xFF56C2FF.toInt(), "U")
        }
    }
}

private class SemicircularRadialOverlayView(
    context: Context,
    private val anchor: RadialAnchor,
    initialState: String,
    initialSessionActive: Boolean,
    private val onCenterStartStop: () -> Unit,
    private val onItemSelected: (String) -> Unit,
    private val onOutsideTap: () -> Unit
) : View(context) {

    private var state = initialState
    private var sessionActive = initialSessionActive
    private var rotationOffsetDeg = 0f
    private var highlightedItemId: String? = null

    private fun dp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v,
        resources.displayMetrics
    )

    private val outerOrbitRx = dp(116f)
    private val outerOrbitRy = dp(138f) // Half-football elliptical contour
    private val outerBackdropRx = dp(156f)
    private val outerBackdropRy = dp(178f)
    private val centerButtonRadius = dp(36f)
    private val itemRadius = dp(24f)

    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEA081320.toInt()
        style = Paint.Style.FILL
    }
    private val backdropRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF38F2FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x334BC9FF
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val centerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val centerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
    }
    private val itemFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val itemStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12.5f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val subStatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9CDDF8.toInt()
        textSize = dp(9f)
        textAlign = Paint.Align.CENTER
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(15f)
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD9ECFF.toInt()
        textSize = dp(9.5f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private var downX = 0f
    private var downY = 0f
    private var lastTouchAngle = 0f
    private var draggingWheel = false

    fun updateState(newState: String, isActive: Boolean) {
        state = newState
        sessionActive = isActive
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (_, ringColor, _) = FloatingDot.statePalette(state)
        backdropRimPaint.color = ringColor

        val ovalBackdrop = RectF(
            anchor.centerX - outerBackdropRx,
            anchor.centerY - outerBackdropRy,
            anchor.centerX + outerBackdropRx,
            anchor.centerY + outerBackdropRy
        )
        val startAngle = if (anchor.openRightward) -90f else 90f
        canvas.drawArc(ovalBackdrop, startAngle, 180f, true, backdropPaint)
        canvas.drawArc(ovalBackdrop, startAngle, 180f, false, backdropRimPaint)

        val orbitRect = RectF(
            anchor.centerX - outerOrbitRx,
            anchor.centerY - outerOrbitRy,
            anchor.centerX + outerOrbitRx,
            anchor.centerY + outerOrbitRy
        )
        canvas.drawArc(orbitRect, startAngle, 180f, false, trackPaint)

        // Draw rotatable semicircular wheel items
        val positions = RadialMenuLayoutMath.computeItemPositions(
            items = RadialMenuLayoutMath.defaultItems,
            anchor = anchor,
            orbitRadiusX = outerOrbitRx,
            orbitRadiusY = outerOrbitRy,
            rotationOffsetDeg = rotationOffsetDeg
        )
        for (pos in positions) {
            val isFocused = highlightedItemId == pos.item.id
            val scale = 0.85f + (pos.visibilityWeight * 0.15f)
            val r = itemRadius * scale
            itemFillPaint.color = if (isFocused) 0xFF14597A.toInt() else 0xEE102438.toInt()
            itemStrokePaint.color = if (isFocused) ringColor else (ringColor and 0x88FFFFFF.toInt())
            canvas.drawCircle(pos.x, pos.y, r, itemFillPaint)
            canvas.drawCircle(pos.x, pos.y, r, itemStrokePaint)

            canvas.drawText(pos.item.glyph, pos.x, pos.y + dp(2f), glyphPaint)
            canvas.drawText(pos.item.shortLabel, pos.x, pos.y + r + dp(11f), labelPaint)
        }

        // Single central Start/Stop circular toggle button
        val hubOffset = if (anchor.openRightward) dp(34f) else -dp(34f)
        val cx = anchor.centerX + hubOffset
        val cy = anchor.centerY

        centerFillPaint.color = if (sessionActive) 0xEE7D1E34.toInt() else 0xEE0B667D.toInt()
        centerStrokePaint.color = if (sessionActive) 0xFFFF6E87.toInt() else 0xFF38F2FF.toInt()
        canvas.drawCircle(cx, cy, centerButtonRadius, centerFillPaint)
        canvas.drawCircle(cx, cy, centerButtonRadius, centerStrokePaint)

        val btnTitle = if (sessionActive) "STOP" else "START"
        canvas.drawText(btnTitle, cx, cy + dp(2f), centerTextPaint)
        canvas.drawText(state.take(10), cx, cy + dp(14f), subStatePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTap()
            return true
        }
        val x = event.x
        val y = event.y
        val hubOffset = if (anchor.openRightward) dp(34f) else -dp(34f)
        val hubX = anchor.centerX + hubOffset
        val hubY = anchor.centerY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val dx = (x - anchor.centerX) / outerBackdropRx
                val dy = (y - anchor.centerY) / outerBackdropRy
                val insideSemicircle = (dx * dx + dy * dy <= 1.05f) &&
                    ((anchor.openRightward && x >= anchor.centerX - dp(24f)) ||
                        (!anchor.openRightward && x <= anchor.centerX + dp(24f)))
                if (!insideSemicircle) {
                    onOutsideTap()
                    return true
                }
                downX = x
                downY = y
                lastTouchAngle = Math.toDegrees(atan2((y - anchor.centerY).toDouble(), (x - anchor.centerX).toDouble())).toFloat()
                draggingWheel = false
                highlightedItemId = findHitItem(x, y)?.id
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot((x - downX).toDouble(), (y - downY).toDouble()) > dp(10f)) {
                    draggingWheel = true
                    val newAngle = Math.toDegrees(atan2((y - anchor.centerY).toDouble(), (x - anchor.centerX).toDouble())).toFloat()
                    var delta = newAngle - lastTouchAngle
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    rotationOffsetDeg = (rotationOffsetDeg + delta).coerceIn(-68f, 68f)
                    lastTouchAngle = newAngle
                    highlightedItemId = null
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!draggingWheel) {
                    if (hypot((x - hubX).toDouble(), (y - hubY).toDouble()) <= centerButtonRadius + dp(8f)) {
                        onCenterStartStop()
                        return true
                    }
                    val hit = findHitItem(x, y)
                    if (hit != null) {
                        onItemSelected(hit.id)
                        return true
                    }
                }
                highlightedItemId = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findHitItem(x: Float, y: Float): RadialMenuItemSpec? {
        val positions = RadialMenuLayoutMath.computeItemPositions(
            items = RadialMenuLayoutMath.defaultItems,
            anchor = anchor,
            orbitRadiusX = outerOrbitRx,
            orbitRadiusY = outerOrbitRy,
            rotationOffsetDeg = rotationOffsetDeg
        )
        return positions.firstOrNull { pos ->
            hypot((x - pos.x).toDouble(), (y - pos.y).toDouble()) <= itemRadius + dp(10f)
        }?.item
    }
}

private class SystemEdgeGlowOverlayView(context: Context) : View(context) {
    private var sweepDeg = 0f
    private var currentState = "LISTENING"
    private val shaderMatrix = Matrix()

    private fun dp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v,
        resources.displayMetrics
    )

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(10f)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private var animator: ValueAnimator? = null

    fun updateState(state: String) {
        currentState = state
        ensureAnimating()
        invalidate()
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    private fun ensureAnimating() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 6500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweepDeg = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val colors = when (currentState.uppercase()) {
            "ERROR" -> intArrayOf(0x66FF4D6D, 0x33FF8FA3, 0x66FF4D6D)
            "PROCESSING", "THINKING" -> intArrayOf(0x668B5CF6, 0x5538F2FF, 0x66C084FC, 0x668B5CF6)
            "SPEAKING", "EXECUTING_ACTION", "VERIFYING" -> intArrayOf(0x6634D399, 0x5538F2FF, 0x6610B981, 0x6634D399)
            "RECONNECTING", "OFFLINE", "INTERRUPTED" -> intArrayOf(0x55FFB347, 0x3338F2FF, 0x55FFB347)
            else -> intArrayOf(0x5538F2FF, 0x4400B4D8, 0x5534D399, 0x5538F2FF)
        }
        val sweep = SweepGradient(w / 2f, h / 2f, colors, null)
        shaderMatrix.setRotate(sweepDeg, w / 2f, h / 2f)
        sweep.setLocalMatrix(shaderMatrix)
        haloPaint.shader = sweep
        rimPaint.shader = sweep

        val r = dp(24f)
        canvas.drawRoundRect(dp(3f), dp(3f), w - dp(3f), h - dp(3f), r, r, haloPaint)
        canvas.drawRoundRect(dp(1f), dp(1f), w - dp(1f), h - dp(1f), r, r, rimPaint)
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
}
