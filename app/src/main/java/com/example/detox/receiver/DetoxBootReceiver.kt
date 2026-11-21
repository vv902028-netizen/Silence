package com.example.detox.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.detox.data.DetoxSessionRepository
import com.example.detox.service.DetoxMonitorService

class DetoxBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val repo = DetoxSessionRepository.getInstance(context)
            repo.reconcileAfterReboot()
            if (repo.isSessionActive()) {
                runCatching { DetoxMonitorService.start(context) }
            }
        }
    }
}
