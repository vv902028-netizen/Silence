package com.example.detox.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.example.MainActivity
import com.example.detox.data.DetoxMode
import com.example.detox.data.DetoxSessionRepository
import java.lang.ref.WeakReference

class DetoxAccessibilityService : AccessibilityService() {

    private lateinit var repository: DetoxSessionRepository
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

        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank()) return

        val snap = repository.getCurrentSnapshot()
        if (!snap.isActive) return

        if (packageName in SYSTEM_UI_PACKAGES) {
            handleSystemUiEvent(event, snap.mode)
            return
        }

        if (repository.isAllowedForegroundPackage(packageName, snap.mode)) {
            if (packageName == applicationContext.packageName) {
                lastBlockedPackage = null
            }
            return
        }

        interceptDisallowedApp(packageName)
    }

    private fun handleSystemUiEvent(event: AccessibilityEvent, mode: DetoxMode) {
        val className = event.className?.toString().orEmpty()
        val lowerClass = className.lowercase()

        // Never block device unlock / keyguard / incoming call / volume / power controls
        val isSafeSystemDialog = lowerClass.contains("keyguard") ||
            lowerClass.contains("bouncer") ||
            lowerClass.contains("volume") ||
            lowerClass.contains("globalactions") ||
            lowerClass.contains("power") ||
            lowerClass.contains("toast")
        if (isSafeSystemDialog) return

        val isRecents = lowerClass.contains("recents") || lowerClass.contains("overview")
        if (isRecents) {
            bringLockScreenToFront(kickBackFirst = false)
            return
        }

        val blockShadeEnabled = repository.readBlockNotificationShade() || mode == DetoxMode.TOTAL_VACUUM
        if (!blockShadeEnabled) return

        val isShadeOrControlCenter = lowerClass.contains("shade") ||
            lowerClass.contains("quicksettings") ||
            lowerClass.contains("controlcenter") ||
            lowerClass.contains("qspanel") ||
            lowerClass.contains("statusbar") ||
            lowerClass.contains("notificationpanel") ||
            lowerClass.contains("expandable") ||
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                (lowerClass.contains("framelayout") || lowerClass.contains("panel")))

        if (isShadeOrControlCenter) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastShadeDismissElapsedMs < SHADE_DISMISS_COOLDOWN_MS) return
            lastShadeDismissElapsedMs = now

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            bringLockScreenToFront(kickBackFirst = false)
        }
    }

    private fun interceptDisallowedApp(packageName: String) {
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

        val isHighRiskSystemScreen = isSettingsOrInstallerPackage(packageName)
        bringLockScreenToFront(kickBackFirst = isHighRiskSystemScreen)
    }

    private fun bringLockScreenToFront(kickBackFirst: Boolean) {
        if (kickBackFirst) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
        }

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

    private fun isSettingsOrInstallerPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower == "com.android.settings" ||
            lower.contains("packageinstaller") ||
            lower.contains("securitycenter") ||
            lower.contains("safecenter") ||
            lower.contains("systemmanager") ||
            lower == "com.android.vending" ||
            lower == "com.samsung.android.lool"
    }

    override fun onInterrupt() {
        // Service remains connected
    }

    override fun onDestroy() {
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
            "miui.systemui.plugin"
        )
        private const val REDIRECT_COOLDOWN_MS = 320L
        private const val SHADE_DISMISS_COOLDOWN_MS = 450L
        private const val BYPASS_COUNT_COOLDOWN_MS = 2_500L

        @Volatile
        private var activeInstance: WeakReference<DetoxAccessibilityService>? = null

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
