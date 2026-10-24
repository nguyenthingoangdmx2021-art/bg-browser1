package com.senior.bgbrowser

import android.content.Context
import android.content.SharedPreferences

object BrowserSettings {
    private const val PREFS_NAME = "bg_browser_prefs"

    const val KEY_WAKELOCK_ENABLED = "wakelock_enabled"
    const val KEY_SILENT_AUDIO_ENABLED = "silent_audio_enabled"
    const val KEY_SERVICE_SHOULD_RUN = "service_should_run"
    const val KEY_BLOCK_POPUPS = "block_popups"
    const val KEY_CUSTOM_USER_AGENT = "custom_user_agent"

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isWakeLockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WAKELOCK_ENABLED, true)

    fun setWakeLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKELOCK_ENABLED, enabled).apply()
    }

    fun isSilentAudioEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SILENT_AUDIO_ENABLED, true)

    fun setSilentAudioEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SILENT_AUDIO_ENABLED, enabled).apply()
    }

    fun shouldServiceRun(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_SHOULD_RUN, true)

    fun setServiceShouldRun(context: Context, shouldRun: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE_SHOULD_RUN, shouldRun).apply()
    }

    fun isBlockPopups(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_POPUPS, true)

    fun setBlockPopups(context: Context, block: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCK_POPUPS, block).apply()
    }

    fun getCustomUserAgent(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_USER_AGENT, "")?.trim() ?: ""

    fun setCustomUserAgent(context: Context, ua: String) {
        prefs(context).edit().putString(KEY_CUSTOM_USER_AGENT, ua.trim()).apply()
    }
}
