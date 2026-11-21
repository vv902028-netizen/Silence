package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightTishinaColorScheme = lightColorScheme(
    primary = GuardianBlue,
    onPrimary = OnGuardianBlue,
    primaryContainer = SkyMistContainer,
    onPrimaryContainer = OnSkyMistContainer,
    secondary = SageShield,
    onSecondary = OnSageShield,
    secondaryContainer = SageContainer,
    onSecondaryContainer = OnSageContainer,
    tertiary = QuietIndigo,
    onTertiary = OnQuietIndigo,
    tertiaryContainer = QuietIndigoContainer,
    onTertiaryContainer = OnQuietIndigoContainer,
    background = AlabasterBackground,
    onBackground = DeepSlateInk,
    surface = AlabasterBackground,
    onSurface = DeepSlateInk,
    surfaceVariant = SurfaceContainerLight,
    onSurfaceVariant = MutedSlateText,
    surfaceContainerLowest = OnGuardianBlue,
    surfaceContainerLow = SurfaceLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceHighLight,
    outline = SoftOutlineLight,
    outlineVariant = SoftOutlineVariantLight,
    error = CalmAmberError,
    errorContainer = CalmAmberContainer,
    onErrorContainer = OnCalmAmberContainer
)

private val DarkTishinaColorScheme = darkColorScheme(
    primary = GuardianBlueDark,
    onPrimary = OnGuardianBlueDark,
    primaryContainer = SkyMistContainerDark,
    onPrimaryContainer = OnSkyMistContainerDark,
    secondary = SageShieldDark,
    onSecondary = OnSageShieldDark,
    secondaryContainer = SageContainerDark,
    onSecondaryContainer = OnSageContainerDark,
    tertiary = QuietIndigoDark,
    onTertiary = OnQuietIndigoDark,
    tertiaryContainer = QuietIndigoContainerDark,
    onTertiaryContainer = OnQuietIndigoContainerDark,
    background = MidnightSlateBackground,
    onBackground = CloudInkDark,
    surface = MidnightSlateBackground,
    onSurface = CloudInkDark,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = MutedCloudTextDark,
    surfaceContainerLowest = MidnightSlateBackground,
    surfaceContainerLow = SurfaceLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceHighDark,
    outline = SoftOutlineDark,
    outlineVariant = SoftOutlineVariantDark
)

@Composable
fun TishinaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkTishinaColorScheme else LightTishinaColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
