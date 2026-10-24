package com.senior.bgbrowser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (BrowserSettings.shouldServiceRun(context)) {
                try {
                    val srvIntent = Intent(context, BrowserService::class.java).apply {
                        this.action = BrowserService.ACTION_START
                    }
                    ContextCompat.startForegroundService(context, srvIntent)
                } catch (_: Exception) {
                    KeepAliveWorker.enqueueImmediateRestart(context)
                }
            }
        }
    }
}
