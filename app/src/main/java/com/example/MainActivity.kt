package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.net.toUri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.detox.admin.DetoxDeviceAdminReceiver
import com.example.detox.data.DetoxMode
import com.example.detox.ui.ActiveLockScreen
import com.example.detox.ui.DetoxScreen
import com.example.detox.ui.DetoxUiState
import com.example.detox.ui.DetoxViewModel
import com.example.detox.ui.HomeSetupScreen
import com.example.detox.ui.SessionCompletedScreen
import com.example.detox.ui.ShieldSetupScreen
import android.app.KeyguardManager
import android.os.SystemClock
import com.example.detox.service.DetoxAccessibilityService
import com.example.ui.theme.TishinaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DetoxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureLockScreenAndOverlayDefense()

        setContent {
            TishinaTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) {
                    viewModel.onHostResumed(isCurrentlyPinned())
                }

                // Automatically release Screen Pinning as soon as the session finishes
                LaunchedEffect(uiState.screen) {
                    if (uiState.screen != DetoxScreen.ActiveLock && isCurrentlyPinned()) {
                        runCatching { stopLockTask() }
                        viewModel.updatePinnedState(false)
                    }
                }

                TishinaRootContent(
                    uiState = uiState,
                    onSelectDuration = viewModel::selectDurationMinutes,
                    onAdjustDuration = viewModel::adjustDurationBy,
                    onSelectMode = viewModel::selectMode,
                    onOpenShieldSetup = viewModel::openShieldSetup,
                    onCloseShieldSetup = viewModel::closeShieldSetup,
                    onStartClick = viewModel::requestStartConfirmation,
                    onDismissConfirmSheet = viewModel::dismissConfirmSheet,
                    onConfirmLock = {
                        viewModel.confirmAndStartSession()
                        requestScreenPinning()
                    },
                    onRequestPinScreen = { requestScreenPinning() },
                    onOpenDialer = { launchDialer() },
                    onReturnHome = viewModel::dismissCompletedSummary,
                    onEnableAccessibility = { launchSafeSettingsIntent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                    onEnableDeviceAdmin = { launchDeviceAdminEnrollment() },
                    onEnableNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openNotificationSettings()
                        }
                    },
                    onEnableBatteryUnrestricted = { launchBatteryOptimizationPrompt() },
                    onEnableDndAccess = { launchSafeSettingsIntent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
                    onToggleBlockNotificationShade = viewModel::toggleBlockNotificationShade,
                    onOpenSecuritySettings = { launchSafeSettingsIntent(Settings.ACTION_SECURITY_SETTINGS) },
                    onDisableAccessibility = {
                        val disabledDirectly = viewModel.disableAccessibilityIfIdle()
                        if (!disabledDirectly) {
                            launchSafeSettingsIntent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        }
                    },
                    onDisableDeviceAdmin = viewModel::disableDeviceAdminIfIdle,
                    onRevokeAllProtection = viewModel::revokeAllProtectionIfIdle,
                    onOpenAppDetailsSettings = { launchAppDetailsSettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onHostResumed(isCurrentlyPinned())
        isLockWindowFocused = hasWindowFocus() && viewModel.uiState.value.screen == DetoxScreen.ActiveLock
    }

    override fun onPause() {
        isLockWindowFocused = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val inActiveLock = viewModel.uiState.value.screen == DetoxScreen.ActiveLock
        isLockWindowFocused = hasFocus && inActiveLock
        if (!hasFocus && inActiveLock) {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val locked = km?.isKeyguardLocked == true
            if (!locked && !isWithinPinningGrace() && !isWithinDialerGrace()) {
                DetoxAccessibilityService.onLockWindowLostFocus()
            }
        }
    }

    private fun configureLockScreenAndOverlayDefense() {
        // Keep native OS Keyguard visible when screen is locked so OS emergency call stays reachable.
        // Prevent third-party floating overlays from drawing over or hijacking touches on our UI.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }
        window.decorView.filterTouchesWhenObscured = true
    }

    private fun isCurrentlyPinned(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun requestScreenPinning() {
        pinningGraceUntilElapsedMs = SystemClock.elapsedRealtime() + 6_000L
        runCatching {
            if (!isCurrentlyPinned()) {
                startLockTask()
            }
        }
        viewModel.updatePinnedState(isCurrentlyPinned())
    }

    private fun launchDialer() {
        dialerGraceUntilElapsedMs = SystemClock.elapsedRealtime() + 8_000L
        if (isCurrentlyPinned()) {
            runCatching { stopLockTask() }
            viewModel.updatePinnedState(false)
        }
        val intent = Intent(Intent.ACTION_DIAL, "tel:".toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    private fun launchDeviceAdminEnrollment() {
        val adminComponent = ComponentName(this, DetoxDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_prompt_explanation)
            )
        }
        runCatching { startActivity(intent) }
    }

    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            runCatching { startActivity(intent) }.onFailure { launchAppDetailsSettings() }
        } else {
            launchAppDetailsSettings()
        }
    }

    @SuppressLint("BatteryLife")
    private fun launchBatteryOptimizationPrompt() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:$packageName".toUri()
        }
        val launched = runCatching { startActivity(intent) }.isSuccess
        if (!launched) {
            launchSafeSettingsIntent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }

    private fun launchAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$packageName".toUri()
        }
        runCatching { startActivity(intent) }
    }

    private fun launchSafeSettingsIntent(action: String) {
        runCatching {
            startActivity(Intent(action))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    companion object {
        @Volatile
        var isLockWindowFocused: Boolean = false
            private set

        @Volatile
        private var pinningGraceUntilElapsedMs: Long = 0L

        @Volatile
        private var dialerGraceUntilElapsedMs: Long = 0L

        fun isWithinPinningGrace(): Boolean =
            SystemClock.elapsedRealtime() <= pinningGraceUntilElapsedMs

        fun isWithinDialerGrace(): Boolean =
            SystemClock.elapsedRealtime() <= dialerGraceUntilElapsedMs
    }
}

@Composable
private fun TishinaRootContent(
    uiState: DetoxUiState,
    onSelectDuration: (Int) -> Unit,
    onAdjustDuration: (Int) -> Unit,
    onSelectMode: (DetoxMode) -> Unit,
    onOpenShieldSetup: () -> Unit,
    onCloseShieldSetup: () -> Unit,
    onStartClick: () -> Unit,
    onDismissConfirmSheet: () -> Unit,
    onConfirmLock: () -> Unit,
    onRequestPinScreen: () -> Unit,
    onOpenDialer: () -> Unit,
    onReturnHome: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onEnableDeviceAdmin: () -> Unit,
    onEnableNotifications: () -> Unit,
    onEnableBatteryUnrestricted: () -> Unit,
    onEnableDndAccess: () -> Unit,
    onToggleBlockNotificationShade: (Boolean) -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onDisableAccessibility: () -> Unit,
    onDisableDeviceAdmin: () -> Unit,
    onRevokeAllProtection: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit
) {
    Crossfade(
        targetState = uiState.screen,
        animationSpec = tween(durationMillis = 220),
        label = "screen_crossfade"
    ) { screen ->
        when (screen) {
            DetoxScreen.Home -> HomeSetupScreen(
                uiState = uiState,
                onSelectDuration = onSelectDuration,
                onAdjustDuration = onAdjustDuration,
                onSelectMode = onSelectMode,
                onOpenShieldSetup = onOpenShieldSetup,
                onStartClick = onStartClick,
                onDismissConfirmSheet = onDismissConfirmSheet,
                onConfirmLock = onConfirmLock
            )

            DetoxScreen.ShieldSetup -> ShieldSetupScreen(
                protectionStatus = uiState.protectionStatus,
                blockNotificationShade = uiState.blockNotificationShade,
                onBack = onCloseShieldSetup,
                onEnableAccessibility = onEnableAccessibility,
                onEnableDeviceAdmin = onEnableDeviceAdmin,
                onEnableNotifications = onEnableNotifications,
                onEnableBatteryUnrestricted = onEnableBatteryUnrestricted,
                onEnableDndAccess = onEnableDndAccess,
                onToggleBlockNotificationShade = onToggleBlockNotificationShade,
                onOpenSecuritySettings = onOpenSecuritySettings,
                onDisableAccessibility = onDisableAccessibility,
                onDisableDeviceAdmin = onDisableDeviceAdmin,
                onRevokeAllProtection = onRevokeAllProtection,
                onOpenAppDetailsSettings = onOpenAppDetailsSettings
            )

            DetoxScreen.ActiveLock -> ActiveLockScreen(
                uiState = uiState,
                onRequestPinScreen = onRequestPinScreen,
                onOpenDialer = onOpenDialer
            )

            DetoxScreen.Completed -> {
                val summary = uiState.completedSummary
                if (summary != null) {
                    SessionCompletedScreen(
                        summary = summary,
                        onReturnHome = onReturnHome
                    )
                } else {
                    HomeSetupScreen(
                        uiState = uiState,
                        onSelectDuration = onSelectDuration,
                        onAdjustDuration = onAdjustDuration,
                        onSelectMode = onSelectMode,
                        onOpenShieldSetup = onOpenShieldSetup,
                        onStartClick = onStartClick,
                        onDismissConfirmSheet = onDismissConfirmSheet,
                        onConfirmLock = onConfirmLock
                    )
                }
            }
        }
    }
}
