package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
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
                        val mode = uiState.selectedMode
                        viewModel.confirmAndStartSession()
                        if (mode == DetoxMode.TOTAL_VACUUM) {
                            requestScreenPinning()
                        }
                    },
                    onRequestPinScreen = { requestScreenPinning() },
                    onOpenDialer = { launchDialer(emergencyOnly = false) },
                    onOpenEmergencyDialer = { launchDialer(emergencyOnly = true) },
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
    }

    private fun configureLockScreenAndOverlayDefense() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Prevent third-party floating overlays from drawing over or hijacking touches on our UI
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
        runCatching {
            if (!isCurrentlyPinned()) {
                startLockTask()
            }
        }
        viewModel.updatePinnedState(isCurrentlyPinned())
    }

    private fun launchDialer(emergencyOnly: Boolean) {
        if (isCurrentlyPinned()) {
            runCatching { stopLockTask() }
            viewModel.updatePinnedState(false)
        }
        val uri = if (emergencyOnly) Uri.parse("tel:112") else Uri.parse("tel:")
        val intent = Intent(Intent.ACTION_DIAL, uri).apply {
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
        val intent = Intent().apply {
            action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        runCatching { startActivity(intent) }
    }

    @SuppressLint("BatteryLife")
    private fun launchBatteryOptimizationPrompt() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        val launched = runCatching { startActivity(intent) }.isSuccess
        if (!launched) {
            launchSafeSettingsIntent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }

    private fun launchAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
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
    onOpenEmergencyDialer: () -> Unit,
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
                onOpenDialer = onOpenDialer,
                onOpenEmergencyDialer = onOpenEmergencyDialer
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
