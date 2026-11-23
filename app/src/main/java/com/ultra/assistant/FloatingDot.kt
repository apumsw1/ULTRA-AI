package com.ultra.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ultra.assistant.data.LocalSettingsRepository
import kotlin.math.abs

class FloatingDot(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val settingsRepo = LocalSettingsRepository(context)
    private var rootView: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private var orbView: TextView? = null
    private var expandedPanel: LinearLayout? = null
    private var stateBadge: TextView? = null
    private var snippetView: TextView? = null
    private var micButton: TextView? = null
    private var stopButton: TextView? = null

    private var isExpanded = false
    private var currentState = "IDLE"
    private var currentSnippet = "Tap to talk or open ULTRA AI"

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics
    ).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (rootView != null) {
            renderState()
            return
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        // Minimized floating bubble
        val bubbleSize = dp(56)
        val orb = TextView(context).apply {
            text = "U"
            contentDescription = "ULTRA AI Floating Assistant"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            elevation = 18f
        }
        orbView = orb

        // Maximized mini control card
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF00B1726.toInt())
                setStroke(dp(1), 0xFF2CCFFF.toInt())
            }
            elevation = 22f
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(context).apply {
            text = "ULTRA AI"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFEAF4FF.toInt())
        }

        val badge = TextView(context).apply {
            text = "READY"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setTextColor(0xFF00E5FF.toInt())
        }
        stateBadge = badge

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val minimizeBtn = TextView(context).apply {
            text = "—"
            contentDescription = "Minimize floating assistant"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setTextColor(0xFF9BB4CC.toInt())
            setOnClickListener {
                isExpanded = false
                renderState()
            }
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            contentDescription = "Close floating assistant"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(6), dp(4))
            setTextColor(0xFFFF6E87.toInt())
            setOnClickListener {
                settingsRepo.setFloatingAssistantEnabledSync(false)
                hide()
                runCatching {
                    context.startService(
                        Intent(context, VoiceAssistantService::class.java)
                            .setAction(VoiceAssistantService.ACTION_HIDE_DOT)
                    )
                }
            }
        }

        headerRow.addView(titleText)
        headerRow.addView(badge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        headerRow.addView(spacer)
        headerRow.addView(minimizeBtn)
        headerRow.addView(closeBtn)

        val snippet = TextView(context).apply {
            text = currentSnippet
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFFC3D7EB.toInt())
            setPadding(0, dp(8), 0, dp(10))
        }
        snippetView = snippet

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val micBtn = createActionChip("Listen", 0xFF0A4F66.toInt(), 0xFF7BE9FF.toInt()) {
            val action = if (VoiceAssistantService.isRunning && currentState == "SPEAKING") {
                VoiceAssistantService.ACTION_INTERRUPT
            } else {
                VoiceAssistantService.ACTION_START_SINGLE_TURN
            }
            runCatching {
                context.startService(Intent(context, VoiceAssistantService::class.java).setAction(action))
            }
        }
        micButton = micBtn

        val stopBtn = createActionChip("Stop", 0xFF3D1E2E.toInt(), 0xFFFF8AA0.toInt()) {
            runCatching {
                context.startService(
                    Intent(context, VoiceAssistantService::class.java)
                        .setAction(VoiceAssistantService.ACTION_STOP_SPEAKING)
                )
            }
        }
        stopButton = stopBtn

        val openVoiceBtn = createActionChip("Full Voice", 0xFF19324D.toInt(), 0xFFEAF4FF.toInt()) {
            isExpanded = false
            renderState()
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra("open_route", UltraRoutes.VOICE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }

        actionRow.addView(micBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(36)
        ).apply { marginEnd = dp(6) })
        actionRow.addView(stopBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(36)
        ).apply { marginEnd = dp(6) })
        actionRow.addView(openVoiceBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(36)
        ))

        panel.addView(headerRow)
        panel.addView(snippet)
        panel.addView(actionRow)
        expandedPanel = panel

        container.addView(orb, LinearLayout.LayoutParams(bubbleSize, bubbleSize))
        container.addView(panel, LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT))

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(14)
            y = dp(180)
        }

        attachDragBehavior(orb, container, p) {
            isExpanded = !isExpanded
            renderState()
        }
        attachDragBehavior(headerRow, container, p, onTap = null)

        runCatching {
            wm.addView(container, p)
            rootView = container
            params = p
            renderState()
        }
    }

    fun updateState(state: String, snippet: String? = null) {
        currentState = state
        if (!snippet.isNullOrBlank()) {
            currentSnippet = snippet
        } else {
            currentSnippet = when (state) {
                "LISTENING" -> "Listening to you..."
                "PROCESSING" -> "Thinking..."
                "SPEAKING" -> "Speaking response..."
                "OFFLINE" -> "Offline local voice mode"
                else -> "Tap to talk or open ULTRA AI"
            }
        }
        if (rootView != null) {
            rootView?.post { renderState() }
        }
    }

    fun isShowing(): Boolean = rootView != null

    fun hide() {
        rootView?.let { runCatching { wm.removeView(it) } }
        rootView = null
        params = null
        orbView = null
        expandedPanel = null
        stateBadge = null
        snippetView = null
        micButton = null
        stopButton = null
        isExpanded = false
    }

    private fun renderState() {
        val (fillColor, ringColor, glyph) = when (currentState) {
            "LISTENING" -> Triple(0xEE007B9E.toInt(), 0xFF38F2FF.toInt(), "◉")
            "PROCESSING" -> Triple(0xEE5B36CC.toInt(), 0xFFB89CFF.toInt(), "✦")
            "SPEAKING" -> Triple(0xEE0C7D60.toInt(), 0xFF34D399.toInt(), "♫")
            "OFFLINE" -> Triple(0xEE7D5315.toInt(), 0xFFFFB347.toInt(), "U")
            else -> Triple(0xEE0D2A45.toInt(), 0xFF56C2FF.toInt(), "U")
        }

        orbView?.apply {
            text = glyph
            visibility = if (isExpanded) View.GONE else View.VISIBLE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fillColor)
                setStroke(dp(2), ringColor)
            }
        }
        expandedPanel?.apply {
            visibility = if (isExpanded) View.VISIBLE else View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF20B1726.toInt())
                setStroke(dp(1), ringColor)
            }
        }
        stateBadge?.apply {
            text = currentState
            setTextColor(ringColor)
        }
        snippetView?.text = currentSnippet
        micButton?.text = if (currentState == "SPEAKING") "Interrupt" else "Listen"
        stopButton?.visibility = if (currentState == "SPEAKING" || currentState == "LISTENING") View.VISIBLE else View.GONE
    }

    private fun createActionChip(
        label: String,
        bgColor: Int,
        fgColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(fgColor)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(bgColor)
            }
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragBehavior(
        handle: View,
        targetRoot: View,
        p: WindowManager.LayoutParams,
        onTap: (() -> Unit)?
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
                    if (abs(dx) + abs(dy) > dp(6)) moved = true
                    p.x = (startX - dx).coerceAtLeast(0)
                    p.y = (startY + dy).coerceAtLeast(dp(24))
                    runCatching { wm.updateViewLayout(targetRoot, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onTap?.invoke()
                    }
                    true
                }
                else -> false
            }
        }
    }
}
