package com.redurbabat.feedback

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.redurbabat.feedback.ui.AndroidBiometricGateway
import com.redurbabat.feedback.ui.FeedbackApp
import com.redurbabat.feedback.ui.FeedbackController

/**
 * Hosts the management UI. It is a [FragmentActivity] because `androidx.biometric` needs one to
 * show its prompt; everything else is Compose.
 */
class MainActivity : FragmentActivity() {
    private lateinit var controller: FeedbackController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        controller = FeedbackController(applicationContext)
        val biometricGateway = AndroidBiometricGateway(this)
        controller.setBiometricUnlockAvailable(biometricGateway.isAvailable())

        setContent {
            FeedbackApp(controller = controller, biometricGateway = biometricGateway)
        }
    }

    /**
     * Auto-lock is driven from the activity lifecycle rather than from a timer, so the idle window
     * starts exactly when the management UI stops being visible. `onStop` also covers the recents
     * overview and the screen turning off.
     */
    override fun onStop() {
        if (::controller.isInitialized) {
            controller.onEnterBackground()
        }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (::controller.isInitialized) {
            controller.onEnterForeground()
            // Biometric enrolment can change while the app is in the background.
            controller.setBiometricUnlockAvailable(AndroidBiometricGateway(this).isAvailable())
        }
    }

    override fun onDestroy() {
        if (::controller.isInitialized) {
            controller.close()
        }
        super.onDestroy()
    }
}
