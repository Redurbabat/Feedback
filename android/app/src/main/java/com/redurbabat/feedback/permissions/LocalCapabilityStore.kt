package com.redurbabat.feedback.permissions

import android.content.Context
import com.redurbabat.feedback.protocol.Capability

/**
 * Local owner consent for device capabilities. Defaults to an empty set (deny-by-default).
 * This is deliberately independent from server grants: effective permission needs both.
 */
class LocalCapabilityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun granted(): Set<Capability> {
        val wireNames = preferences.getStringSet(KEY_GRANTED, emptySet()).orEmpty()
        return Capability.fromWireList(wireNames).filterTo(LinkedHashSet()) { it.implemented }
    }

    fun setGranted(capability: Capability, granted: Boolean) {
        require(capability.implemented) { "Capability is not implemented" }
        val updated = granted().toMutableSet()
        if (granted) {
            updated.add(capability)
        } else {
            updated.remove(capability)
        }
        preferences.edit()
            .putStringSet(KEY_GRANTED, Capability.toWireList(updated).toSet())
            .apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_GRANTED).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "feedback.capabilities.v1"
        private const val KEY_GRANTED = "granted"
    }
}
