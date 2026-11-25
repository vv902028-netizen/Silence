package com.example.detox.data

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.detox.admin.DetoxDeviceAdminReceiver
import com.example.detox.service.DetoxAccessibilityService
import com.example.detox.service.DetoxMonitorService
import com.example.detox.service.DetoxSystemDisplayController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

class DetoxSessionRepository private constructor(private val appContext: Context) {

    private val storageContext: Context =
        appContext.createDeviceProtectedStorageContext()

    private val prefs: SharedPreferences =
        storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _sessionFlow = MutableStateFlow(readSnapshotLocked())
    val sessionFlow: StateFlow<DetoxSessionSnapshot> = _sessionFlow.asStateFlow()

    private val _protectionFlow = MutableStateFlow(readProtectionStatus())
    val protectionFlow: StateFlow<ProtectionStatus> = _protectionFlow.asStateFlow()

    private val _lastCompletedFlow = MutableStateFlow<CompletedSessionSummary?>(null)
    val lastCompletedFlow: StateFlow<CompletedSessionSummary?> = _lastCompletedFlow.asStateFlow()

    private val _blockShadeFlow = MutableStateFlow(readBlockNotificationShade())
    val blockShadeFlow: StateFlow<Boolean> = _blockShadeFlow.asStateFlow()

    private val _blockControlCenterFlow = MutableStateFlow(readBlockControlCenter())
    val blockControlCenterFlow: StateFlow<Boolean> = _blockControlCenterFlow.asStateFlow()

    private val lock = Any()

    fun startSession(durationMinutes: Int, mode: DetoxMode): DetoxSessionSnapshot = synchronized(lock) {
        val durationMs = durationMinutes.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES) * 60_000L
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val bootCount = readBootCount()

        val (dndApplied, previousFilter) = applyDndIfPermitted(mode)
        val displayOverride = if (mode == DetoxMode.TOTAL_VACUUM) {
            DetoxSystemDisplayController.applyStrictMonochromeAndDisableAod(appContext)
        } else {
            DetoxSystemDisplayController.DisplayOverrideSnapshot()
        }

        val snapshot = DetoxSessionSnapshot(
            isActive = true,
            mode = mode,
            totalDurationMs = durationMs,
            accumulatedElapsedMs = 0L,
            bootAnchorElapsedRealtimeMs = nowElapsed,
            lastBootCount = bootCount,
            lastHeartbeatWallMs = nowWall,
            lastHeartbeatElapsedMs = nowElapsed,
            plannedEndWallMs = nowWall + durationMs,
            timeTamperDetected = false,
            bypassAttempts = 0,
            dndApplied = dndApplied,
            previousInterruptionFilter = previousFilter,
            systemDisplayApplied = displayOverride.applied,
            previousAodState = displayOverride.previousAodState,
            previousDaltonizerEnabled = displayOverride.previousDaltonizerEnabled,
            previousDaltonizerMode = displayOverride.previousDaltonizerMode
        )

        writeSnapshotSynchronously(snapshot)
        savePreferredConfig(durationMinutes, mode)
        _lastCompletedFlow.value = null
        _sessionFlow.value = snapshot
        snapshot
    }

    fun readPreferredDurationMinutes(): Int {
        return prefs.getInt(KEY_PREFERRED_DURATION_MIN, DEFAULT_DURATION_MINUTES)
            .coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
    }

    fun readPreferredMode(): DetoxMode {
        val raw = prefs.getString(KEY_PREFERRED_MODE, DetoxMode.PHONE_ONLY.name)
        return runCatching { DetoxMode.valueOf(raw ?: DetoxMode.PHONE_ONLY.name) }
            .getOrDefault(DetoxMode.PHONE_ONLY)
    }

    fun savePreferredConfig(durationMinutes: Int, mode: DetoxMode) {
        prefs.edit {
            putInt(KEY_PREFERRED_DURATION_MIN, durationMinutes.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES))
            putString(KEY_PREFERRED_MODE, mode.name)
        }
    }

    fun readBlockNotificationShade(): Boolean {
        return prefs.getBoolean(KEY_BLOCK_NOTIFICATION_SHADE, false)
    }

    fun setBlockNotificationShade(enabled: Boolean) = synchronized(lock) {
        if (isSessionActive()) return@synchronized
        prefs.edit(commit = true) {
            putBoolean(KEY_BLOCK_NOTIFICATION_SHADE, enabled)
        }
        _blockShadeFlow.value = enabled
    }

    fun readBlockControlCenter(): Boolean {
        return prefs.getBoolean(KEY_BLOCK_CONTROL_CENTER, readBlockNotificationShade())
    }

    fun setBlockControlCenter(enabled: Boolean) = synchronized(lock) {
        if (isSessionActive()) return@synchronized
        prefs.edit(commit = true) {
            putBoolean(KEY_BLOCK_CONTROL_CENTER, enabled)
        }
        _blockControlCenterFlow.value = enabled
    }

    fun isSessionActive(): Boolean = synchronized(lock) {
        val snap = readSnapshotLocked()
        if (!snap.isActive) return false
        return computeRemainingMsInternal(snap) > 0L
    }

    fun getCurrentSnapshot(): DetoxSessionSnapshot = synchronized(lock) {
        val snap = readSnapshotLocked()
        _sessionFlow.value = snap
        snap
    }

    fun computeRemainingMs(): Long = synchronized(lock) {
        val snap = readSnapshotLocked()
        if (!snap.isActive) return 0L
        computeRemainingMsInternal(snap)
    }

    fun reconcileAfterReboot(): DetoxSessionSnapshot = synchronized(lock) {
        val snap = readSnapshotLocked()
        if (!snap.isActive) return snap

        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val currentBootCount = readBootCount()

        val rebootDetected = (currentBootCount != -1 && snap.lastBootCount != -1 && currentBootCount != snap.lastBootCount) ||
            nowElapsed < snap.bootAnchorElapsedRealtimeMs ||
            nowElapsed < snap.lastHeartbeatElapsedMs

        if (!rebootDetected) {
            _sessionFlow.value = snap
            return snap
        }

        val elapsedBeforeReboot = max(0L, snap.lastHeartbeatElapsedMs - snap.bootAnchorElapsedRealtimeMs)
        val rawWallOffDuration = nowWall - snap.lastHeartbeatWallMs

        val remainingBeforeReboot = max(0L, snap.totalDurationMs - (snap.accumulatedElapsedMs + elapsedBeforeReboot))
        val suspiciousClockJump = rawWallOffDuration < 0L || rawWallOffDuration > MAX_OFFLINE_CREDIT_MS
        val creditedOfflineMs = when {
            suspiciousClockJump -> 0L
            else -> min(rawWallOffDuration, remainingBeforeReboot)
        }

        val newAccumulated = snap.accumulatedElapsedMs + elapsedBeforeReboot + creditedOfflineMs
        val newRemaining = max(0L, snap.totalDurationMs - newAccumulated)

        val updated = snap.copy(
            accumulatedElapsedMs = newAccumulated,
            bootAnchorElapsedRealtimeMs = nowElapsed,
            lastBootCount = currentBootCount,
            lastHeartbeatWallMs = nowWall,
            lastHeartbeatElapsedMs = nowElapsed,
            plannedEndWallMs = nowWall + newRemaining,
            timeTamperDetected = snap.timeTamperDetected || suspiciousClockJump
        )

        writeSnapshotSynchronously(updated)
        _sessionFlow.value = updated
        updated
    }

    fun recordHeartbeat(): DetoxSessionSnapshot = synchronized(lock) {
        val snap = readSnapshotLocked()
        if (!snap.isActive) return snap

        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val remaining = computeRemainingMsInternal(snap)

        val updated = snap.copy(
            lastHeartbeatElapsedMs = max(snap.lastHeartbeatElapsedMs, nowElapsed),
            lastHeartbeatWallMs = nowWall,
            plannedEndWallMs = nowWall + remaining
        )
        writeSnapshotSynchronously(updated)
        _sessionFlow.value = updated
        updated
    }

    fun onSystemTimeChanged(): DetoxSessionSnapshot = synchronized(lock) {
        val snap = readSnapshotLocked()
        if (!snap.isActive) return snap

        val nowWall = System.currentTimeMillis()
        val remaining = computeRemainingMsInternal(snap)
        val updated = snap.copy(
            plannedEndWallMs = nowWall + remaining,
            lastHeartbeatWallMs = nowWall,
            timeTamperDetected = true
        )
        writeSnapshotSynchronously(updated)
        _sessionFlow.value = updated
        updated
    }

    fun recordBypassAttempt(): DetoxSessionSnapshot = synchronized(lock) {
        val snap = readSnapshotLocked()
        if (!snap.isActive) return snap

        val updated = snap.copy(bypassAttempts = snap.bypassAttempts + 1)
        writeSnapshotSynchronously(updated)
        _sessionFlow.value = updated
        updated
    }

    fun completeSession(): CompletedSessionSummary? = synchronized(lock) {
        val snap = readSnapshotLocked()
        if (!snap.isActive) return null

        restoreDndIfApplied(snap)
        DetoxSystemDisplayController.restorePreviousSettings(
            context = appContext,
            applied = snap.systemDisplayApplied,
            previousAodState = snap.previousAodState,
            previousDaltonizerEnabled = snap.previousDaltonizerEnabled,
            previousDaltonizerMode = snap.previousDaltonizerMode
        )

        val summary = CompletedSessionSummary(
            durationMs = snap.totalDurationMs,
            mode = snap.mode,
            bypassAttempts = snap.bypassAttempts
        )

        val cleared = DetoxSessionSnapshot(isActive = false, mode = snap.mode)
        writeSnapshotSynchronously(cleared)
        _sessionFlow.value = cleared
        _lastCompletedFlow.value = summary
        summary
    }

    fun clearCompletedSummary() {
        _lastCompletedFlow.value = null
    }

    fun refreshProtectionStatus(): ProtectionStatus {
        val status = readProtectionStatus()
        _protectionFlow.value = status
        return status
    }

    fun disableDeviceAdminIfIdle(): Boolean = synchronized(lock) {
        if (isSessionActive()) return false
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        val adminComponent = ComponentName(appContext, DetoxDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(adminComponent)) {
            runCatching { dpm.removeActiveAdmin(adminComponent) }
        }
        refreshProtectionStatus()
        true
    }

    fun disableAccessibilityIfIdle(): Boolean = synchronized(lock) {
        if (isSessionActive()) return false
        val disabled = DetoxAccessibilityService.disableSelfSafely()
        refreshProtectionStatus()
        disabled
    }

    fun revokeAllProtectionIfIdle(): Boolean = synchronized(lock) {
        if (isSessionActive()) return false
        DetoxMonitorService.cancelAllAlarms(appContext)
        DetoxMonitorService.stop(appContext)
        DetoxAccessibilityService.disableSelfSafely()
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = ComponentName(appContext, DetoxDeviceAdminReceiver::class.java)
        if (dpm?.isAdminActive(adminComponent) == true) {
            runCatching { dpm.removeActiveAdmin(adminComponent) }
        }
        refreshProtectionStatus()
        true
    }

    fun isAllowedForegroundPackage(packageName: String, mode: DetoxMode): Boolean {
        if (packageName == appContext.packageName) return true
        if (packageName == "com.android.systemui") return true
        if (packageName == "com.android.emergency") return true
        if (packageName == "com.android.server.telecom") return true
        if (packageName == "com.android.phone") return true

        if (mode == DetoxMode.PHONE_ONLY) {
            val defaultDialer = runCatching {
                val telecom = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                telecom?.defaultDialerPackage
            }.getOrNull()
            if (!defaultDialer.isNullOrBlank() && packageName == defaultDialer) return true
            if (packageName in KNOWN_DIALER_PACKAGES) return true
        }
        return false
    }

    private fun computeRemainingMsInternal(snap: DetoxSessionSnapshot): Long {
        if (!snap.isActive) return 0L
        val nowElapsed = SystemClock.elapsedRealtime()
        val deltaSinceAnchor = if (nowElapsed >= snap.bootAnchorElapsedRealtimeMs) {
            nowElapsed - snap.bootAnchorElapsedRealtimeMs
        } else {
            // Device rebooted before reconcileAfterReboot ran
            max(0L, snap.lastHeartbeatElapsedMs - snap.bootAnchorElapsedRealtimeMs)
        }
        val totalElapsed = snap.accumulatedElapsedMs + deltaSinceAnchor
        return max(0L, snap.totalDurationMs - totalElapsed)
    }

    private fun readProtectionStatus(): ProtectionStatus {
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = ComponentName(appContext, DetoxDeviceAdminReceiver::class.java)
        val deviceAdminEnabled = runCatching {
            dpm?.isAdminActive(adminComponent) == true
        }.getOrDefault(false)

        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }

        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIgnored = runCatching {
            powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true
        }.getOrDefault(false)

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val dndGranted = runCatching { nm?.isNotificationPolicyAccessGranted == true }.getOrDefault(false)

        val accessibilityEnabled = checkAccessibilityServiceEnabled()

        return ProtectionStatus(
            accessibilityEnabled = accessibilityEnabled,
            deviceAdminEnabled = deviceAdminEnabled,
            notificationsGranted = notificationsGranted,
            batteryOptimizationIgnored = batteryIgnored,
            dndPolicyGranted = dndGranted
        )
    }

    private fun checkAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(appContext, DetoxAccessibilityService::class.java)
        val expectedFlat = expectedComponent.flattenToString()
        val expectedShort = expectedComponent.flattenToShortString()

        val viaSecureSetting = runCatching {
            val enabledServices = Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            enabledServices.split(':').any { entry ->
                entry.equals(expectedFlat, ignoreCase = true) ||
                    entry.equals(expectedShort, ignoreCase = true)
            }
        }.getOrDefault(false)

        if (viaSecureSetting) return true

        return runCatching {
            val am = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            list.any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                serviceInfo.packageName == appContext.packageName &&
                    serviceInfo.name == DetoxAccessibilityService::class.java.name
            }
        }.getOrDefault(false)
    }

    private fun applyDndIfPermitted(mode: DetoxMode): Pair<Boolean, Int> {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return Pair(false, -1)
        if (!nm.isNotificationPolicyAccessGranted) return Pair(false, -1)

        return runCatching {
            val previous = nm.currentInterruptionFilter
            val targetFilter = when (mode) {
                DetoxMode.PHONE_ONLY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                DetoxMode.TOTAL_VACUUM -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            }
            nm.setInterruptionFilter(targetFilter)
            Pair(true, previous)
        }.getOrDefault(Pair(false, -1))
    }

    private fun restoreDndIfApplied(snap: DetoxSessionSnapshot) {
        if (!snap.dndApplied) return
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (!nm.isNotificationPolicyAccessGranted) return
        val safeFilter = when (snap.previousInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> NotificationManager.INTERRUPTION_FILTER_ALL
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            NotificationManager.INTERRUPTION_FILTER_NONE -> NotificationManager.INTERRUPTION_FILTER_NONE
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            else -> return
        }
        runCatching {
            nm.setInterruptionFilter(safeFilter)
        }
    }

    private fun readBootCount(): Int {
        return runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
    }

    private fun readSnapshotLocked(): DetoxSessionSnapshot {
        val isActive = prefs.getBoolean(KEY_IS_ACTIVE, false)
        val modeRaw = prefs.getString(KEY_MODE, DetoxMode.PHONE_ONLY.name)
        val mode = runCatching { DetoxMode.valueOf(modeRaw ?: DetoxMode.PHONE_ONLY.name) }
            .getOrDefault(DetoxMode.PHONE_ONLY)

        return DetoxSessionSnapshot(
            isActive = isActive,
            mode = mode,
            totalDurationMs = prefs.getLong(KEY_TOTAL_DURATION_MS, 0L),
            accumulatedElapsedMs = prefs.getLong(KEY_ACCUMULATED_ELAPSED_MS, 0L),
            bootAnchorElapsedRealtimeMs = prefs.getLong(KEY_BOOT_ANCHOR_ELAPSED_MS, 0L),
            lastBootCount = prefs.getInt(KEY_LAST_BOOT_COUNT, -1),
            lastHeartbeatWallMs = prefs.getLong(KEY_LAST_HEARTBEAT_WALL_MS, 0L),
            lastHeartbeatElapsedMs = prefs.getLong(KEY_LAST_HEARTBEAT_ELAPSED_MS, 0L),
            plannedEndWallMs = prefs.getLong(KEY_PLANNED_END_WALL_MS, 0L),
            timeTamperDetected = prefs.getBoolean(KEY_TIME_TAMPER, false),
            bypassAttempts = prefs.getInt(KEY_BYPASS_ATTEMPTS, 0),
            dndApplied = prefs.getBoolean(KEY_DND_APPLIED, false),
            previousInterruptionFilter = prefs.getInt(KEY_PREV_INTERRUPTION_FILTER, -1),
            systemDisplayApplied = prefs.getBoolean(KEY_SYS_DISPLAY_APPLIED, false),
            previousAodState = prefs.getInt(KEY_PREV_AOD_STATE, -1),
            previousDaltonizerEnabled = prefs.getInt(KEY_PREV_DALTONIZER_ENABLED, -1),
            previousDaltonizerMode = prefs.getInt(KEY_PREV_DALTONIZER_MODE, -1)
        )
    }

    private fun writeSnapshotSynchronously(snap: DetoxSessionSnapshot) {
        prefs.edit(commit = true) {
            putBoolean(KEY_IS_ACTIVE, snap.isActive)
            putString(KEY_MODE, snap.mode.name)
            putLong(KEY_TOTAL_DURATION_MS, snap.totalDurationMs)
            putLong(KEY_ACCUMULATED_ELAPSED_MS, snap.accumulatedElapsedMs)
            putLong(KEY_BOOT_ANCHOR_ELAPSED_MS, snap.bootAnchorElapsedRealtimeMs)
            putInt(KEY_LAST_BOOT_COUNT, snap.lastBootCount)
            putLong(KEY_LAST_HEARTBEAT_WALL_MS, snap.lastHeartbeatWallMs)
            putLong(KEY_LAST_HEARTBEAT_ELAPSED_MS, snap.lastHeartbeatElapsedMs)
            putLong(KEY_PLANNED_END_WALL_MS, snap.plannedEndWallMs)
            putBoolean(KEY_TIME_TAMPER, snap.timeTamperDetected)
            putInt(KEY_BYPASS_ATTEMPTS, snap.bypassAttempts)
            putBoolean(KEY_DND_APPLIED, snap.dndApplied)
            putInt(KEY_PREV_INTERRUPTION_FILTER, snap.previousInterruptionFilter)
            putBoolean(KEY_SYS_DISPLAY_APPLIED, snap.systemDisplayApplied)
            putInt(KEY_PREV_AOD_STATE, snap.previousAodState)
            putInt(KEY_PREV_DALTONIZER_ENABLED, snap.previousDaltonizerEnabled)
            putInt(KEY_PREV_DALTONIZER_MODE, snap.previousDaltonizerMode)
        }
    }

    companion object {
        const val MIN_DURATION_MINUTES = 5
        const val MAX_DURATION_MINUTES = 7 * 24 * 60 // 7 days (10,080 min)
        const val DEFAULT_DURATION_MINUTES = 60
        const val LONG_DURATION_WARNING_MINUTES = 12 * 60 // > 12 hours
        const val EXTREME_DURATION_WARNING_MINUTES = 24 * 60 // > 1 day

        private const val PREFS_NAME = "tishina_detox_vault_v1"
        private const val MAX_OFFLINE_CREDIT_MS = 8L * 24L * 60L * 60L * 1000L

        private const val KEY_IS_ACTIVE = "is_active"
        private const val KEY_MODE = "mode"
        private const val KEY_TOTAL_DURATION_MS = "total_duration_ms"
        private const val KEY_ACCUMULATED_ELAPSED_MS = "accumulated_elapsed_ms"
        private const val KEY_BOOT_ANCHOR_ELAPSED_MS = "boot_anchor_elapsed_ms"
        private const val KEY_LAST_BOOT_COUNT = "last_boot_count"
        private const val KEY_LAST_HEARTBEAT_WALL_MS = "last_heartbeat_wall_ms"
        private const val KEY_LAST_HEARTBEAT_ELAPSED_MS = "last_heartbeat_elapsed_ms"
        private const val KEY_PLANNED_END_WALL_MS = "planned_end_wall_ms"
        private const val KEY_TIME_TAMPER = "time_tamper"
        private const val KEY_BYPASS_ATTEMPTS = "bypass_attempts"
        private const val KEY_DND_APPLIED = "dnd_applied"
        private const val KEY_PREV_INTERRUPTION_FILTER = "prev_interruption_filter"
        private const val KEY_SYS_DISPLAY_APPLIED = "sys_display_applied"
        private const val KEY_PREV_AOD_STATE = "prev_aod_state"
        private const val KEY_PREV_DALTONIZER_ENABLED = "prev_daltonizer_enabled"
        private const val KEY_PREV_DALTONIZER_MODE = "prev_daltonizer_mode"
        private const val KEY_PREFERRED_DURATION_MIN = "pref_duration_min"
        private const val KEY_PREFERRED_MODE = "pref_mode"
        private const val KEY_BLOCK_NOTIFICATION_SHADE = "pref_block_notification_shade"
        private const val KEY_BLOCK_CONTROL_CENTER = "pref_block_control_center"

        private val KNOWN_DIALER_PACKAGES = setOf(
            "com.google.android.dialer",
            "com.android.dialer",
            "com.android.incallui",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.miui.phone",
            "com.android.contacts"
        )

        @Volatile
        private var instance: DetoxSessionRepository? = null

        fun getInstance(context: Context): DetoxSessionRepository {
            return instance ?: synchronized(this) {
                instance ?: DetoxSessionRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
