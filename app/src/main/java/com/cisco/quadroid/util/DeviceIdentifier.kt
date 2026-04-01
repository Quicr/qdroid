package com.cisco.quadroid.util

import android.content.Context
import java.util.UUID

/**
 * Singleton to manage a unique device identifier for the app session.
 * The identifier is persisted in SharedPreferences.
 */
object DeviceIdentifier {
    private const val PREFS_NAME = "quadroid_prefs"
    private const val KEY_DEVICE_ID = "device_id"

    private var deviceId: String? = null

    /**
     * Initializes and returns the unique device ID.
     * If the ID is not yet initialized, it is loaded from [context]'s SharedPreferences.
     * If no ID exists in SharedPreferences, a new UUID is generated and stored.
     *
     * @param context The context used to access SharedPreferences.
     * @return The unique device identifier.
     */
    fun get(context: Context): String {
        return deviceId ?: synchronized(this) {
            deviceId ?: loadFromPrefs(context).also { deviceId = it }
        }
    }

    /**
     * Returns the currently stored device ID, or a newly generated UUID if it is not set.
     * This method does not access SharedPreferences. It should only be used after [get]
     * has been called at least once during the app's lifetime.
     */
    fun get(): String {
        return deviceId ?: synchronized(this) {
            deviceId ?: UUID.randomUUID().toString().also { deviceId = it }
        }
    }

    /**
     * Explicitly sets the device ID.
     */
    fun set(id: String) {
        synchronized(this) {
            deviceId = id
        }
    }

    /**
     * Loads the device ID from SharedPreferences. Generates and returns a new one if not found.
     */
    private fun loadFromPrefs(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString()
    }

    /**
     * Saves the current device ID to SharedPreferences.
     *
     * @param context The context used to access SharedPreferences.
     */
    fun saveToPrefs(context: Context) {
        val id = deviceId ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
    }
}
