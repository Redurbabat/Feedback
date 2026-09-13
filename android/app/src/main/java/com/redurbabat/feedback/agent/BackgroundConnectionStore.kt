package com.redurbabat.feedback.agent

import android.content.Context

/**
 * Stores only the owner's explicit choice to keep the Feedback agent connected in background.
 *
 * This is not a credential store. Device tokens remain exclusively in DeviceRegistrationStore.
 */
class BackgroundConnectionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "feedback.background.v1"
        private const val KEY_ENABLED = "enabled"
    }
}
