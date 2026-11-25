package com.example.detox.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private lateinit var overlayController: DetoxOverlayController
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastRedirectElapsedMs: Long = 0L
    private var lastShadeDismissElapsedMs: Long = 0L
    private var lastCountedBypassElapsedMs: Long = 0L
    private var lastBlockedPackage: String? = null
    @Volatile
    private var lastObservedAppPackage: String? = null
    private var screenReceiverRegistered: Boolean = false

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

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Always hide overlays on screen off so native OS Keyguard & 112 button stay 100% unobstructed
                    if (::overlayController.isInitialized) {
                        overlayController.hideAll()
                    }
                }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                    syncOverlaysInternal()
                }
            }
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
        overlayController = DetoxOverlayController(this)
        registerScreenStateReceiver()
        repository.refreshProtectionStatus()
        repository.reconcileAfterReboot()
        if (repository.isSessionActive()) {
            if (!DetoxMonitorService.isRunning) {
                runCatching { DetoxMonitorService.start(applicationContext) }
            }
            syncOverlaysInternal()
        }
    }

    private fun registerScreenStateReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(screenStateReceiver, filter)
            }
            screenReceiverRegistered = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!::repository.isInitialized) {
            repository = DetoxSessionRepository.getInstance(applicationContext)
        }
        if (!::overlayController.isInitialized) {
            overlayController = DetoxOverlayController(this)
        }

        if (!repository.isSessionActive()) {
            overlayController.hideAll()
            return
        }

        if (repository.computeRemainingMs() <= 0L) {
            overlayController.hideAll()
            repository.completeSession()
            DetoxMonitorService.stop(applicationContext)
            DetoxMonitorService.cancelAllAlarms(applicationContext)
            return
        }

        if (isDeviceLocked()) {
            overlayController.hideAll()
            return
        }

        val snap = repository.getCurrentSnapshot()
        if (!snap.isActive) {
            overlayController.hideAll()
            return
        }

        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isNotBlank() &&
            packageName !in SYSTEM_UI_PACKAGES &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            lastObservedAppPackage = packageName
        }

        // If power dialog or emergency call screen appeared, immediately yield overlays for safety
        val eventMeta = "${event.className?.toString().orEmpty()} ${event.text?.joinToString(" ").orEmpty()}".lowercase()
        if (isSafeSystemTitle(eventMeta) || isPowerOrSafeDialogVisible() || packageName in EMERGENCY_CORE_PACKAGES) {
            if (packageName in EMERGENCY_CORE_PACKAGES || isPowerDialogMeta(eventMeta) || isPowerOrSafeDialogVisible()) {
                overlayController.hideAll()
            }
            return
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
            collapseFreeformAndSidebars = isSettingsOrInstallerPackage(packageName)
        )
    }

    private fun syncOverlaysInternal() {
        if (!::repository.isInitialized || !::overlayController.isInitialized) return
        val remaining = repository.computeRemainingMs()
        val snap = repository.getCurrentSnapshot()

        if (!snap.isActive || remaining <= 0L || isDeviceLocked() || isPowerOrSafeDialogVisible()) {
            overlayController.hideAll()
            return
        }

        val shouldYieldForCall = when (snap.mode) {
            DetoxMode.PHONE_ONLY -> isCallRingingOrActive() || lastObservedAppPackage in EMERGENCY_CORE_PACKAGES
            DetoxMode.TOTAL_VACUUM -> isActiveVoiceCall() || lastObservedAppPackage in EMERGENCY_CORE_PACKAGES
        }
        if (shouldYieldForCall) {
            overlayController.hidePersistentCurtain()
            overlayController.updateTopStatusBarShield(
                shouldShow = false,
                blockNotifications = false,
                blockControlCenter = false
            )
            return
        }

        val blockNotifications = snap.mode == DetoxMode.TOTAL_VACUUM || repository.readBlockNotificationShade()
        val blockControlCenter = snap.mode == DetoxMode.TOTAL_VACUUM || repository.readBlockControlCenter()

        if (snap.mode == DetoxMode.TOTAL_VACUUM && !MainActivity.isWithinPinningGrace()) {
            // Monolithic full-screen lock curtain: zero animations, zero swipe-away, zero Control Center tap-through
            overlayController.updateTopStatusBarShield(
                shouldShow = true,
                blockNotifications = true,
                blockControlCenter = true
            )
            overlayController.showOrUpdatePersistentCurtain(
                mode = DetoxMode.TOTAL_VACUUM,
                remainingMs = remaining,
                totalDurationMs = snap.totalDurationMs,
                plannedEndWallMs = snap.plannedEndWallMs
            )
        } else {
            overlayController.hidePersistentCurtain()
            overlayController.updateTopStatusBarShield(
                shouldShow = !MainActivity.isWithinPinningGrace(),
                blockNotifications = blockNotifications,
                blockControlCenter = blockControlCenter
            )
        }
    }

    private fun flashMonolithicShieldNow(durationMs: Long = 440L) {
        if (!::overlayController.isInitialized || !::repository.isInitialized) return
        if (isDeviceLocked() || MainActivity.isWithinPinningGrace() || isPowerOrSafeDialogVisible()) return
        val snap = repository.getCurrentSnapshot()
        if (!snap.isActive) return
        val remaining = repository.computeRemainingMs()
        if (remaining <= 0L) return
        overlayController.flashTransientShield(
            mode = snap.mode,
            remainingMs = remaining,
            totalDurationMs = snap.totalDurationMs,
            plannedEndWallMs = snap.plannedEndWallMs,
            durationMs = durationMs
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
        val minShadeHeight = (dm.heightPixels * 0.25f).toInt()
        val rect = Rect()

        for (win in currentWindows) {
            if (win.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val title = win.title?.toString()?.lowercase().orEmpty()
            if (isSafeSystemTitle(title)) continue

            val matchesControlCenter = isControlCenterMeta(title)
            val matchesNotifications = isNotificationShadeMeta(title)

            if (blockControlCenter && matchesControlCenter) return true
            if (blockNotifications && matchesNotifications) return true

            if (blockNotifications && blockControlCenter) {
                win.getBoundsInScreen(rect)
                if (rect.width() >= minShadeWidth && rect.height() >= minShadeHeight) {
                    return true
                }
            }
        }
        return false
    }

    private fun hasAnySystemPanelWindow(): Boolean {
        return hasDisallowedSystemPanelWindow(
            blockNotifications = true,
            blockControlCenter = true
        )
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
            if (isPowerDialogMeta(title)) {
                return true
            }
        }
        return false
    }

    private fun isPowerDialogMeta(lowerText: String): Boolean {
        if (lowerText.isBlank()) return false
        return lowerText.contains("globalactions") ||
            lowerText.contains("power") ||
            lowerText.contains("shutdown") ||
            lowerText.contains("reboot") ||
            lowerText.contains("питани") ||
            lowerText.contains("выключ") ||
            lowerText.contains("перезагруз") ||
            lowerText.contains("emergency") ||
            lowerText.contains("экстрен") ||
            lowerText.contains("pin") ||
            lowerText.contains("закреп")
    }

    private fun isSafeSystemTitle(lowerText: String): Boolean {
        if (lowerText.isBlank()) return false
        return lowerText.contains("keyguard") ||
            lowerText.contains("bouncer") ||
            lowerText.contains("volume") ||
            lowerText.contains("громкост") ||
            lowerText.contains("toast") ||
            isPowerDialogMeta(lowerText)
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

        if (isSafeSystemTitle(combinedMeta)) return

        val isRecents = lowerClass.contains("recents") || lowerClass.contains("overview")
        if (isRecents) {
            flashMonolithicShieldNow(durationMs = 460L)
            collapseAndRestoreLockScreen(useHomeAction = false)
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

        // Instantly drop an opaque touch-eating curtain so Hotspot / tiles CANNOT be tapped even mid-swipe
        flashMonolithicShieldNow(durationMs = 520L)
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
            flashMonolithicShieldNow(durationMs = 420L)
            dismissShadeOnce()
            if (!allowedDialerOrCall) {
                bringLockScreenDelayed(delayMs = 40L)
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

        // 1. Instant zero-animation monolithic shield so user sees NO closing/reopening bounce
        flashMonolithicShieldNow(durationMs = 460L)

        if (collapseFreeformAndSidebars) {
            collapseAndRestoreLockScreen(useHomeAction = true)
        } else {
            // Direct no-animation return without flashing the Home screen
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

    private fun collapseAndRestoreLockScreen(useHomeAction: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        if (useHomeAction) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        relaunchLockRunnable.run()
        bringLockScreenDelayed(delayMs = 90L)
    }

    private fun bringLockScreenNowAndConfirm() {
        relaunchLockRunnable.run()
        bringLockScreenDelayed(delayMs = 75L)
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

    private fun isActiveVoiceCall(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.mode == AudioManager.MODE_IN_CALL ||
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
        if (::overlayController.isInitialized) {
            overlayController.hideAll()
        }
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenStateReceiver) }
            screenReceiverRegistered = false
        }
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
        private val SHADE_VERIFY_DELAYS_MS = longArrayOf(120L, 300L, 580L)
        private const val REDIRECT_COOLDOWN_MS = 90L
        private const val SHADE_DISMISS_COOLDOWN_MS = 70L
        private const val BYPASS_COUNT_COOLDOWN_MS = 2_500L

        @Volatile
        private var activeInstance: WeakReference<DetoxAccessibilityService>? = null

        fun syncOverlaysNow() {
            val service = activeInstance?.get() ?: return
            service.mainHandler.post { service.syncOverlaysInternal() }
        }

        fun clearOverlaysNow() {
            val service = activeInstance?.get() ?: return
            if (service::overlayController.isInitialized) {
                service.overlayController.hideAll()
            }
        }

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
                service.flashMonolithicShieldNow(durationMs = 460L)
                service.collapseAndRestoreLockScreen(useHomeAction = true)
                return
            }

            if ((blockNotifications && blockControlCenter) ||
                service.hasDisallowedSystemPanelWindow(blockNotifications, blockControlCenter)
            ) {
                service.triggerShadeCollapseBurst(restoreLockScreen = true)
            } else if (!service.hasAnySystemPanelWindow()) {
                service.flashMonolithicShieldNow(durationMs = 400L)
                service.bringLockScreenNowAndConfirm()
            }
        }

        fun enforceWatchdogCheck() {
            val service = activeInstance?.get() ?: return
            if (!service::repository.isInitialized || !service.repository.isSessionActive()) {
                clearOverlaysNow()
                return
            }

            service.syncOverlaysInternal()

            if (service.isDeviceLocked() || MainActivity.isWithinPinningGrace()) return
            if (service.isPowerOrSafeDialogVisible()) return

            val snap = service.repository.getCurrentSnapshot()
            val allowedDialerOrCall = service.isAllowedDialerOrCall(snap.mode)
            val blockNotifications = snap.mode == DetoxMode.TOTAL_VACUUM ||
                service.repository.readBlockNotificationShade()
            val blockControlCenter = snap.mode == DetoxMode.TOTAL_VACUUM ||
                service.repository.readBlockControlCenter()

            if (service.hasDisallowedFloatingOrSplitWindow(snap.mode)) {
                service.mainHandler.post {
                    service.flashMonolithicShieldNow(durationMs = 460L)
                    service.collapseAndRestoreLockScreen(useHomeAction = true)
                }
                return
            }

            if ((blockNotifications || blockControlCenter) &&
                service.hasDisallowedSystemPanelWindow(blockNotifications, blockControlCenter)
            ) {
                service.mainHandler.post {
                    service.flashMonolithicShieldNow(durationMs = 440L)
                    service.dismissShadeOnce()
                    if (!allowedDialerOrCall) {
                        service.bringLockScreenDelayed(delayMs = 40L)
                    }
                }
                return
            }

            if (!MainActivity.isLockWindowFocused && !allowedDialerOrCall) {
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
            if (service::overlayController.isInitialized) {
                service.overlayController.hideAll()
            }
            return runCatching {
                service.disableSelf()
                true
            }.getOrDefault(false)
        }
    }
}
