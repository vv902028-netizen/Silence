package com.example.detox.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.detox.data.DetoxMode
import com.example.detox.data.DetoxSessionRepository
import com.example.detox.receiver.DetoxFinishAlarmReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DetoxMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private lateinit var repository: DetoxSessionRepository

    override fun onCreate() {
        super.onCreate()
        repository = DetoxSessionRepository.getInstance(applicationContext)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        repository.reconcileAfterReboot()
        val remainingMs = repository.computeRemainingMs()
        val active = repository.isSessionActive() && remainingMs > 0L

        // Always enter foreground immediately to avoid ForegroundServiceDidNotStartInTimeException on Android 12+
        val notification = buildActiveNotification(if (active) remainingMs else 0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_ACTIVE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID_ACTIVE, notification)
        }

        if (!active) {
            isRunning = false
            val completed = repository.completeSession()
            cancelAllAlarms(applicationContext)
            if (completed != null) {
                postCompletionNotification()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        val activeMode = repository.getCurrentSnapshot().mode
        DetoxAccessibilityService.syncOverlaysNow()
        serviceScope.launch {
            DetoxBackgroundCleaner.pauseBackgroundMediaPlayback(applicationContext)
            DetoxBackgroundCleaner.terminateBackgroundApps(applicationContext, activeMode)
        }
        scheduleFinishAlarm(remainingMs)
        startCountdownLoop()
        return START_STICKY
    }

    private fun startCountdownLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            var halfSecTick = 0
            while (isActive) {
                val remainingMs = repository.computeRemainingMs()
                if (remainingMs <= 0L) {
                    handleSessionFinished()
                    break
                }

                DetoxAccessibilityService.enforceWatchdogCheck()

                if (halfSecTick % 2 == 0) {
                    val wholeSec = halfSecTick / 2
                    updateActiveNotification(remainingMs)

                    if (wholeSec % HEARTBEAT_INTERVAL_SEC == 0) {
                        repository.recordHeartbeat()
                    }

                    if (wholeSec > 0 && wholeSec % BACKGROUND_SWEEP_INTERVAL_SEC == 0) {
                        val mode = repository.getCurrentSnapshot().mode
                        DetoxBackgroundCleaner.terminateBackgroundApps(applicationContext, mode)
                    }
                }

                halfSecTick++
                delay(500L)
            }
        }
    }

    private fun handleSessionFinished() {
        isRunning = false
        DetoxAccessibilityService.clearOverlaysNow()
        DetoxBackgroundCleaner.releaseMediaAudioFocus(applicationContext)
        val completed = repository.completeSession()
        cancelAllAlarms(applicationContext)
        if (completed != null) {
            postCompletionNotification()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (repository.isSessionActive()) {
            scheduleImmediateRestart()
        }
    }

    override fun onDestroy() {
        isRunning = false
        if (!repository.isSessionActive()) {
            DetoxAccessibilityService.clearOverlaysNow()
        }
        DetoxBackgroundCleaner.releaseMediaAudioFocus(applicationContext)
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleFinishAlarm(remainingMs: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = SystemClock.elapsedRealtime() + remainingMs + 1_500L
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            finishAlarmPendingIntent(applicationContext)
        )
    }

    private fun scheduleImmediateRestart() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 2_000L,
            restartWatchdogPendingIntent(applicationContext)
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val activeChannel = NotificationChannel(
            CHANNEL_ACTIVE_ID,
            getString(R.string.notif_channel_detox_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_detox_desc)
            setShowBadge(false)
        }

        val alertChannel = NotificationChannel(
            CHANNEL_ALERT_ID,
            getString(R.string.notif_channel_alert_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_alert_desc)
            enableVibration(true)
        }

        nm.createNotificationChannel(activeChannel)
        nm.createNotificationChannel(alertChannel)
    }

    private fun buildActiveNotification(remainingMs: Long): Notification {
        val snap = repository.getCurrentSnapshot()
        val formattedLeft = formatRemainingShort(remainingMs)
        val endWall = if (snap.plannedEndWallMs > 0L) snap.plannedEndWallMs else System.currentTimeMillis()
        val endTimeText = SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(Date(endWall))

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            REQ_OPEN_APP,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = when (snap.mode) {
            DetoxMode.PHONE_ONLY -> getString(R.string.notif_active_text_phone, formattedLeft)
            DetoxMode.TOTAL_VACUUM -> getString(R.string.notif_active_text_vacuum, formattedLeft)
        }

        return NotificationCompat.Builder(this, CHANNEL_ACTIVE_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.notif_active_title, endTimeText))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateActiveNotification(remainingMs: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID_ACTIVE, buildActiveNotification(remainingMs))
    }

    private fun postCompletionNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            REQ_COMPLETE,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val done = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.notif_complete_title))
            .setContentText(getString(R.string.notif_complete_text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .build()

        nm.notify(NOTIFICATION_ID_COMPLETE, done)
    }

    private fun formatRemainingShort(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
        val days = totalSeconds / 86_400L
        val remAfterDays = totalSeconds % 86_400L
        val hours = remAfterDays / 3600L
        val minutes = (remAfterDays % 3600L) / 60L
        val seconds = remAfterDays % 60L
        return when {
            days > 0L -> String.format(Locale.getDefault(), "%d д %02d:%02d:%02d", days, hours, minutes, seconds)
            hours > 0L -> String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
            else -> String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ACTIVE_ID = "tishina_active_timer"
        private const val CHANNEL_ALERT_ID = "tishina_return_alerts"
        private const val NOTIFICATION_ID_ACTIVE = 4101
        private const val NOTIFICATION_ID_COMPLETE = 4103

        private const val REQ_OPEN_APP = 5101
        private const val REQ_COMPLETE = 5103
        private const val REQ_FINISH_ALARM = 5104
        private const val REQ_RESTART_WATCHDOG = 5105

        private const val HEARTBEAT_INTERVAL_SEC = 15
        private const val BACKGROUND_SWEEP_INTERVAL_SEC = 60

        fun start(context: Context) {
            val intent = Intent(context, DetoxMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            isRunning = false
            val intent = Intent(context, DetoxMonitorService::class.java)
            context.stopService(intent)
        }

        fun cancelAllAlarms(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarmManager.cancel(finishAlarmPendingIntent(context))
            alarmManager.cancel(restartWatchdogPendingIntent(context))
        }

        private fun finishAlarmPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context.applicationContext, DetoxFinishAlarmReceiver::class.java).apply {
                action = DetoxFinishAlarmReceiver.ACTION_SESSION_FINISH
            }
            return PendingIntent.getBroadcast(
                context.applicationContext,
                REQ_FINISH_ALARM,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun restartWatchdogPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context.applicationContext, DetoxFinishAlarmReceiver::class.java).apply {
                action = DetoxFinishAlarmReceiver.ACTION_WATCHDOG_CHECK
            }
            return PendingIntent.getBroadcast(
                context.applicationContext,
                REQ_RESTART_WATCHDOG,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
