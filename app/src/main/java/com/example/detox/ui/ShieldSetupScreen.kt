package com.example.detox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.detox.data.ProtectionStatus
import kotlinx.coroutines.delay

private enum class RevocationGateStep {
    NONE,
    WARNING_1,
    WARNING_2,
    TYPE_CONFIRM,
    PANEL
}

private const val REQUIRED_SECRET_TAPS = 7

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShieldSetupScreen(
    protectionStatus: ProtectionStatus,
    blockNotificationShade: Boolean,
    blockControlCenter: Boolean,
    onBack: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onEnableDeviceAdmin: () -> Unit,
    onEnableNotifications: () -> Unit,
    onEnableBatteryUnrestricted: () -> Unit,
    onEnableDndAccess: () -> Unit,
    onToggleBlockNotificationShade: (Boolean) -> Unit,
    onToggleBlockControlCenter: (Boolean) -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onDisableAccessibility: () -> Unit,
    onDisableDeviceAdmin: () -> Unit,
    onRevokeAllProtection: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit
) {
    BackHandler(onBack = onBack)

    var secretTapCount by remember { mutableIntStateOf(0) }
    var gateStep by remember { mutableStateOf(RevocationGateStep.NONE) }

    // Auto-reset tap counter if user pauses tapping for 4 seconds
    LaunchedEffect(secretTapCount) {
        if (secretTapCount in 1 until REQUIRED_SECRET_TAPS) {
            delay(4_000L)
            secretTapCount = 0
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.shield_screen_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("shield_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.shield_back_cd)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.shield_screen_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                ShieldStepCard(
                    icon = Icons.Rounded.Security,
                    title = stringResource(R.string.shield_step_accessibility_title),
                    description = stringResource(R.string.shield_step_accessibility_desc),
                    isRequired = true,
                    isEnabled = protectionStatus.accessibilityEnabled,
                    onEnableClick = onEnableAccessibility,
                    testTagName = "shield_step_accessibility_button"
                )

                ShieldStepCard(
                    icon = Icons.Rounded.AdminPanelSettings,
                    title = stringResource(R.string.shield_step_admin_title),
                    description = stringResource(R.string.shield_step_admin_desc),
                    isRequired = true,
                    isEnabled = protectionStatus.deviceAdminEnabled,
                    onEnableClick = onEnableDeviceAdmin,
                    testTagName = "shield_step_admin_button"
                )

                ShieldStepCard(
                    icon = Icons.Rounded.NotificationsActive,
                    title = stringResource(R.string.shield_step_notifications_title),
                    description = stringResource(R.string.shield_step_notifications_desc),
                    isRequired = true,
                    isEnabled = protectionStatus.notificationsGranted,
                    onEnableClick = onEnableNotifications,
                    testTagName = "shield_step_notifications_button"
                )

                ShieldStepCard(
                    icon = Icons.Rounded.BatterySaver,
                    title = stringResource(R.string.shield_step_battery_title),
                    description = stringResource(R.string.shield_step_battery_desc),
                    isRequired = false,
                    isEnabled = protectionStatus.batteryOptimizationIgnored,
                    onEnableClick = onEnableBatteryUnrestricted,
                    testTagName = "shield_step_battery_button"
                )

                ShieldStepCard(
                    icon = Icons.Rounded.DoNotDisturbOn,
                    title = stringResource(R.string.shield_step_dnd_title),
                    description = stringResource(R.string.shield_step_dnd_desc),
                    isRequired = false,
                    isEnabled = protectionStatus.dndPolicyGranted,
                    onEnableClick = onEnableDndAccess,
                    testTagName = "shield_step_dnd_button"
                )

                // Adjustable access to Notification Shade and Control Center (for PHONE_ONLY; always locked in TOTAL_VACUUM)
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.shield_panels_card_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Text(
                            text = stringResource(R.string.shield_panels_card_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        ToggleOptionRow(
                            title = stringResource(R.string.shield_option_block_notifications_title),
                            description = stringResource(R.string.shield_option_block_notifications_desc),
                            checked = blockNotificationShade,
                            onCheckedChange = onToggleBlockNotificationShade,
                            testTagName = "toggle_block_shade_switch"
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        ToggleOptionRow(
                            title = stringResource(R.string.shield_option_block_control_center_title),
                            description = stringResource(R.string.shield_option_block_control_center_desc),
                            checked = blockControlCenter,
                            onCheckedChange = onToggleBlockControlCenter,
                            testTagName = "toggle_block_control_center_switch"
                        )
                    }
                }

                // Screen pinning settings card
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.shield_pinning_tip_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = stringResource(R.string.shield_pinning_tip_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(
                            onClick = onOpenSecuritySettings,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.testTag("open_security_settings_button")
                        ) {
                            Text(text = stringResource(R.string.shield_pinning_open_settings))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Stealth version footer: 7 rapid taps unlock the revocation confirmation gate
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val noRippleInteraction = remember { MutableInteractionSource() }
                    Text(
                        text = stringResource(R.string.version_footer_text, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f),
                        modifier = Modifier
                            .clickable(
                                interactionSource = noRippleInteraction,
                                indication = null
                            ) {
                                val next = secretTapCount + 1
                                if (next >= REQUIRED_SECRET_TAPS) {
                                    secretTapCount = 0
                                    gateStep = RevocationGateStep.WARNING_1
                                } else {
                                    secretTapCount = next
                                }
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp)
                            .testTag("hidden_version_footer")
                    )

                    if (secretTapCount in 4 until REQUIRED_SECRET_TAPS) {
                        Text(
                            text = stringResource(
                                R.string.version_taps_remaining,
                                REQUIRED_SECRET_TAPS - secretTapCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    when (gateStep) {
        RevocationGateStep.NONE -> Unit

        RevocationGateStep.WARNING_1 -> TimedWarningDialog(
            title = stringResource(R.string.revoke_warn1_title),
            body = stringResource(R.string.revoke_warn1_body),
            waitSeconds = 5,
            confirmReadyLabel = stringResource(R.string.revoke_warn1_confirm),
            confirmWaitLabelProvider = { sec -> stringResource(R.string.revoke_warn1_confirm_wait, sec) },
            dismissLabel = stringResource(R.string.confirm_cancel),
            onDismiss = { gateStep = RevocationGateStep.NONE },
            onProceed = { gateStep = RevocationGateStep.WARNING_2 }
        )

        RevocationGateStep.WARNING_2 -> TimedWarningDialog(
            title = stringResource(R.string.revoke_warn2_title),
            body = stringResource(R.string.revoke_warn2_body),
            waitSeconds = 10,
            confirmReadyLabel = stringResource(R.string.revoke_warn2_confirm),
            confirmWaitLabelProvider = { sec -> stringResource(R.string.revoke_warn2_confirm_wait, sec) },
            dismissLabel = stringResource(R.string.revoke_keep_protection),
            onDismiss = { gateStep = RevocationGateStep.NONE },
            onProceed = { gateStep = RevocationGateStep.TYPE_CONFIRM }
        )

        RevocationGateStep.TYPE_CONFIRM -> KeywordConfirmationDialog(
            onDismiss = { gateStep = RevocationGateStep.NONE },
            onConfirmed = { gateStep = RevocationGateStep.PANEL }
        )

        RevocationGateStep.PANEL -> RevocationPanelDialog(
            protectionStatus = protectionStatus,
            onDisableAccessibility = onDisableAccessibility,
            onDisableDeviceAdmin = onDisableDeviceAdmin,
            onRevokeAllProtection = onRevokeAllProtection,
            onOpenAppDetailsSettings = onOpenAppDetailsSettings,
            onClose = { gateStep = RevocationGateStep.NONE }
        )
    }
}

@Composable
private fun TimedWarningDialog(
    title: String,
    body: String,
    waitSeconds: Int,
    confirmReadyLabel: String,
    confirmWaitLabelProvider: @Composable (Int) -> String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    onProceed: () -> Unit
) {
    var secondsLeft by remember(title) { mutableIntStateOf(waitSeconds) }

    LaunchedEffect(title) {
        while (secondsLeft > 0) {
            delay(1_000L)
            secondsLeft--
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(
                onClick = onProceed,
                enabled = secondsLeft == 0,
                modifier = Modifier.testTag("revoke_warning_proceed_button")
            ) {
                Text(
                    text = if (secondsLeft > 0) {
                        confirmWaitLabelProvider(secondsLeft)
                    } else {
                        confirmReadyLabel
                    }
                )
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("revoke_warning_cancel_button")
            ) {
                Text(text = dismissLabel)
            }
        }
    )
}

@Composable
private fun KeywordConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit
) {
    var typedText by remember { mutableStateOf("") }
    val requiredKeyword = stringResource(R.string.revoke_warn3_keyword)
    val isMatch = typedText.trim().equals(requiredKeyword, ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.revoke_warn3_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.revoke_warn3_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = typedText,
                    onValueChange = { typedText = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.revoke_warn3_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("revoke_keyword_input")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirmed,
                enabled = isMatch,
                modifier = Modifier.testTag("revoke_keyword_confirm_button")
            ) {
                Text(text = stringResource(R.string.revoke_warn3_open))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(text = stringResource(R.string.revoke_keep_protection))
            }
        }
    )
}

@Composable
private fun RevocationPanelDialog(
    protectionStatus: ProtectionStatus,
    onDisableAccessibility: () -> Unit,
    onDisableDeviceAdmin: () -> Unit,
    onRevokeAllProtection: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                text = stringResource(R.string.revoke_panel_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.revoke_panel_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OutlinedButton(
                    onClick = onDisableAccessibility,
                    enabled = protectionStatus.accessibilityEnabled,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("revoke_disable_accessibility_button")
                ) {
                    Text(
                        text = if (protectionStatus.accessibilityEnabled) {
                            stringResource(R.string.revoke_disable_accessibility)
                        } else {
                            "${stringResource(R.string.shield_step_accessibility_title)} · ${stringResource(R.string.revoke_done_badge)}"
                        }
                    )
                }

                OutlinedButton(
                    onClick = onDisableDeviceAdmin,
                    enabled = protectionStatus.deviceAdminEnabled,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("revoke_disable_admin_button")
                ) {
                    Text(
                        text = if (protectionStatus.deviceAdminEnabled) {
                            stringResource(R.string.revoke_disable_admin)
                        } else {
                            "${stringResource(R.string.shield_step_admin_title)} · ${stringResource(R.string.revoke_done_badge)}"
                        }
                    )
                }

                if (protectionStatus.accessibilityEnabled || protectionStatus.deviceAdminEnabled) {
                    Button(
                        onClick = onRevokeAllProtection,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("revoke_disable_all_button")
                    ) {
                        Text(text = stringResource(R.string.revoke_disable_all))
                    }
                }

                FilledTonalButton(
                    onClick = onOpenAppDetailsSettings,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("revoke_open_app_details_button")
                ) {
                    Text(text = stringResource(R.string.revoke_open_app_settings))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(text = stringResource(R.string.revoke_close))
            }
        }
    )
}

@Composable
private fun ShieldStepCard(
    icon: ImageVector,
    title: String,
    description: String,
    isRequired: Boolean,
    isEnabled: Boolean,
    onEnableClick: () -> Unit,
    testTagName: String
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isEnabled) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isEnabled) Icons.Rounded.CheckCircle else icon,
                        contentDescription = title,
                        tint = if (isEnabled) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            when {
                                isEnabled -> R.string.shield_enabled_badge
                                isRequired -> R.string.shield_required_badge
                                else -> R.string.shield_optional_badge
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            isEnabled -> MaterialTheme.colorScheme.secondary
                            isRequired -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                if (!isEnabled) {
                    FilledTonalButton(
                        onClick = onEnableClick,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.testTag(testTagName)
                    ) {
                        Text(text = stringResource(R.string.shield_enable_action))
                    }
                }
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ToggleOptionRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTagName: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTagName)
        )
    }
}
