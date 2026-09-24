package com.local.calltouchlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the protection back after a reboot and after the app itself was updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val settings = SettingsRepository(context)
        if (!settings.serviceEnabled) return
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> if (settings.startAfterBoot) CallMonitorService.start(context.applicationContext)
            // An update kills the service; without this the protection stays off until the app is opened.
            Intent.ACTION_MY_PACKAGE_REPLACED -> CallMonitorService.start(context.applicationContext)
        }
    }
}
