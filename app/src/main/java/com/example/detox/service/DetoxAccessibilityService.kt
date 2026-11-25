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

    private val lockActivityIntent: Intent by lazy {
        Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
    }

    private val shadeVerifyRunnable = Runnable {
        verifyAndCollapseShadeIfStillOpen()
    }

    private val relaunchLockRunnable = Runnable {
        if (!::repository.isInitialized || !repository.isSessionActive()) return@Runnable
        if (isDeviceLocked()) return@Runnable
        runCatching { startActivity(lockActivityIntent) }
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

        val blockNotifications = snap.mode == DetoxMode.TOTAL_VACUUM || repository.readBlockNotificationShade()
        val blockControlCenter = snap.mode == DetoxMode.TOTAL_VACUUM || repository.readBlockControlCenter()

        // 1. Instant detection of multi-window / Freeform floating windows / PiP / OEM sidebars
        if (hasDisallowedFloatingOrSplitWindow(snap.mode)) {
            val sourcePkg = packageName.ifBlank { "floating_window" }
            interceptDisallowedApp(sourcePkg, collapseFreeformAndSidebars = true)
            return
        }

        // 2. Live window-stack check for expanded Shade / Control Center
        if ((blockNotifications || blockControlCenter) &&
            !MainActivity.isWithinPinningGrace() &&
            hasDisallowedSystemPanelWindow(blockNotifications, blockControlCenter)
        ) {
            triggerShadeCollapseBurst(restoreLockScreen = !isAllowedDialerOrCall(snap.mode))
            return
        }

        if (packageName.isBlank()) return

        // 3. Handle SystemUI & OEM Control Center events
        if (packageName in SYSTEM_UI_PACKAGES) {
            handleSystemUiEvent(event, snap.mode, blockNotifications, blockControlCenter)
            return
        }

        // 4. Enforce strict package allowlist (blocks OEM sidebars, Honor AI, Chrome, Settings, etc.)
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
        if (appWindowCount >= 2) return true
        if (hasCompactFloatingAppWindow) return true

        return false
    }

    /**
     * Inspects live TYPE_SYSTEM windows to detect Notification Shade and/or Control Center
     * respecting granular user toggles (both unconditionally enforced in TOTAL_VACUUM).
     */
    private fun hasDisallowedSystemPanelWindow(
        blockNotifications: Boolean,
        blockControlCenter: Boolean
    ): Boolean {
        if (!blockNotifications && !blockControlCenter) return false
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

            val matchesControlCenter = isControlCenterMeta(title)
            val matchesNotifications = isNotificationShadeMeta(title)

            if (blockControlCenter && matchesControlCenter) return true
            if (blockNotifications && matchesNotifications) return true

            // When BOTH are blocked (including TOTAL_VACUUM), catch any large unlabeled SystemUI panel
            if (blockNotifications && blockControlCenter) {
                win.getBoundsInScreen(rect)
                if (rect.width() >= minShadeWidth && rect.height() >= minShadeHeight) {
                    return true
                }
            }
        }
        return false
    }

    private fun isControlCenterMeta(lowerMeta: String): Boolean {
        return lowerMeta.contains("controlcenter") ||
            lowerMeta.contains("control center") ||
            lowerMeta.contains("центр управления") ||
            lowerMeta.contains("quicksettings") ||
            lowerMeta.contains("quick settings") ||
            lowerMeta.contains("быстрые настройки") ||
            lowerMeta.contains("qspanel") ||
            lowerMeta.contains("qs_panel") ||
            lowerMeta.contains("tiles")
    }

    private fun isNotificationShadeMeta(lowerMeta: String): Boolean {
        return lowerMeta.contains("notification") ||
            lowerMeta.contains("уведомлен") ||
            lowerMeta.contains("shade") ||
            lowerMeta.contains("шторк") ||
            lowerMeta.contains("statusbar")
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

    private fun handleSystemUiEvent(
        event: AccessibilityEvent,
        mode: DetoxMode,
        blockNotifications: Boolean,
        blockControlCenter: Boolean
    ) {
        if (isDeviceLocked()) return

        val className = event.className?.toString().orEmpty()
        val lowerClass = className.lowercase()
        val eventText = event.text?.joinToString(" ")?.lowercase().orEmpty()
        val descText = event.contentDescription?.toString()?.lowercase().orEmpty()
        val pkgLower = event.packageName?.toString()?.lowercase().orEmpty()
        val combinedMeta = "$pkgLower $lowerClass $eventText $descText"

        // Never interfere with lockscreen, volume slider, power dialog, emergency dialer, or toasts
        if (isSafeSystemTitle(combinedMeta)) return

        val isRecents = lowerClass.contains("recents") || lowerClass.contains("overview")
        if (isRecents) {
            collapseAndRestoreLockScreen()
            return
        }

        if (!blockNotifications && !blockControlCenter) return
        if (MainActivity.isWithinPinningGrace()) return

        val isControlCenterEvent = isControlCenterMeta(combinedMeta)
        val isNotificationEvent = isNotificationShadeMeta(combinedMeta)
        val isGenericExpandedPanel = (blockNotifications && blockControlCenter) && (
            combinedMeta.contains("panel") ||
                combinedMeta.contains("expandable") ||
                (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    (lowerClass.contains("framelayout") || lowerClass.contains("linearlayout")))
            )

        val shouldDismiss = (blockControlCenter && isControlCenterEvent) ||
            (blockNotifications && isNotificationEvent) ||
            isGenericExpandedPanel

        if (shouldDismiss) {
            triggerShadeCollapseBurst(restoreLockScreen = !isAllowedDialerOrCall(mode))
        }
    }

    private fun triggerShadeCollapseBurst(restoreLockScreen: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShadeDismissElapsedMs < SHADE_DISMISS_COOLDOWN_MS) return
        lastShadeDismissElapsedMs = now

        dismissShadeOnce()
        if (restoreLockScreen) {
            bringLockScreenNowAndConfirm()
        }

        mainHandler.removeCallbacks(shadeVerifyRunnable)
        for (delayMs in SHADE_VERIFY_DELAYS_MS) {
            mainHandler.postDelayed(shadeVerifyRunnable, delayMs)
        }
    }

    private fun verifyAndCollapseShadeIfStillOpen() {
        if (!::repository.isInitialized || !repository.isSessionActive()) return
        if (isDeviceLocked() || MainActivity.isWithinPinningGrace() || isPowerOrSafeDialogVisible()) return

        val snap = repository.getCurrentSnapshot()
        val blockNotifications = snap.mode == DetoxMode.TOTAL_VACUUM || repository.readBlockNotificationShade()
        val blockControlCenter = snap.mode == DetoxMode.TOTAL_VACUUM || repository.readBlockControlCenter()
        if (!blockNotifications && !blockControlCenter) return

        val allowedDialerOrCall = isAllowedDialerOrCall(snap.mode)
        val panelVisible = hasDisallowedSystemPanelWindow(blockNotifications, blockControlCenter)
        val unfocusedAndBothBlocked = blockNotifications && blockControlCenter &&
            !MainActivity.isLockWindowFocused && !allowedDialerOrCall

        if (panelVisible || unfocusedAndBothBlocked) {
            dismissShadeOnce()
            if (!allowedDialerOrCall) {
                bringLockScreenDelayed(delayMs = 50L)
            }
        }
    }

    private fun dismissShadeOnce() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Zero-latency escape interception: brings MainActivity back and collapses overlays FIRST,
     * then records bypass count asynchronously so SharedPreferences disk commit never adds lag.
     */
    private fun interceptDisallowedApp(packageName: String, collapseFreeformAndSidebars: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirectElapsedMs < REDIRECT_COOLDOWN_MS) return
        lastRedirectElapsedMs = now

        // 1. Immediate UI recovery (0ms)
        if (collapseFreeformAndSidebars || isSettingsOrInstallerPackage(packageName)) {
            collapseAndRestoreLockScreen()
        } else {
            bringLockScreenNowAndConfirm()
        }

        // 2. Deferred bookkeeping off the critical redirect path
        val isNewAttempt = packageName != lastBlockedPackage ||
            (now - lastCountedBypassElapsedMs) >= BYPASS_COUNT_COOLDOWN_MS
        if (isNewAttempt) {
            lastBlockedPackage = packageName
            lastCountedBypassElapsedMs = now
            mainHandler.post {
                if (::repository.isInitialized && repository.isSessionActive()) {
                    repository.recordBypassAttempt()
                }
            }
        }

        if (!DetoxMonitorService.isRunning) {
            mainHandler.post {
                runCatching { DetoxMonitorService.start(applicationContext) }
            }
        }
    }

    private fun collapseAndRestoreLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
        // Fire immediately AND schedule a quick follow-up after WM transition settles
        relaunchLockRunnable.run()
        bringLockScreenDelayed(delayMs = 95L)
    }

    private fun bringLockScreenNowAndConfirm() {
        relaunchLockRunnable.run()
        bringLockScreenDelayed(delayMs = 80L)
    }

    private fun bringLockScreenDelayed(delayMs: Long) {
        mainHandler.removeCallbacks(relaunchLockRunnable)
        if (delayMs <= 0L) {
            relaunchLockRunnable.run()
        } else {
            mainHandler.postDelayed(relaunchLockRunnable, delayMs)
        }
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
        private val SHADE_VERIFY_DELAYS_MS = longArrayOf(140L, 340L, 640L)
        private const val REDIRECT_COOLDOWN_MS = 90L
        private const val SHADE_DISMISS_COOLDOWN_MS = 80L
        private const val BYPASS_COUNT_COOLDOWN_MS = 2_500L

        @Volatile
        private var activeInstance: WeakReference<DetoxAccessibilityService>? = null

        fun onLockWindowLostFocus() {
            val service = activeInstance?.get() ?: return
            if (!service::repository.isInitialized || !service.repository.isSessionActive()) return
            if (service.isDeviceLocked() || MainActivity.isWithinPinningGrace()) return
            if (service.isPowerOrSafeDialogVisible()) return

            val snap = service.repository.getCurrentSnapshot()
            if (service.isAllowedDialerOrCall(snap.mode)) return

            val blockNotifications = snap.mode == DetoxMode.TOTAL_VACUUM ||
                service.repository.readBlockNotificationShade()
            val blockControlCenter = snap.mode == DetoxMode.TOTAL_VACUUM ||
                service.repository.readBlockControlCenter()

            if (service.hasDisallowedFloatingOrSplitWindow(snap.mode)) {
                service.collapseAndRestoreLockScreen()
                return
            }

            if ((blockNotifications && blockControlCenter) ||
                service.hasDisallowedSystemPanelWindow(blockNotifications, blockControlCenter)
            ) {
                service.triggerShadeCollapseBurst(restoreLockScreen = true)
            }
        }

        fun enforceWatchdogCheck() {
            val service = activeInstance?.get() ?: return
            if (!service::repository.isInitialized || !service.repository.isSessionActive()) return
            if (service.isDeviceLocked() || MainActivity.isWithinPinningGrace()) return
            if (service.isPowerOrSafeDialogVisible()) return

            val snap = service.repository.getCurrentSnapshot()
            val allowedDialerOrCall = service.isAllowedDialerOrCall(snap.mode)
            val blockNotifications = snap.mode == DetoxMode.TOTAL_VACUUM ||
                service.repository.readBlockNotificationShade()
            val blockControlCenter = snap.mode == DetoxMode.TOTAL_VACUUM ||
                service.repository.readBlockControlCenter()

            if (service.hasDisallowedFloatingOrSplitWindow(snap.mode)) {
                service.mainHandler.post { service.collapseAndRestoreLockScreen() }
                return
            }

            if ((blockNotifications || blockControlCenter) &&
                service.hasDisallowedSystemPanelWindow(blockNotifications, blockControlCenter)
            ) {
                service.mainHandler.post {
                    service.dismissShadeOnce()
                    if (!allowedDialerOrCall) {
                        service.bringLockScreenDelayed(delayMs = 50L)
                    }
                }
                return
            }

            if (!MainActivity.isLockWindowFocused && !allowedDialerOrCall) {
                // Only force back when not intentionally viewing an allowed panel (e.g. notifications allowed in PHONE_ONLY)
                val bothBlocked = blockNotifications && blockControlCenter
                val leftToOtherApp = service.lastObservedAppPackage != null &&
                    service.lastObservedAppPackage != service.applicationContext.packageName
                if (bothBlocked || leftToOtherApp || snap.mode == DetoxMode.TOTAL_VACUUM) {
                    service.mainHandler.post {
                        if (bothBlocked) {
                            service.dismissShadeOnce()
                        }
                        service.bringLockScreenDelayed(delayMs = 0L)
                    }
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
