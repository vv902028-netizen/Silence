package com.example.detox.data

enum class DetoxMode {
    PHONE_ONLY,
    TOTAL_VACUUM
}

data class DetoxSessionSnapshot(
    val isActive: Boolean = false,
    val mode: DetoxMode = DetoxMode.PHONE_ONLY,
    val totalDurationMs: Long = 0L,
    val accumulatedElapsedMs: Long = 0L,
    val bootAnchorElapsedRealtimeMs: Long = 0L,
    val lastBootCount: Int = -1,
    val lastHeartbeatWallMs: Long = 0L,
    val lastHeartbeatElapsedMs: Long = 0L,
    val plannedEndWallMs: Long = 0L,
    val timeTamperDetected: Boolean = false,
    val bypassAttempts: Int = 0,
    val dndApplied: Boolean = false,
    val previousInterruptionFilter: Int = -1
)

data class ProtectionStatus(
    val accessibilityEnabled: Boolean = false,
    val deviceAdminEnabled: Boolean = false,
    val notificationsGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val usageStatsGranted: Boolean = false,
    val dndPolicyGranted: Boolean = false
) {
    val requiredReady: Boolean
        get() = accessibilityEnabled && deviceAdminEnabled && notificationsGranted

    val missingRequiredCount: Int
        get() = listOf(accessibilityEnabled, deviceAdminEnabled, notificationsGranted).count { !it }

    val allRecommendedReady: Boolean
        get() = accessibilityEnabled &&
            deviceAdminEnabled &&
            notificationsGranted &&
            batteryOptimizationIgnored
}

data class CompletedSessionSummary(
    val durationMs: Long,
    val mode: DetoxMode,
    val bypassAttempts: Int
)
