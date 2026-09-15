package com.redurbabat.feedback.pairing

import android.content.Context

/**
 * The owner's switch to refuse setup links outright - the revocation path CLAUDE.md asks for.
 *
 * Plain private preferences, the same place the background-connection choice lives. That is enough
 * here, and deliberately so: turning the flag off again does not grant anything. A setup link can
 * only ever offer the origin this build already carries or the one the device is already
 * registered with, so the worst an edit of this value can do is re-enable a confirmation of what
 * the app already trusts. Sealing it would suggest it guards a secret, which it does not.
 */
class SetupLinkStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun isIgnored(): Boolean = preferences.getBoolean(KEY_IGNORED, false)

    fun setIgnored(ignored: Boolean) {
        preferences.edit().putBoolean(KEY_IGNORED, ignored).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "feedback.setuplink.v1"
        private const val KEY_IGNORED = "ignored"
    }
}
