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
    @Volatile
    private var lastObservedAppPackage: String? = null

    private val shadeVerifyRunnable = Runnable {
        verifyAndCollapseShadeIfStillOpen()
    }

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
        if (isDeviceLocked()) return

        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isNotBlank() &&
            packageName !in SYSTEM_UI_PACKAGES &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            lastObservedAppPackage = packageName
        }

        val blockShadeEnabled = repository.readBlockNotificationShade() || snap.mode == DetoxMode.TOTAL_VACUUM

        // 1. Detect multi-window / Freeform floating windows / PiP / OEM sidebars via active window stack
        if (hasDisallowedFloatingOrSplitWindow(snap.mode)) {
            val sourcePkg = packageName.ifBlank { "floating_window" }
            interceptDisallowedApp(sourcePkg, collapseFreeformAndSidebars = true)
            return
        }

        // 2. Detect expanded Notification Shade / Control Center directly from live window stack
        if (blockShadeEnabled && !MainActivity.isWithinPinningGrace() && hasExpandedSystemShadeWindow()) {
            triggerShadeCollapseBurst(restoreLockScreen = !isAllowedDialerOrCall(snap.mode))
            return
        }

        if (packageName.isBlank()) return

        // 3. Handle SystemUI & OEM Control Center events
        if (packageName in SYSTEM_UI_PACKAGES) {
            handleSystemUiEvent(event, snap.mode, blockShadeEnabled)
            return
        }

        // 4. Enforce strict package allowlist (blocks OEM multi-window sidebars, Honor AI, Chrome, Settings, etc.)
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
        // Compact floating app window on top is a PiP/freeform bypass
        if (hasCompactFloatingAppWindow) return true

        return false
    }

    /**
     * Detects expanded Notification Shade or OEM Control Center (including HONOR MagicOS / MIUI / OneUI)
     * directly from live TYPE_SYSTEM window bounds, defeating repeated swipes and custom OEM class names.
     */
    private fun hasExpandedSystemShadeWindow(): Boolean {
        if (isDeviceLocked()) return false
        val currentWindows = runCatching { windows }.getOrNull() ?: return false
        if (currentWindows.isEmpty()) return false

        val dm = resources.displayMetrics
        val minShadeWidth = (dm.widthPixels * 0.50f).toInt()
        val minShadeHeight = (dm.heightPixels * 0.28f).toInt()
        val rect = Rect()

        for (win in currentWindows) {
            if (win.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val title = win.title?.toString()?.lowercase().orEmpty()
            if (isSafeSystemTitle(title)) continue

            win.getBoundsInScreen(rect)
            val isExpandedPanel = rect.width() >= minShadeWidth && rect.height() >= minShadeHeight
            val isExplicitControlCenter = title.contains("shade") ||
                title.contains("control") ||
                title.contains("quick") ||
                title.contains("notification") ||
                title.contains("центр управления") ||
                title.contains("шторк") ||
                title.contains("уведомлен")

            if (isExpandedPanel || isExplicitControlCenter) {
                return true
            }
        }
        return false
    }

    private fun isPowerOrSafeDialogVisible(): Boolean {
        val currentWindows = runCatching { windows }.getOrNull() ?: return false
        for (win in currentWindows) {
            if (win.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val title = win.title?.toString()?.lowercase().orEmpty()
            if (isSafeSystemTitle(title)) {
                return true
            }
        }
        return false
    }

    private fun isSafeSystemTitle(lowerText: String): Boolean {
        if (lowerText.isBlank()) return false
        return lowerText.contains("keyguard") ||
            lowerText.contains("bouncer") ||
            lowerText.contains("volume") ||
            lowerText.contains("громкост") ||
            lowerText.contains("globalactions") ||
            lowerText.contains("power") ||
            lowerText.contains("shutdown") ||
            lowerText.contains("reboot") ||
            lowerText.contains("питани") ||
            lowerText.contains("выключ") ||
            lowerText.contains("перезагруз") ||
            lowerText.contains("emergency") ||
            lowerText.contains("экстрен") ||
            lowerText.contains("toast") ||
            lowerText.contains("pin") ||
            lowerText.contains("закреп")
    }

    private fun handleSystemUiEvent(event: AccessibilityEvent, mode: DetoxMode, blockShadeEnabled: Boolean) {
        if (isDeviceLocked()) return

        val className = event.className?.toString().orEmpty()
        val lowerClass = className.lowercase()
        val eventText = event.text?.joinToString(" ")?.lowercase().orEmpty()
        val descText = event.contentDescription?.toString()?.lowercase().orEmpty()
        val combinedMeta = "$lowerClass $eventText $descText"

        // Never interfere with lockscreen, volume slider, power dialog, emergency dialer, or toasts
        if (isSafeSystemTitle(combinedMeta)) return

        val isRecents = lowerClass.contains("recents") || lowerClass.contains("overview")
        if (isRecents) {
            collapseAndRestoreLockScreen(countBypass = false)
            return
        }

        if (!blockShadeEnabled) return
        if (MainActivity.isWithinPinningGrace()) return

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
            event.packageName?.toString()?.contains("controlcenter", ignoreCase = true) == true ||
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                (lowerClass.contains("framelayout") || lowerClass.contains("linearlayout")))

        if (isShadeOrControlCenter) {
            triggerShadeCollapseBurst(restoreLockScreen = !isAllowedDialerOrCall(mode))
        }
    }

    /**
     * Dismisses Notification Shade / Control Center immediately and runs follow-up verification pulses
     * to defeat rapid repeat swipes and gestures held down past the first dismiss command.
     */
    private fun triggerShadeCollapseBurst(restoreLockScreen: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShadeDismissElapsedMs < SHADE_DISMISS_COOLDOWN_MS) return
        lastShadeDismissElapsedMs = now

        dismissShadeOnce()
        if (restoreLockScreen) {
            bringLockScreenDelayed(delayMs = 90L)
        }

        // Follow-up verification pulses catch Control Center settling open after finger release
        mainHandler.removeCallbacks(shadeVerifyRunnable)
        for (delayMs in SHADE_VERIFY_DELAYS_MS) {
            mainHandler.postDelayed(shadeVerifyRunnable, delayMs)
        }
    }

    private fun verifyAndCollapseShadeIfStillOpen() {
        if (!::repository.isInitialized || !repository.isSessionActive()) return
        if (isDeviceLocked() || MainActivity.isWithinPinningGrace() || isPowerOrSafeDialogVisible()) return

        val snap = repository.getCurrentSnapshot()
        val blockShadeEnabled = repository.readBlockNotificationShade() || snap.mode == DetoxMode.TOTAL_VACUUM
        if (!blockShadeEnabled) return

        val allowedDialerOrCall = isAllowedDialerOrCall(snap.mode)
        val shadeStillOpen = hasExpandedSystemShadeWindow() ||
            (!MainActivity.isLockWindowFocused && !allowedDialerOrCall)

        if (shadeStillOpen) {
            dismissShadeOnce()
            if (!allowedDialerOrCall) {
                bringLockScreenDelayed(delayMs = 80L)
            }
        }
    }

    private fun dismissShadeOnce() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
        bringLockScreenDelayed(delayMs = 140L)
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
        if (isDeviceLocked()) return@Runnable
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

    private fun isAllowedDialerOrCall(mode: DetoxMode): Boolean {
        if (isCallRingingOrActive()) return true
        val currentPkg = lastObservedAppPackage
        if (currentPkg in EMERGENCY_CORE_PACKAGES) return true
        if (mode == DetoxMode.PHONE_ONLY) {
            if (MainActivity.isWithinDialerGrace()) return true
            if (!currentPkg.isNullOrBlank() &&
                currentPkg != applicationContext.packageName &&
                repository.isAllowedForegroundPackage(currentPkg, DetoxMode.PHONE_ONLY)
            ) {
                return true
            }
        }
        return false
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
        mainHandler.removeCallbacks(shadeVerifyRunnable)
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
            "com.huawei.systemui",
            "com.hihonor.controlcenter",
            "com.huawei.controlcenter",
            "com.oplus.systemui",
            "com.coloros.systemui"
        )
        private val EMERGENCY_CORE_PACKAGES = setOf(
            "com.android.emergency",
            "com.android.server.telecom",
            "com.android.phone"
        )
        private val SHADE_VERIFY_DELAYS_MS = longArrayOf(160L, 380L, 700L)
        private const val REDIRECT_COOLDOWN_MS = 250L
        private const val SHADE_DISMISS_COOLDOWN_MS = 120L
        private const val BYPASS_COUNT_COOLDOWN_MS = 2_500L

        @Volatile
        private var activeInstance: WeakReference<DetoxAccessibilityService>? = null

        /**
         * Triggered immediately when MainActivity loses window focus while unlocked and outside grace.
         * Defeats Control Center / Notification Shade pull-downs and OEM sidebars.
         */
        fun onLockWindowLostFocus() {
            val service = activeInstance?.get() ?: return
            if (!service::repository.isInitialized || !service.repository.isSessionActive()) return
            if (service.isDeviceLocked() || MainActivity.isWithinPinningGrace()) return
            if (service.isPowerOrSafeDialogVisible()) return

            val snap = service.repository.getCurrentSnapshot()
            if (service.isAllowedDialerOrCall(snap.mode)) return

            val blockShadeEnabled = service.repository.readBlockNotificationShade() ||
                snap.mode == DetoxMode.TOTAL_VACUUM

            if (blockShadeEnabled) {
                service.triggerShadeCollapseBurst(restoreLockScreen = true)
            }
            if (service.hasDisallowedFloatingOrSplitWindow(snap.mode)) {
                service.collapseAndRestoreLockScreen(countBypass = false)
            }
        }

        /**
         * Periodic 1-second safety net invoked by DetoxMonitorService so a stubborn Control Center,
         * floating window, or post-unlock launcher cannot stay open even after repeated swipes.
         */
        fun enforceWatchdogCheck() {
            val service = activeInstance?.get() ?: return
            if (!service::repository.isInitialized || !service.repository.isSessionActive()) return
            if (service.isDeviceLocked() || MainActivity.isWithinPinningGrace()) return
            if (service.isPowerOrSafeDialogVisible()) return

            val snap = service.repository.getCurrentSnapshot()
            val allowedDialerOrCall = service.isAllowedDialerOrCall(snap.mode)
            val blockShadeEnabled = service.repository.readBlockNotificationShade() ||
                snap.mode == DetoxMode.TOTAL_VACUUM

            if (service.hasDisallowedFloatingOrSplitWindow(snap.mode)) {
                service.mainHandler.post {
                    service.collapseAndRestoreLockScreen(countBypass = false)
                }
                return
            }

            if (blockShadeEnabled && service.hasExpandedSystemShadeWindow()) {
                service.mainHandler.post {
                    service.dismissShadeOnce()
                    if (!allowedDialerOrCall) {
                        service.bringLockScreenDelayed(delayMs = 80L)
                    }
                }
                return
            }

            if (!MainActivity.isLockWindowFocused && !allowedDialerOrCall) {
                service.mainHandler.post {
                    if (blockShadeEnabled) {
                        service.dismissShadeOnce()
                    }
                    service.bringLockScreenDelayed(delayMs = 0L)
                }
            }
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
