package com.local.calltouchlock

import android.content.Context

class SettingsRepository(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE, true)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE, value).apply()

    var blockDuringCalls: Boolean
        get() = prefs.getBoolean(KEY_BLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK, value).apply()

    /** A ringing call is answered / rejected with the keys only. */
    var blockWhileRinging: Boolean
        get() = prefs.getBoolean(KEY_RINGING, true)
        set(value) = prefs.edit().putBoolean(KEY_RINGING, value).apply()

    /** Lock screen, keys only (works while the accessibility service is on). */
    var lockScreenGuard: Boolean
        get() = prefs.getBoolean(KEY_GUARD, true)
        set(value) = prefs.edit().putBoolean(KEY_GUARD, value).apply()

    var startAfterBoot: Boolean
        get() = prefs.getBoolean(KEY_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_BOOT, value).apply()

    companion object {
        private const val PREFS = "call_touch_lock"
        private const val KEY_SERVICE = "service_enabled"
        private const val KEY_BLOCK = "block_during_calls"
        private const val KEY_BOOT = "start_after_boot"
        private const val KEY_GUARD = "lock_screen_guard"
        private const val KEY_RINGING = "block_while_ringing"
    }
}
