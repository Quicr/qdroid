package com.cisco.quadroid.util

import android.content.Context

/**
 * Singleton to manage app preferences using SharedPreferences.
 * Uses the same shared preferences as DeviceIdentifier.
 */
object PreferencesManager {
    private const val PREFS_NAME = "quadroid_prefs"
    private const val KEY_VAD_ENABLED = "vad_enabled"

    /**
     * Gets the VAD (Voice Activity Detection) enabled state from preferences.
     * Defaults to true if not set.
     *
     * @param context The context used to access SharedPreferences.
     * @return True if VAD is enabled, false otherwise.
     */
    fun getVadEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_VAD_ENABLED, true) // Default to enabled
    }

    /**
     * Saves the VAD (Voice Activity Detection) enabled state to preferences.
     *
     * @param context The context used to access SharedPreferences.
     * @param enabled True to enable VAD, false to disable.
     */
    fun setVadEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_VAD_ENABLED, enabled).apply()
    }
}
