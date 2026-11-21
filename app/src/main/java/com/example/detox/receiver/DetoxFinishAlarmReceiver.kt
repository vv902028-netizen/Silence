package com.example.detox.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.detox.data.DetoxSessionRepository
import com.example.detox.service.DetoxMonitorService

class DetoxFinishAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = DetoxSessionRepository.getInstance(context)
        repo.reconcileAfterReboot()
        if (repo.isSessionActive()) {
            runCatching { DetoxMonitorService.start(context) }
        } else {
            repo.completeSession()
            DetoxMonitorService.stop(context)
        }
    }

    companion object {
        const val ACTION_SESSION_FINISH = "com.example.detox.ACTION_SESSION_FINISH"
        const val ACTION_WATCHDOG_CHECK = "com.example.detox.ACTION_WATCHDOG_CHECK"
    }
}
