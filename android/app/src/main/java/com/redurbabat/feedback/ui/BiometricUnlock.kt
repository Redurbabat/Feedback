package com.redurbabat.feedback.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * The app's view of biometric unlocking.
 *
 * Kept behind an interface so the composables never touch `androidx.biometric` directly and the
 * management UI stays independent of the activity that hosts the prompt.
 */
interface BiometricGateway {
    /** True only when this device can actually run a Class 3 prompt right now. */
    fun isAvailable(): Boolean

    fun prompt(onSuccess: () -> Unit, onFailure: (String?) -> Unit)

    /** Used where no activity can host a prompt, for example in previews. */
    object Unavailable : BiometricGateway {
        override fun isAvailable(): Boolean = false

        override fun prompt(onSuccess: () -> Unit, onFailure: (String?) -> Unit) {
            onFailure("Auf diesem Gerät ist keine biometrische Entsperrung verfügbar.")
        }
    }
}

/**
 * Biometric unlock for the local app lock.
 *
 * Scope, stated plainly: this is a convenience gate in front of an app-lock session that a
 * PIN/passphrase already established. It is not a second cryptographic factor - the stored verifier
 * is a PBKDF2 hash, so there is no secret for a biometric-bound key to release. The PIN/passphrase
 * therefore stays the primary and the only recovery secret, an active lockout still applies, and a
 * failed or cancelled prompt never unlocks anything.
 *
 * Only [BiometricManager.Authenticators.BIOMETRIC_STRONG] is accepted. Where a device offers just a
 * weak sensor the feature reports itself unavailable instead of quietly downgrading.
 *
 * The feature is also limited to API 28 and above, where `BiometricPrompt` delegates to the
 * system's own dialog. Below that it inflates an AppCompat-themed fragment of its own, which this
 * app's platform theme does not provide. Reporting "unavailable" there is the honest option; the
 * alternative would be to restyle the whole app for a convenience feature.
 */
class AndroidBiometricGateway(
    private val activity: FragmentActivity,
) : BiometricGateway {

    override fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }
        return BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    override fun prompt(onSuccess: () -> Unit, onFailure: (String?) -> Unit) {
        if (!isAvailable()) {
            onFailure("Auf diesem Gerät ist keine biometrische Entsperrung eingerichtet.")
            return
        }

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure(errString.toString())
            }

            override fun onAuthenticationFailed() {
                // A single non-matching read; the prompt stays open for another try.
            }
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            callback,
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Feedback entsperren")
                .setSubtitle("Biometrie entsperrt nur die lokale Oberfläche.")
                .setNegativeButtonText("PIN verwenden")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(false)
                .build(),
        )
    }

    private companion object {
        const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG
    }
}
