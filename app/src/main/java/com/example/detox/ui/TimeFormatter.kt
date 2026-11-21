package com.example.detox.ui

import android.content.Context
import com.example.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFormatter {

    fun formatSetupDuration(minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return String.format(Locale.getDefault(), "%02d:%02d", hours, mins)
    }

    fun formatDurationHuman(context: Context, totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return when {
            hours > 0 && mins > 0 -> context.getString(R.string.timer_hours_minutes_short, hours, mins)
            hours > 0 -> context.getString(R.string.timer_hours_short, hours)
            else -> context.getString(R.string.timer_minutes_short, mins)
        }
    }

    fun formatCountdown(remainingMs: Long): String {
        val safeSeconds = ((remainingMs.coerceAtLeast(0L)) + 999L) / 1000L
        val hours = safeSeconds / 3600L
        val minutes = (safeSeconds % 3600L) / 60L
        val seconds = safeSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    fun formatClockTime(wallMs: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(wallMs))
    }
}
