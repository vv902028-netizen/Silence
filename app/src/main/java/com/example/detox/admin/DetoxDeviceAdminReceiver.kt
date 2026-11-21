package com.example.detox.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.example.R
import com.example.detox.data.DetoxSessionRepository

class DetoxDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        DetoxSessionRepository.getInstance(context).refreshProtectionStatus()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val repo = DetoxSessionRepository.getInstance(context)
        return if (repo.isSessionActive()) {
            context.getString(R.string.device_admin_disable_warning_active)
        } else {
            context.getString(R.string.device_admin_disable_warning_idle)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        DetoxSessionRepository.getInstance(context).refreshProtectionStatus()
    }
}
