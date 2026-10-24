package com.senior.bgbrowser

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat

class BrowserService : Service() {
    companion object {
        const val CHANNEL_ID = "bg_browser_keep_alive_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.senior.bgbrowser.ACTION_START"
        const val ACTION_STOP = "com.senior.bgbrowser.ACTION_STOP"
        const val ACTION_MOVE_TO_OVERLAY = "com.senior.bgbrowser.ACTION_MOVE_TO_OVERLAY"
        const val ACTION_UPDATE_CONFIG = "com.senior.bgbrowser.ACTION_UPDATE_CONFIG"
        @Volatile var isRunning = false; private set
        var overlayContainerRef: FrameLayout? = null; private set
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var silentAudioPlayer: SilentAudioPlayer
    private var windowManager: WindowManager? = null
    private var overlayContainer: FrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            TabManager.pulseKeepAliveTabs(applicationContext)
            if (BrowserSettings.isWakeLockEnabled(applicationContext) && wakeLock?.isHeld == false) acquireLocks()
            handler.postDelayed(this, 3000L)
        }
    }
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        silentAudioPlayer = SilentAudioPlayer(applicationContext)
        createNotificationChannel()
        setupOverlayWindowIfPermitted()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { BrowserSettings.setServiceShouldRun(applicationContext, false); stopSelfCleanly(); return START_NOT_STICKY }
            ACTION_MOVE_TO_OVERLAY -> { moveKeepAliveTabsToOverlay(); updateNotification() }
            ACTION_UPDATE_CONFIG -> applyRuntimeConfig()
            else -> {
                BrowserSettings.setServiceShouldRun(applicationContext, true)
                val n = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(NOTIFICATION_ID, n)
                TabManager.ensureInitialized(applicationContext)
                applyRuntimeConfig()
                handler.removeCallbacks(heartbeatRunnable); handler.post(heartbeatRunnable)
                KeepAliveWorker.scheduleWatchdog(applicationContext)
            }
        }
        return START_STICKY
    }
    private fun applyRuntimeConfig() {
        if (overlayContainer == null && Settings.canDrawOverlays(this)) setupOverlayWindowIfPermitted()
        if (BrowserSettings.isWakeLockEnabled(applicationContext)) acquireLocks() else releaseLocks()
        if (BrowserSettings.isSilentAudioEnabled(applicationContext)) silentAudioPlayer.start() else silentAudioPlayer.stop()
        updateNotification()
    }
    private fun updateNotification() { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification()) }
    private fun setupOverlayWindowIfPermitted() {
        if (!Settings.canDrawOverlays(this) || overlayContainer != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val c = FrameLayout(applicationContext)
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(1, 1, flag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        try { windowManager?.addView(c, p); overlayContainer = c; overlayContainerRef = c } catch (_: Exception) {}
    }
    private fun moveKeepAliveTabsToOverlay() { if (overlayContainer == null && Settings.canDrawOverlays(this)) setupOverlayWindowIfPermitted(); TabManager.moveKeepAliveTabsToServiceOverlay(this, overlayContainer) }
    private fun acquireLocks() {
        try {
            if (wakeLock == null) wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BgBrowser::CpuLock").apply { setReferenceCounted(false) }
            if (wakeLock?.isHeld == false) wakeLock?.acquire(12 * 3600 * 1000L)
            if (wifiLock == null) wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BgBrowser::WifiLock").apply { setReferenceCounted(false) }
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } catch (_: Exception) {}
    }
    private fun releaseLocks() { try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}; try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {} }
    private fun stopSelfCleanly() { isRunning = false; handler.removeCallbacks(heartbeatRunnable); silentAudioPlayer.stop(); releaseLocks(); KeepAliveWorker.cancelWatchdog(applicationContext); overlayContainer?.let { try { it.removeAllViews(); windowManager?.removeView(it) } catch (_: Exception) {} }; overlayContainer = null; overlayContainerRef = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent); if (BrowserSettings.shouldServiceRun(applicationContext)) moveKeepAliveTabsToOverlay() }
    override fun onDestroy() { isRunning = false; handler.removeCallbacks(heartbeatRunnable); silentAudioPlayer.stop(); releaseLocks(); super.onDestroy(); if (BrowserSettings.shouldServiceRun(applicationContext)) KeepAliveWorker.enqueueImmediateRestart(applicationContext) }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun buildNotification(): Notification {
        val openP = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopP = PendingIntent.getService(this, 2, Intent(this, BrowserService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("BG Browser đang chạy nền").setContentText("Tab nền: ${TabManager.countKeepAliveTabs()}/${TabManager.tabs.size}").setOngoing(true).setContentIntent(openP).addAction(0, "Mở lại", openP).addAction(0, "Dừng", stopP).build()
    }
    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Chạy nền", NotificationManager.IMPORTANCE_LOW)) }
}
