package com.example.detox.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.detox.data.DetoxMode
import com.example.detox.ui.TimeFormatter
import com.example.ui.theme.AlabasterBackground
import com.example.ui.theme.CloudInkDark
import com.example.ui.theme.DeepSlateInk
import com.example.ui.theme.GuardianBlue
import com.example.ui.theme.GuardianBlueDark
import com.example.ui.theme.MidnightSlateBackground
import com.example.ui.theme.MutedCloudTextDark
import com.example.ui.theme.MutedSlateText
import com.example.ui.theme.QuietIndigo
import com.example.ui.theme.QuietIndigoDark
import com.example.ui.theme.SurfaceHighDark
import com.example.ui.theme.SurfaceHighLight

/**
 * Manages Accessibility-owned TYPE_ACCESSIBILITY_OVERLAY windows (zero extra permissions needed):
 * 1. Top status-bar touch shield (blocks Control Center / Notification Shade swipes before they start).
 * 2. Full-screen monolithic lock curtain styled in the unified Material 3 Family Link palette
 *    (persistent in TOTAL_VACUUM, instant brief shield on escape attempts in PHONE_ONLY so no
 *    closing/reopening animation or clickable Control Center tiles ever appear).
 */
class DetoxOverlayController(private val serviceContext: Context) {

    private val windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var statusBarShieldView: View? = null
    private var currentStripConfig: StripConfig = StripConfig.NONE

    private var curtainRootView: FrameLayout? = null
    private var curtainRingView: ProgressRingView? = null
    private var curtainCountdownText: TextView? = null
    private var curtainEndTimeText: TextView? = null
    private var curtainHintText: TextView? = null
    private var curtainDarkTheme: Boolean? = null
    private var curtainMode: DetoxMode? = null
    private var isPersistentCurtainActive: Boolean = false

    private val hideTransientCurtainRunnable = Runnable {
        if (!isPersistentCurtainActive) {
            removeCurtainInternal()
        }
    }

    private enum class StripConfig {
        NONE,
        FULL_WIDTH,
        RIGHT_CONTROL_CENTER,
        LEFT_NOTIFICATIONS
    }

    fun updateTopStatusBarShield(
        shouldShow: Boolean,
        blockNotifications: Boolean,
        blockControlCenter: Boolean
    ) {
        val targetConfig = when {
            !shouldShow || (!blockNotifications && !blockControlCenter) -> StripConfig.NONE
            blockNotifications && blockControlCenter -> StripConfig.FULL_WIDTH
            blockControlCenter -> StripConfig.RIGHT_CONTROL_CENTER
            else -> StripConfig.LEFT_NOTIFICATIONS
        }

        mainHandler.post {
            if (targetConfig == currentStripConfig) return@post
            removeStatusBarShieldInternal()
            if (targetConfig != StripConfig.NONE) {
                attachStatusBarShieldInternal(targetConfig)
            }
            currentStripConfig = targetConfig
        }
    }

    fun showOrUpdatePersistentCurtain(
        mode: DetoxMode,
        remainingMs: Long,
        totalDurationMs: Long,
        plannedEndWallMs: Long
    ) {
        mainHandler.post {
            mainHandler.removeCallbacks(hideTransientCurtainRunnable)
            isPersistentCurtainActive = true
            ensureCurtainAttached(mode)
            bindCurtainData(mode, remainingMs, totalDurationMs, plannedEndWallMs)
        }
    }

    fun hidePersistentCurtain() {
        mainHandler.post {
            mainHandler.removeCallbacks(hideTransientCurtainRunnable)
            isPersistentCurtainActive = false
            removeCurtainInternal()
        }
    }

    /**
     * Instantly displays a monolithic Material 3 shield over the screen for [durationMs] while
     * the system finishes collapsing Control Center or restoring MainActivity silently underneath.
     */
    fun flashTransientShield(
        mode: DetoxMode,
        remainingMs: Long,
        totalDurationMs: Long,
        plannedEndWallMs: Long,
        durationMs: Long = 440L
    ) {
        mainHandler.post {
            if (isPersistentCurtainActive) {
                ensureCurtainAttached(mode)
                bindCurtainData(mode, remainingMs, totalDurationMs, plannedEndWallMs)
                return@post
            }
            mainHandler.removeCallbacks(hideTransientCurtainRunnable)
            ensureCurtainAttached(mode)
            bindCurtainData(mode, remainingMs, totalDurationMs, plannedEndWallMs)
            mainHandler.postDelayed(hideTransientCurtainRunnable, durationMs)
        }
    }

    fun hideAll() {
        mainHandler.post {
            mainHandler.removeCallbacks(hideTransientCurtainRunnable)
            isPersistentCurtainActive = false
            removeCurtainInternal()
            removeStatusBarShieldInternal()
            currentStripConfig = StripConfig.NONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachStatusBarShieldInternal(config: StripConfig) {
        val wm = windowManager ?: return
        val dm = serviceContext.resources.displayMetrics
        val statusBarHeight = readStatusBarHeightPx()

        val widthPx = when (config) {
            StripConfig.FULL_WIDTH -> WindowManager.LayoutParams.MATCH_PARENT
            StripConfig.RIGHT_CONTROL_CENTER, StripConfig.LEFT_NOTIFICATIONS -> (dm.widthPixels * 0.56f).toInt()
            StripConfig.NONE -> return
        }
        val gravity = when (config) {
            StripConfig.RIGHT_CONTROL_CENTER -> Gravity.TOP or Gravity.END
            else -> Gravity.TOP or Gravity.START
        }

        val view = View(serviceContext).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { _, _ -> true }
        }

        val params = WindowManager.LayoutParams(
            widthPx,
            statusBarHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        ).apply {
            this.gravity = gravity
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        runCatching {
            wm.addView(view, params)
            statusBarShieldView = view
        }
    }

    private fun removeStatusBarShieldInternal() {
        val view = statusBarShieldView ?: return
        statusBarShieldView = null
        runCatching { windowManager?.removeViewImmediate(view) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureCurtainAttached(mode: DetoxMode) {
        val wm = windowManager ?: return
        val isDark = isNightMode()

        if (curtainRootView != null && curtainDarkTheme == isDark && curtainMode == mode) {
            return
        }
        removeCurtainInternal()

        val bgColor = if (isDark) MidnightSlateBackground.toArgb() else AlabasterBackground.toArgb()
        val inkColor = if (isDark) CloudInkDark.toArgb() else DeepSlateInk.toArgb()
        val mutedColor = if (isDark) MutedCloudTextDark.toArgb() else MutedSlateText.toArgb()
        val trackColor = if (isDark) SurfaceHighDark.toArgb() else SurfaceHighLight.toArgb()
        val accentColor = when (mode) {
            DetoxMode.TOTAL_VACUUM -> if (isDark) QuietIndigoDark.toArgb() else QuietIndigo.toArgb()
            DetoxMode.PHONE_ONLY -> if (isDark) GuardianBlueDark.toArgb() else GuardianBlue.toArgb()
        }

        val displayFont = runCatching { ResourcesCompat.getFont(serviceContext, R.font.plus_jakarta_sans) }.getOrNull()
        val bodyFont = runCatching { ResourcesCompat.getFont(serviceContext, R.font.inter) }.getOrNull()

        val root = FrameLayout(serviceContext).apply {
            setBackgroundColor(bgColor)
            isClickable = true
            isFocusable = false
            setOnTouchListener { _, _ -> true }
        }

        val ringSizePx = dpToPx(268)
        val centerBox = FrameLayout(serviceContext).apply {
            layoutParams = FrameLayout.LayoutParams(ringSizePx, ringSizePx, Gravity.CENTER)
        }

        val ringView = ProgressRingView(serviceContext, trackColor, accentColor).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val textStack = LinearLayout(serviceContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val countdownView = TextView(serviceContext).apply {
            setTextColor(inkColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 50f)
            typeface = displayFont
            fontFeatureSettings = "tnum"
            maxLines = 1
            gravity = Gravity.CENTER
        }

        val endTimeView = TextView(serviceContext).apply {
            setTextColor(mutedColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = bodyFont
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(6), 0, 0)
        }

        textStack.addView(countdownView)
        textStack.addView(endTimeView)
        centerBox.addView(ringView)
        centerBox.addView(textStack)
        root.addView(centerBox)

        val bottomHint = TextView(serviceContext).apply {
            text = if (mode == DetoxMode.TOTAL_VACUUM) {
                serviceContext.getString(R.string.lock_vacuum_emergency_hint)
            } else {
                ""
            }
            setTextColor(mutedColor)
            alpha = 0.72f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = bodyFont
            gravity = Gravity.CENTER
            val navPadding = readNavBarHeightPx() + dpToPx(28)
            setPadding(dpToPx(24), dpToPx(12), dpToPx(24), navPadding)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        }
        root.addView(bottomHint)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        runCatching {
            wm.addView(root, params)
            curtainRootView = root
            curtainRingView = ringView
            curtainCountdownText = countdownView
            curtainEndTimeText = endTimeView
            curtainHintText = bottomHint
            curtainDarkTheme = isDark
            curtainMode = mode
        }
    }

    private fun bindCurtainData(
        mode: DetoxMode,
        remainingMs: Long,
        totalDurationMs: Long,
        plannedEndWallMs: Long
    ) {
        val fraction = if (totalDurationMs > 0L) {
            (remainingMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        curtainRingView?.setProgressFraction(fraction)

        val isMultiDay = remainingMs >= 86_400_000L
        curtainCountdownText?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isMultiDay) 34f else 50f)
            text = TimeFormatter.formatCountdown(remainingMs)
        }
        curtainEndTimeText?.text = serviceContext.getString(
            R.string.lock_free_at,
            TimeFormatter.formatClockTime(plannedEndWallMs)
        )
        curtainHintText?.visibility = if (mode == DetoxMode.TOTAL_VACUUM) View.VISIBLE else View.INVISIBLE
    }

    private fun removeCurtainInternal() {
        val root = curtainRootView ?: return
        curtainRootView = null
        curtainRingView = null
        curtainCountdownText = null
        curtainEndTimeText = null
        curtainHintText = null
        curtainDarkTheme = null
        curtainMode = null
        runCatching { windowManager?.removeViewImmediate(root) }
    }

    private fun isNightMode(): Boolean {
        val uiMode = serviceContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun readStatusBarHeightPx(): Int {
        val resId = serviceContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        val dimen = if (resId > 0) serviceContext.resources.getDimensionPixelSize(resId) else dpToPx(32)
        return dimen.coerceAtLeast(dpToPx(24)) + dpToPx(6)
    }

    private fun readNavBarHeightPx(): Int {
        val resId = serviceContext.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) serviceContext.resources.getDimensionPixelSize(resId) else dpToPx(24)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            serviceContext.resources.displayMetrics
        ).toInt()
    }

    private class ProgressRingView(
        context: Context,
        trackColor: Int,
        accentColor: Int
    ) : View(context) {

        private var progressFraction: Float = 1f
        private val strokePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            10f,
            resources.displayMetrics
        )
        private val insetPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            12f,
            resources.displayMetrics
        )
        private val arcBounds = RectF()

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
            color = trackColor
        }

        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
            color = accentColor
        }

        fun setProgressFraction(fraction: Float) {
            val clamped = fraction.coerceIn(0f, 1f)
            if (clamped != progressFraction) {
                progressFraction = clamped
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            arcBounds.set(insetPx, insetPx, width - insetPx, height - insetPx)
            canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)
            val sweep = (progressFraction * 360f).coerceIn(2f, 360f)
            canvas.drawArc(arcBounds, -90f, sweep, false, accentPaint)
        }
    }
}
