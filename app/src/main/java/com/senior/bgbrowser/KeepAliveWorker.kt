package com.senior.bgbrowser

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class KeepAliveWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val shouldRun = BrowserSettings.shouldServiceRun(applicationContext)

        if (shouldRun && !BrowserService.isRunning) {
            try {
                val intent = Intent(applicationContext, BrowserService::class.java).apply {
                    action = BrowserService.ACTION_START
                }
                ContextCompat.startForegroundService(applicationContext, intent)
            } catch (_: Exception) {
                return Result.retry()
            }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_PERIODIC_WORK = "bg_browser_periodic_watchdog"
        private const val UNIQUE_IMMEDIATE_WORK = "bg_browser_immediate_restart"

        fun scheduleWatchdog(context: Context) {
            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueImmediateRestart(context: Context) {
            val request = OneTimeWorkRequestBuilder<KeepAliveWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancelWatchdog(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_IMMEDIATE_WORK)
        }
    }
}
