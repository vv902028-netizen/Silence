package com.example.detox.ui

import android.content.Context
import com.example.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeFormatter {

    fun formatSetupDuration(minutes: Int): String {
        val safeMinutes = minutes.coerceAtLeast(0)
        val days = safeMinutes / (24 * 60)
        val remAfterDays = safeMinutes % (24 * 60)
        val hours = remAfterDays / 60
        val mins = remAfterDays % 60
        return if (days > 0) {
            String.format(Locale.getDefault(), "%d д %02d:%02d", days, hours, mins)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", hours, mins)
        }
    }

    fun formatDurationHuman(context: Context, totalMinutes: Int): String {
        val safeMinutes = totalMinutes.coerceAtLeast(0)
        val days = safeMinutes / (24 * 60)
        val remAfterDays = safeMinutes % (24 * 60)
        val hours = remAfterDays / 60
        val mins = remAfterDays % 60
        return when {
            days > 0 && hours > 0 -> context.getString(R.string.timer_days_hours_short, days, hours)
            days > 0 -> context.getString(R.string.timer_days_short, days)
            hours > 0 && mins > 0 -> context.getString(R.string.timer_hours_minutes_short, hours, mins)
            hours > 0 -> context.getString(R.string.timer_hours_short, hours)
            else -> context.getString(R.string.timer_minutes_short, mins)
        }
    }

    fun formatCountdown(remainingMs: Long): String {
        val safeSeconds = ((remainingMs.coerceAtLeast(0L)) + 999L) / 1000L
        val days = safeSeconds / 86_400L
        val remAfterDays = safeSeconds % 86_400L
        val hours = remAfterDays / 3600L
        val minutes = (remAfterDays % 3600L) / 60L
        val seconds = remAfterDays % 60L
        return when {
            days > 0L -> String.format(Locale.getDefault(), "%d д %02d:%02d:%02d", days, hours, minutes, seconds)
            hours > 0L -> String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
            else -> String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    fun formatClockTime(wallMs: Long): String {
        val nowCal = Calendar.getInstance()
        val endCal = Calendar.getInstance().apply { timeInMillis = wallMs }
        val sameDay = nowCal.get(Calendar.YEAR) == endCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == endCal.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(wallMs))
        } else {
            SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(wallMs))
        }
    }
}
