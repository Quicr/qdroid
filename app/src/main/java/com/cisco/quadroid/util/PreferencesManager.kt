package com.cisco.quadroid.util

import android.content.Context

/**
 * Singleton to manage app preferences using SharedPreferences.
 * Uses the same shared preferences as DeviceIdentifier.
 */
object PreferencesManager {
    private const val PREFS_NAME = "quadroid_prefs"
    private const val KEY_VAD_ENABLED = "vad_enabled"
    private const val KEY_RELAY_URL = "relay_url"
    private const val KEY_CUSTOM_RELAY_URLS = "custom_relay_urls"
    private const val KEY_DEVELOPER_MODE = "developer_mode"
    private const val KEY_CLOUDFLARE_OVERRIDE = "cloudflare_override"

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

    /**
     * Gets the relay URL from preferences.
     * Defaults to "moq://eng-3.us-west-2.m10x.org:33550" if not set.
     *
     * @param context The context used to access SharedPreferences.
     * @return The saved relay URL.
     */
    fun getRelayUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_RELAY_URL, "moq://eng-3.us-west-2.m10x.org:33550") ?: "moq://eng-3.us-west-2.m10x.org:33550"
    }

    /**
     * Saves the relay URL to preferences.
     *
     * @param context The context used to access SharedPreferences.
     * @param url The relay URL to save.
     */
    fun setRelayUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RELAY_URL, url).apply()
    }

    /**
     * Gets the list of custom relay URLs from preferences.
     *
     * @param context The context used to access SharedPreferences.
     * @return List of custom relay URLs.
     */
    fun getCustomRelayUrls(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val urlsString = prefs.getString(KEY_CUSTOM_RELAY_URLS, "") ?: ""
        return if (urlsString.isEmpty()) {
            emptyList()
        } else {
            urlsString.split("|||").filter { it.isNotBlank() }
        }
    }

    /**
     * Adds a custom relay URL to the saved list.
     *
     * @param context The context used to access SharedPreferences.
     * @param url The custom relay URL to add.
     */
    fun addCustomRelayUrl(context: Context, url: String) {
        if (url.isBlank()) return

        val currentUrls = getCustomRelayUrls(context).toMutableList()
        if (!currentUrls.contains(url)) {
            currentUrls.add(url)
            saveCustomRelayUrls(context, currentUrls)
        }
    }

    /**
     * Removes a custom relay URL from the saved list.
     *
     * @param context The context used to access SharedPreferences.
     * @param url The custom relay URL to remove.
     */
    fun removeCustomRelayUrl(context: Context, url: String) {
        val currentUrls = getCustomRelayUrls(context).toMutableList()
        currentUrls.remove(url)
        saveCustomRelayUrls(context, currentUrls)
    }

    /**
     * Saves the list of custom relay URLs to preferences.
     *
     * @param context The context used to access SharedPreferences.
     * @param urls The list of custom relay URLs to save.
     */
    private fun saveCustomRelayUrls(context: Context, urls: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val urlsString = urls.joinToString("|||")
        prefs.edit().putString(KEY_CUSTOM_RELAY_URLS, urlsString).apply()
    }

    /**
     * Gets the developer mode state from preferences.
     * Defaults to false if not set.
     *
     * @param context The context used to access SharedPreferences.
     * @return True if developer mode is enabled, false otherwise.
     */
    fun getDeveloperMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEVELOPER_MODE, false)
    }

    /**
     * Saves the developer mode state to preferences.
     *
     * @param context The context used to access SharedPreferences.
     * @param enabled True to enable developer mode, false to disable.
     */
    fun setDeveloperMode(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DEVELOPER_MODE, enabled).apply()
    }

    /**
     * Gets the Cloudflare override state from preferences.
     * Defaults to false if not set.
     *
     * @param context The context used to access SharedPreferences.
     * @return True if Cloudflare override is enabled, false otherwise.
     */
    fun getCloudflareOverride(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CLOUDFLARE_OVERRIDE, false)
    }

    /**
     * Saves the Cloudflare override state to preferences.
     *
     * @param context The context used to access SharedPreferences.
     * @param enabled True to enable Cloudflare override, false to disable.
     */
    fun setCloudflareOverride(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CLOUDFLARE_OVERRIDE, enabled).apply()
    }
}
