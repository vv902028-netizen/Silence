package com.example.detox.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.R
import com.example.detox.data.DetoxMode
import com.example.ui.theme.TimerDisplayTextStyle
import com.example.ui.theme.VacuumMutedGray
import com.example.ui.theme.VacuumPureBlack
import com.example.ui.theme.VacuumPureWhite
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ActiveLockScreen(
    uiState: DetoxUiState,
    onRequestPinScreen: () -> Unit,
    onOpenDialer: () -> Unit
) {
    BackHandler(enabled = true) { }

    if (uiState.activeMode == DetoxMode.TOTAL_VACUUM) {
        TotalVacuumBrickScreen(uiState = uiState)
    } else {
        PhoneOnlyLockScreen(
            uiState = uiState,
            onRequestPinScreen = onRequestPinScreen,
            onOpenDialer = onOpenDialer
        )
    }
}

/**
 * Strict monochrome brick screen for TOTAL_VACUUM:
 * Pure black text on pure white background, static non-animated clock, zero interactivity, minimal text.
 */
@Composable
private fun TotalVacuumBrickScreen(uiState: DetoxUiState) {
    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()

    DisposableEffect(view, darkTheme) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = true
        controller?.isAppearanceLightNavigationBars = true
        onDispose {
            controller?.isAppearanceLightStatusBars = !darkTheme
            controller?.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    val countdownText = TimeFormatter.formatCountdown(uiState.remainingMs)
    val isMultiDay = uiState.remainingMs >= 86_400_000L
    val clockStyle = if (isMultiDay) {
        TimerDisplayTextStyle.copy(fontSize = 38.sp, lineHeight = 44.sp)
    } else {
        TimerDisplayTextStyle
    }

    Scaffold(
        containerColor = VacuumPureWhite,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VacuumPureWhite)
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 520.dp)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.lock_mode_badge_vacuum),
                    style = MaterialTheme.typography.labelLarge,
                    color = VacuumMutedGray
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = countdownText,
                        style = clockStyle,
                        color = VacuumPureBlack,
                        maxLines = 1,
                        modifier = Modifier.testTag("active_countdown_text")
                    )
                    Text(
                        text = stringResource(
                            R.string.lock_free_at,
                            TimeFormatter.formatClockTime(uiState.plannedEndWallMs)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VacuumMutedGray
                    )
                }

                Text(
                    text = stringResource(R.string.lock_vacuum_emergency_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = VacuumMutedGray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 8.dp)
                        .testTag("vacuum_emergency_hint_text")
                )
            }
        }
    }
}

@Composable
private fun PhoneOnlyLockScreen(
    uiState: DetoxUiState,
    onRequestPinScreen: () -> Unit,
    onOpenDialer: () -> Unit
) {
    val ringAccent = MaterialTheme.colorScheme.primary
    val ringContainer = MaterialTheme.colorScheme.primaryContainer
    val ringTrack = MaterialTheme.colorScheme.surfaceContainerHigh

    val isMultiDay = uiState.remainingMs >= 86_400_000L
    val countdownStyle = if (isMultiDay) {
        TimerDisplayTextStyle.copy(fontSize = 34.sp, lineHeight = 40.sp)
    } else {
        TimerDisplayTextStyle
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.ambientBackgroundEnabled) {
                SubtleAmbientBackdrop(modifier = Modifier.fillMaxSize())
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 520.dp)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Minimal top status row
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = ringContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Call,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.lock_mode_badge_phone),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        if (uiState.isScreenPinned || uiState.protectionStatus.accessibilityEnabled) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = stringResource(
                                            if (uiState.isScreenPinned) {
                                                R.string.lock_screen_pinned_badge
                                            } else {
                                                R.string.lock_shield_active_badge
                                            }
                                        ),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }

                    if (!uiState.isScreenPinned) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PushPin,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.lock_pin_screen_banner_title),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                TextButton(
                                    onClick = onRequestPinScreen,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("pin_screen_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.lock_pin_screen_banner_action),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    }
                }

                // Countdown ring + optional subtle offline activity hint
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Box(
                        modifier = Modifier.size(264.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokePx = 10.dp.toPx()
                            val inset = 12.dp.toPx()
                            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                            val topLeft = Offset(inset, inset)

                            drawArc(
                                color = ringTrack,
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                            )

                            val sweep = (uiState.progressRemainingFraction * 360f).coerceIn(2f, 360f)
                            drawArc(
                                color = ringAccent,
                                startAngle = -90f,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = TimeFormatter.formatCountdown(uiState.remainingMs),
                                style = countdownStyle,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                modifier = Modifier.testTag("active_countdown_text")
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = stringResource(
                                        R.string.lock_free_at,
                                        TimeFormatter.formatClockTime(uiState.plannedEndWallMs)
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (uiState.activityHintsEnabled) {
                        SubtleActivityHintChip(remainingMs = uiState.remainingMs)
                    }

                    if (uiState.timeTamperDetected) {
                        StatusNoticeRow(text = stringResource(R.string.lock_tamper_notice))
                    } else if (uiState.bypassAttempts > 0) {
                        StatusNoticeRow(
                            text = stringResource(R.string.lock_bypass_notice, uiState.bypassAttempts)
                        )
                    }
                }

                // Bottom dialer button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    FilledTonalButton(
                        onClick = onOpenDialer,
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("open_dialer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Call,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.lock_open_dialer),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtleAmbientBackdrop(modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val transition = rememberInfiniteTransition(label = "subtle_ambient")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ambient_phase"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val baseRadius = size.minDimension * 0.58f

        val offsetX1 = cos(phase.toDouble()).toFloat() * w * 0.14f
        val offsetY1 = sin(phase.toDouble()).toFloat() * h * 0.08f
        val center1 = Offset(w * 0.5f + offsetX1, h * 0.42f + offsetY1)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = 0.08f),
                    primaryColor.copy(alpha = 0.0f)
                ),
                center = center1,
                radius = baseRadius
            ),
            radius = baseRadius,
            center = center1
        )

        val offsetX2 = sin(phase.toDouble() * 0.8).toFloat() * w * 0.16f
        val offsetY2 = cos(phase.toDouble() * 0.8).toFloat() * h * 0.10f
        val center2 = Offset(w * 0.5f - offsetX2, h * 0.58f - offsetY2)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    secondaryColor.copy(alpha = 0.06f),
                    secondaryColor.copy(alpha = 0.0f)
                ),
                center = center2,
                radius = baseRadius * 0.9f
            ),
            radius = baseRadius * 0.9f,
            center = center2
        )
    }
}

@Composable
private fun SubtleActivityHintChip(remainingMs: Long) {
    val context = LocalContext.current
    val arrayRes = when {
        remainingMs < 30L * 60_000L -> R.array.offline_hints_short
        remainingMs < 120L * 60_000L -> R.array.offline_hints_medium
        else -> R.array.offline_hints_long
    }
    val hints = remember(arrayRes) { context.resources.getStringArray(arrayRes) }
    var hintIndex by remember(arrayRes) { mutableIntStateOf(0) }
    val currentHint = if (hints.isNotEmpty()) hints[hintIndex % hints.size] else ""

    if (currentHint.isNotEmpty()) {
        Surface(
            onClick = { hintIndex++ },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("activity_hint_chip")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = currentHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.lock_activity_hint_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusNoticeRow(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
