package com.example.detox.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.example.MainActivity
import com.example.detox.data.DetoxMode
import com.example.detox.data.DetoxSessionRepository
import java.lang.ref.WeakReference

class DetoxAccessibilityService : AccessibilityService() {

    private lateinit var repository: DetoxSessionRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastRedirectElapsedMs: Long = 0L
    private var lastShadeDismissElapsedMs: Long = 0L
    private var lastCountedBypassElapsedMs: Long = 0L
    private var lastBlockedPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = WeakReference(this)
        repository = DetoxSessionRepository.getInstance(applicationContext)
        repository.refreshProtectionStatus()
        repository.reconcileAfterReboot()
        if (repository.isSessionActive() && !DetoxMonitorService.isRunning) {
            runCatching { DetoxMonitorService.start(applicationContext) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!::repository.isInitialized) {
            repository = DetoxSessionRepository.getInstance(applicationContext)
        }

        if (!repository.isSessionActive()) return

        if (repository.computeRemainingMs() <= 0L) {
            repository.completeSession()
            DetoxMonitorService.stop(applicationContext)
            DetoxMonitorService.cancelAllAlarms(applicationContext)
            return
        }

        val snap = repository.getCurrentSnapshot()
        if (!snap.isActive) return

        // 1. Detect multi-window / Freeform floating windows / PiP / OEM sidebars via active window stack
        if (hasDisallowedFloatingOrSplitWindow(snap.mode)) {
            val sourcePkg = event.packageName?.toString().orEmpty().ifBlank { "floating_window" }
            interceptDisallowedApp(sourcePkg, collapseFreeformAndSidebars = true)
            return
        }

        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank()) return

        // 2. Handle SystemUI (Notification Shade, Quick Settings, Control Center, Recents)
        if (packageName in SYSTEM_UI_PACKAGES) {
            handleSystemUiEvent(event, snap.mode)
            return
        }

        // 3. Enforce strict package allowlist (blocks OEM multi-window sidebars, Honor AI, Chrome, Settings, etc.)
        if (repository.isAllowedForegroundPackage(packageName, snap.mode)) {
            if (packageName == applicationContext.packageName) {
                lastBlockedPackage = null
            }
            return
        }

        interceptDisallowedApp(
            packageName = packageName,
            collapseFreeformAndSidebars = true
        )
    }

    /**
     * Checks the live window stack for PiP, Freeform floating windows (e.g. HONOR MagicOS / MIUI / OneUI
     * floating apps launched from sidebar), or Split-Screen dividers coexisting with our lock screen.
     */
    private fun hasDisallowedFloatingOrSplitWindow(mode: DetoxMode): Boolean {
        if (isDeviceLocked()) return false
        if (mode == DetoxMode.PHONE_ONLY && isCallRingingOrActive()) return false

        val currentWindows = runCatching { windows }.getOrNull() ?: return false
        if (currentWindows.isEmpty()) return false

        var appWindowCount = 0
        var hasSplitDivider = false
        var hasCompactFloatingAppWindow = false

        val displayMetrics = resources.displayMetrics
        val screenW = displayMetrics.widthPixels
        val screenH = displayMetrics.heightPixels
        val rect = Rect()

        for (win in currentWindows) {
            when (win.type) {
                AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> {
                    hasSplitDivider = true
                }
                AccessibilityWindowInfo.TYPE_APPLICATION -> {
                    appWindowCount++
                    win.getBoundsInScreen(rect)
                    val isFloatingOrPip = rect.width() > 0 && rect.height() > 0 &&
                        (rect.width() < (screenW * 0.90f) || rect.height() < (screenH * 0.80f))
                    if (isFloatingOrPip) {
                        hasCompactFloatingAppWindow = true
                    }
                }
                AccessibilityWindowInfo.TYPE_SYSTEM -> {
                    val title = win.title?.toString()?.lowercase().orEmpty()
                    if (title.contains("multi") ||
                        title.contains("dock") ||
                        title.contains("sidebar") ||
                        title.contains("smart") ||
                        title.contains("float") ||
                        title.contains("боков") ||
                        title.contains("панел")
                    ) {
                        return true
                    }
                }
            }
        }

        if (hasSplitDivider) return true
        // Two simultaneous application windows (e.g. LockScreen + Floating Honor AI / Chrome PiP)
        if (appWindowCount >= 2) return true
        // In Total Vacuum, no non-fullscreen app window should ever be on screen
        if (mode == DetoxMode.TOTAL_VACUUM && hasCompactFloatingAppWindow) return true
        // In Phone Only, a compact floating window on top is also a PiP/freeform bypass
        if (hasCompactFloatingAppWindow) return true

        return false
    }

    private fun handleSystemUiEvent(event: AccessibilityEvent, mode: DetoxMode) {
        if (isDeviceLocked()) return

        val className = event.className?.toString().orEmpty()
        val lowerClass = className.lowercase()
        val eventText = event.text?.joinToString(" ")?.lowercase().orEmpty()
        val descText = event.contentDescription?.toString()?.lowercase().orEmpty()

        // Never interfere with lockscreen, volume slider, power dialog, or toasts
        val isSafeSystemDialog = lowerClass.contains("keyguard") ||
            lowerClass.contains("bouncer") ||
            lowerClass.contains("volume") ||
            lowerClass.contains("globalactions") ||
            lowerClass.contains("power") ||
            lowerClass.contains("toast")
        if (isSafeSystemDialog) return

        val isRecents = lowerClass.contains("recents") || lowerClass.contains("overview")
        if (isRecents) {
            collapseAndRestoreLockScreen(countBypass = false)
            return
        }

        val blockShadeEnabled = repository.readBlockNotificationShade() || mode == DetoxMode.TOTAL_VACUUM
        if (!blockShadeEnabled) return

        val combinedMeta = "$lowerClass $eventText $descText"
        val isShadeOrControlCenter = combinedMeta.contains("shade") ||
            combinedMeta.contains("quicksettings") ||
            combinedMeta.contains("controlcenter") ||
            combinedMeta.contains("control center") ||
            combinedMeta.contains("центр управления") ||
            combinedMeta.contains("уведомлен") ||
            combinedMeta.contains("notification") ||
            combinedMeta.contains("qspanel") ||
            combinedMeta.contains("statusbar") ||
            combinedMeta.contains("panel") ||
            combinedMeta.contains("expandable") ||
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                (lowerClass.contains("framelayout") || lowerClass.contains("linearlayout")))

        if (isShadeOrControlCenter) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastShadeDismissElapsedMs < SHADE_DISMISS_COOLDOWN_MS) return
            lastShadeDismissElapsedMs = now

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            bringLockScreenDelayed(delayMs = 100L)
        }
    }

    private fun interceptDisallowedApp(packageName: String, collapseFreeformAndSidebars: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirectElapsedMs < REDIRECT_COOLDOWN_MS) return
        lastRedirectElapsedMs = now

        val isNewAttempt = packageName != lastBlockedPackage ||
            (now - lastCountedBypassElapsedMs) >= BYPASS_COUNT_COOLDOWN_MS
        if (isNewAttempt) {
            lastBlockedPackage = packageName
            lastCountedBypassElapsedMs = now
            repository.recordBypassAttempt()
        }

        if (!DetoxMonitorService.isRunning) {
            runCatching { DetoxMonitorService.start(applicationContext) }
        }

        if (collapseFreeformAndSidebars || isSettingsOrInstallerPackage(packageName)) {
            collapseAndRestoreLockScreen(countBypass = false)
        } else {
            bringLockScreenDelayed(delayMs = 0L)
        }
    }

    /**
     * Collapses Freeform floating windows, PiP, OEM sidebars (HONOR/MIUI/OneUI), and Settings screens
     * using BACK + HOME, then brings MainActivity back fullscreen after a short WM settle delay.
     */
    private fun collapseAndRestoreLockScreen(countBypass: Boolean) {
        if (countBypass) {
            repository.recordBypassAttempt()
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
        bringLockScreenDelayed(delayMs = 150L)
    }

    private fun bringLockScreenDelayed(delayMs: Long) {
        mainHandler.removeCallbacks(relaunchLockRunnable)
        if (delayMs <= 0L) {
            relaunchLockRunnable.run()
        } else {
            mainHandler.postDelayed(relaunchLockRunnable, delayMs)
        }
    }

    private val relaunchLockRunnable = Runnable {
        if (!::repository.isInitialized || !repository.isSessionActive()) return@Runnable
        val lockIntent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        runCatching { startActivity(lockIntent) }
    }

    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return km.isKeyguardLocked
    }

    private fun isCallRingingOrActive(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.mode == AudioManager.MODE_RINGTONE ||
            am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun isSettingsOrInstallerPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower == "com.android.settings" ||
            lower.contains("packageinstaller") ||
            lower.contains("securitycenter") ||
            lower.contains("safecenter") ||
            lower.contains("systemmanager") ||
            lower.contains("multiwindow") ||
            lower == "com.android.vending" ||
            lower == "com.samsung.android.lool"
    }

    override fun onInterrupt() {
        // Service remains connected
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(relaunchLockRunnable)
        if (activeInstance?.get() === this) {
            activeInstance = null
        }
        if (::repository.isInitialized) {
            repository.refreshProtectionStatus()
        }
        super.onDestroy()
    }

    companion object {
        private val SYSTEM_UI_PACKAGES = setOf(
            "com.android.systemui",
            "com.miui.systemui",
            "miui.systemui.plugin",
            "com.hihonor.systemui",
            "com.huawei.systemui"
        )
        private const val REDIRECT_COOLDOWN_MS = 350L
        private const val SHADE_DISMISS_COOLDOWN_MS = 400L
        private const val BYPASS_COUNT_COOLDOWN_MS = 2_500L

        @Volatile
        private var activeInstance: WeakReference<DetoxAccessibilityService>? = null

        fun enforceLockFocusNow() {
            val service = activeInstance?.get() ?: return
            if (!service::repository.isInitialized || !service.repository.isSessionActive()) return
            if (service.isDeviceLocked()) return
            val snap = service.repository.getCurrentSnapshot()
            if (snap.mode == DetoxMode.PHONE_ONLY && service.isCallRingingOrActive()) return
            val now = SystemClock.elapsedRealtime()
            if (now - service.lastRedirectElapsedMs < REDIRECT_COOLDOWN_MS) return
            service.lastRedirectElapsedMs = now
            service.collapseAndRestoreLockScreen(countBypass = false)
        }

        fun disableSelfSafely(): Boolean {
            val service = activeInstance?.get() ?: return false
            val repo = DetoxSessionRepository.getInstance(service.applicationContext)
            if (repo.isSessionActive()) return false
            return runCatching {
                service.disableSelf()
                true
            }.getOrDefault(false)
        }
    }
}
