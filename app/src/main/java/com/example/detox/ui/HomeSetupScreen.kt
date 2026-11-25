package com.example.detox.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.detox.data.DetoxMode
import com.example.detox.data.DetoxSessionRepository
import com.example.detox.data.ProtectionStatus
import com.example.ui.theme.TimerSetupTextStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val PRESET_MINUTES = listOf(30, 60, 240, 720, 1440, 4320, 10080)

/**
 * Piecewise slider mapping across [5 min .. 7 days]:
 *   0.00..0.35 -> 5m..120m (5m steps)
 *   0.35..0.60 -> 120m..720m (30m steps)
 *   0.60..0.75 -> 720m..1440m (60m steps)
 *   0.75..1.00 -> 1440m..10080m (360m / 6h steps)
 */
private fun durationToSliderFraction(minutes: Int): Float {
    val m = minutes.coerceIn(
        DetoxSessionRepository.MIN_DURATION_MINUTES,
        DetoxSessionRepository.MAX_DURATION_MINUTES
    ).toFloat()
    return when {
        m <= 120f -> ((m - 5f) / 115f) * 0.35f
        m <= 720f -> 0.35f + ((m - 120f) / 600f) * 0.25f
        m <= 1440f -> 0.60f + ((m - 720f) / 720f) * 0.15f
        else -> 0.75f + ((m - 1440f) / (10080f - 1440f)) * 0.25f
    }.coerceIn(0f, 1f)
}

private fun sliderFractionToDuration(fraction: Float): Int {
    val f = fraction.coerceIn(0f, 1f)
    return when {
        f <= 0.35f -> {
            val raw = 5f + (f / 0.35f) * 115f
            ((raw / 5f).roundToInt() * 5).coerceIn(5, 120)
        }
        f <= 0.60f -> {
            val raw = 120f + ((f - 0.35f) / 0.25f) * 600f
            ((raw / 30f).roundToInt() * 30).coerceIn(120, 720)
        }
        f <= 0.75f -> {
            val raw = 720f + ((f - 0.60f) / 0.15f) * 720f
            ((raw / 60f).roundToInt() * 60).coerceIn(720, 1440)
        }
        else -> {
            val raw = 1440f + ((f - 0.75f) / 0.25f) * (10080f - 1440f)
            ((raw / 360f).roundToInt() * 360).coerceIn(1440, DetoxSessionRepository.MAX_DURATION_MINUTES)
        }
    }
}

private fun computeSmartStepDelta(currentMinutes: Int, increasing: Boolean): Int {
    return if (increasing) {
        when {
            currentMinutes < 120 -> 5
            currentMinutes < 720 -> 30
            currentMinutes < 1440 -> 60
            else -> 360
        }
    } else {
        when {
            currentMinutes <= 120 -> -5
            currentMinutes <= 720 -> -30
            currentMinutes <= 1440 -> -60
            else -> -360
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSetupScreen(
    uiState: DetoxUiState,
    onSelectDuration: (Int) -> Unit,
    onAdjustDuration: (Int) -> Unit,
    onSelectMode: (DetoxMode) -> Unit,
    onOpenShieldSetup: () -> Unit,
    onStartClick: () -> Unit,
    onDismissConfirmSheet: () -> Unit,
    onConfirmLock: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            HomeBottomStartBar(
                protectionStatus = uiState.protectionStatus,
                onStartClick = onStartClick
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
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                HomeTopHeader(
                    protectionStatus = uiState.protectionStatus,
                    onShieldClick = onOpenShieldSetup
                )

                TimerSelectorCard(
                    selectedMinutes = uiState.selectedDurationMinutes,
                    estimatedEndWallMs = uiState.estimatedEndWallMs,
                    onSelectDuration = onSelectDuration,
                    onAdjustDuration = onAdjustDuration
                )

                ModeSelectionSection(
                    selectedMode = uiState.selectedMode,
                    onSelectMode = onSelectMode
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    if (uiState.showConfirmSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismissConfirmSheet,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            ConfirmLockSheetContent(
                durationMinutes = uiState.selectedDurationMinutes,
                estimatedEndWallMs = uiState.estimatedEndWallMs,
                selectedMode = uiState.selectedMode,
                onDismiss = onDismissConfirmSheet,
                onConfirmLock = onConfirmLock
            )
        }
    }
}

@Composable
private fun HomeTopHeader(
    protectionStatus: ProtectionStatus,
    onShieldClick: () -> Unit
) {
    val isReady = protectionStatus.requiredReady
    val containerColor = if (isReady) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (isReady) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Surface(
            onClick = onShieldClick,
            shape = RoundedCornerShape(24.dp),
            color = containerColor,
            contentColor = contentColor,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("shield_status_pill")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isReady) Icons.Rounded.VerifiedUser else Icons.Rounded.GppMaybe,
                    contentDescription = stringResource(
                        if (isReady) R.string.shield_status_ready else R.string.shield_status_partial
                    ),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(
                        if (isReady) R.string.shield_status_ready else R.string.shield_status_partial
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerSelectorCard(
    selectedMinutes: Int,
    estimatedEndWallMs: Long,
    onSelectDuration: (Int) -> Unit,
    onAdjustDuration: (Int) -> Unit
) {
    val context = LocalContext.current
    val endClockText = TimeFormatter.formatClockTime(estimatedEndWallMs)
    val isMultiDay = selectedMinutes >= 1440
    val isOver12h = selectedMinutes > DetoxSessionRepository.LONG_DURATION_WARNING_MINUTES
    val isOver24h = selectedMinutes > DetoxSessionRepository.EXTREME_DURATION_WARNING_MINUTES

    val setupTextStyle = if (isMultiDay) {
        TimerSetupTextStyle.copy(fontSize = 34.sp, lineHeight = 40.sp)
    } else {
        TimerSetupTextStyle
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.timer_card_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalIconButton(
                    onClick = { onAdjustDuration(computeSmartStepDelta(selectedMinutes, increasing = false)) },
                    enabled = selectedMinutes > DetoxSessionRepository.MIN_DURATION_MINUTES,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("duration_minus_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Remove,
                        contentDescription = stringResource(R.string.timer_minus_step)
                    )
                }

                Text(
                    text = TimeFormatter.formatSetupDuration(selectedMinutes),
                    style = setupTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.testTag("selected_duration_text")
                )

                FilledTonalIconButton(
                    onClick = { onAdjustDuration(computeSmartStepDelta(selectedMinutes, increasing = true)) },
                    enabled = selectedMinutes < DetoxSessionRepository.MAX_DURATION_MINUTES,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("duration_plus_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.timer_plus_step)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.timer_ends_at, endClockText),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Slider(
                value = durationToSliderFraction(selectedMinutes),
                onValueChange = { fraction ->
                    onSelectDuration(sliderFractionToDuration(fraction))
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = if (isOver24h) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    activeTrackColor = if (isOver24h) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("duration_slider")
            )

            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PRESET_MINUTES.forEach { preset ->
                    val isSelected = selectedMinutes == preset
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectDuration(preset) },
                        label = {
                            Text(
                                text = TimeFormatter.formatDurationHuman(context, preset),
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = MaterialTheme.colorScheme.outlineVariant,
                            selectedBorderColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.testTag("preset_chip_$preset")
                    )
                }
            }

            if (isOver12h) {
                Spacer(modifier = Modifier.height(14.dp))
                DurationWarningBanner(
                    selectedMinutes = selectedMinutes,
                    isOver24h = isOver24h
                )
            }
        }
    }
}

@Composable
private fun DurationWarningBanner(
    selectedMinutes: Int,
    isOver24h: Boolean
) {
    val context = LocalContext.current
    val humanDuration = TimeFormatter.formatDurationHuman(context, selectedMinutes)
    val containerColor = if (isOver24h) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (isOver24h) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (isOver24h) "duration_warning_24h" else "duration_warning_12h")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp)
            )
            Text(
                text = if (isOver24h) {
                    stringResource(R.string.duration_warn_24h, humanDuration)
                } else {
                    stringResource(R.string.duration_warn_12h)
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ModeSelectionSection(
    selectedMode: DetoxMode,
    onSelectMode: (DetoxMode) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.mode_section_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        ModeOptionCard(
            title = stringResource(R.string.mode_phone_title),
            subtitle = stringResource(R.string.mode_phone_subtitle),
            icon = Icons.Rounded.Call,
            selected = selectedMode == DetoxMode.PHONE_ONLY,
            useTertiaryAccent = false,
            onClick = { onSelectMode(DetoxMode.PHONE_ONLY) },
            testTagName = "mode_phone_only_card"
        )

        ModeOptionCard(
            title = stringResource(R.string.mode_vacuum_title),
            subtitle = stringResource(R.string.mode_vacuum_subtitle),
            icon = Icons.Rounded.DoNotDisturbOn,
            selected = selectedMode == DetoxMode.TOTAL_VACUUM,
            useTertiaryAccent = true,
            onClick = { onSelectMode(DetoxMode.TOTAL_VACUUM) },
            testTagName = "mode_total_vacuum_card"
        )
    }
}

@Composable
private fun ModeOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    useTertiaryAccent: Boolean,
    onClick: () -> Unit,
    testTagName: String
) {
    val activeContainer = if (useTertiaryAccent) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val activeAccent = if (useTertiaryAccent) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val activeOnContainer = if (useTertiaryAccent) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    val containerColor by animateColorAsState(
        targetValue = if (selected) activeContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = spring(),
        label = "mode_card_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) activeAccent else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = spring(),
        label = "mode_card_border"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTagName)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) activeAccent.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (selected) activeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) activeOnContainer else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) activeOnContainer.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = activeAccent,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun HomeBottomStartBar(
    protectionStatus: ProtectionStatus,
    onStartClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = onStartClick,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .height(56.dp)
                    .testTag("start_silence_button")
            ) {
                Icon(
                    imageVector = if (protectionStatus.requiredReady) Icons.Rounded.Lock else Icons.Rounded.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        if (protectionStatus.requiredReady) {
                            R.string.start_silence_button
                        } else {
                            R.string.setup_before_start_button
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun ConfirmLockSheetContent(
    durationMinutes: Int,
    estimatedEndWallMs: Long,
    selectedMode: DetoxMode,
    onDismiss: () -> Unit,
    onConfirmLock: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val endClock = TimeFormatter.formatClockTime(estimatedEndWallMs)
    val humanDuration = TimeFormatter.formatDurationHuman(context, durationMinutes)
    val modeName = stringResource(
        if (selectedMode == DetoxMode.PHONE_ONLY) R.string.mode_phone_title else R.string.mode_vacuum_title
    )

    val isOver12h = durationMinutes > DetoxSessionRepository.LONG_DURATION_WARNING_MINUTES
    val isOver24h = durationMinutes > DetoxSessionRepository.EXTREME_DURATION_WARNING_MINUTES
    val holdSeconds = when {
        isOver24h -> 4
        isOver12h -> 3
        else -> 2
    }

    var extremeAcknowledged by remember(durationMinutes) { mutableStateOf(!isOver24h) }
    val holdProgress = remember { Animatable(0f) }
    var isHolding by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    if (isOver24h) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isOver24h) Icons.Rounded.WarningAmber else Icons.Rounded.Lock,
                contentDescription = null,
                tint = if (isOver24h) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        }

        Text(
            text = stringResource(R.string.confirm_sheet_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Text(
            text = stringResource(R.string.confirm_sheet_body, endClock, humanDuration, modeName),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(
                    if (selectedMode == DetoxMode.TOTAL_VACUUM) {
                        R.string.confirm_sheet_note_vacuum
                    } else {
                        R.string.confirm_sheet_note_phone
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp)
            )
        }

        if (isOver12h) {
            DurationWarningBanner(
                selectedMinutes = durationMinutes,
                isOver24h = isOver24h
            )
        }

        if (isOver24h) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { extremeAcknowledged = !extremeAcknowledged }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .testTag("confirm_extreme_checkbox_row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(
                    checked = extremeAcknowledged,
                    onCheckedChange = { extremeAcknowledged = it }
                )
                Text(
                    text = stringResource(R.string.confirm_extreme_checkbox, humanDuration),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        val canHold = extremeAcknowledged
        val holdFillColor = if (isOver24h) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        val holdContainerColor = if (isOver24h) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
        val holdOnContainer = if (isOver24h) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .alpha(if (canHold) 1f else 0.45f)
                .clip(RoundedCornerShape(29.dp))
                .background(holdContainerColor)
                .pointerInput(canHold, holdSeconds) {
                    if (!canHold) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            isHolding = true
                            holdJob?.cancel()
                            holdJob = scope.launch {
                                holdProgress.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = holdSeconds * 1000,
                                        easing = LinearEasing
                                    )
                                )
                                if (holdProgress.value >= 0.99f) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onConfirmLock()
                                }
                            }
                            val released = tryAwaitRelease()
                            isHolding = false
                            if (released && holdProgress.value < 0.99f) {
                                holdJob?.cancel()
                                scope.launch {
                                    holdProgress.animateTo(0f, animationSpec = spring())
                                }
                            }
                        }
                    )
                }
                .testTag("hold_to_lock_button"),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(holdProgress.value.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(holdFillColor)
            )
            Text(
                text = if (isHolding) {
                    stringResource(R.string.confirm_holding_button)
                } else {
                    stringResource(R.string.confirm_hold_button_sec, holdSeconds)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (holdProgress.value > 0.45f) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    holdOnContainer
                }
            )
        }

        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("confirm_cancel_button")
        ) {
            Text(
                text = stringResource(R.string.confirm_cancel),
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
