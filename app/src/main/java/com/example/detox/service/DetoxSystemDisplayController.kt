package com.example.detox.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Optionally disables system Always-On Display (doze_always_on) and enables system-wide grayscale
 * (accessibility_display_daltonizer) during TOTAL_VACUUM if the user granted WRITE_SECURE_SETTINGS
 * via ADB. Degrades cleanly to a safe no-op on standard devices without WRITE_SECURE_SETTINGS.
 */
object DetoxSystemDisplayController {

    private const val KEY_DOZE_ALWAYS_ON = "doze_always_on"
    private const val KEY_DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
    private const val KEY_DALTONIZER_MODE = "accessibility_display_daltonizer"
    private const val DALTONIZER_MONOCHROMACY = 0

    data class DisplayOverrideSnapshot(
        val applied: Boolean = false,
        val previousAodState: Int = -1,
        val previousDaltonizerEnabled: Int = -1,
        val previousDaltonizerMode: Int = -1
    )

    fun hasSecureSettingsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun applyStrictMonochromeAndDisableAod(context: Context): DisplayOverrideSnapshot {
        if (!hasSecureSettingsPermission(context)) return DisplayOverrideSnapshot()

        val cr = context.contentResolver
        return runCatching {
            val prevAod = Settings.Secure.getInt(cr, KEY_DOZE_ALWAYS_ON, -1)
            val prevDaltEnabled = Settings.Secure.getInt(cr, KEY_DALTONIZER_ENABLED, -1)
            val prevDaltMode = Settings.Secure.getInt(cr, KEY_DALTONIZER_MODE, -1)

            if (prevAod != -1) {
                Settings.Secure.putInt(cr, KEY_DOZE_ALWAYS_ON, 0)
            }
            Settings.Secure.putInt(cr, KEY_DALTONIZER_ENABLED, 1)
            Settings.Secure.putInt(cr, KEY_DALTONIZER_MODE, DALTONIZER_MONOCHROMACY)

            DisplayOverrideSnapshot(
                applied = true,
                previousAodState = prevAod,
                previousDaltonizerEnabled = prevDaltEnabled,
                previousDaltonizerMode = prevDaltMode
            )
        }.getOrDefault(DisplayOverrideSnapshot())
    }

    fun restorePreviousSettings(
        context: Context,
        applied: Boolean,
        previousAodState: Int,
        previousDaltonizerEnabled: Int,
        previousDaltonizerMode: Int
    ) {
        if (!applied || !hasSecureSettingsPermission(context)) return

        val cr = context.contentResolver
        runCatching {
            if (previousAodState != -1) {
                Settings.Secure.putInt(cr, KEY_DOZE_ALWAYS_ON, previousAodState)
            }
            if (previousDaltonizerEnabled != -1) {
                Settings.Secure.putInt(cr, KEY_DALTONIZER_ENABLED, previousDaltonizerEnabled)
            } else {
                Settings.Secure.putInt(cr, KEY_DALTONIZER_ENABLED, 0)
            }
            if (previousDaltonizerMode != -1) {
                Settings.Secure.putInt(cr, KEY_DALTONIZER_MODE, previousDaltonizerMode)
            }
        }
    }
}
