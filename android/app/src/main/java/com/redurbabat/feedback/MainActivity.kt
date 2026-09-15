package com.redurbabat.feedback

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.redurbabat.feedback.pairing.SetupLinkActivity
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
        /*
         * FLAG_SECURE for the whole management UI, not just for the lock screen.
         *
         * A running screen.view stream sees everything the display shows (THREAT_MODEL 4.18).
         * Without this flag, someone holding a stolen control center session could start a
         * stream and simply wait for the owner to unlock the app - and read the PIN that is
         * supposed to protect it. The pairing QR code is a secret for the same reason, and the
         * recents thumbnail is a copy of whatever was last on screen.
         *
         * The cost is that the owner cannot screenshot this app. For a screen that shows a
         * pairing ticket, that is the right trade.
         */
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()

        controller = FeedbackController(applicationContext)
        val biometricGateway = AndroidBiometricGateway(this)
        controller.setBiometricUnlockAvailable(biometricGateway.isAvailable())
        consumeSetupLink(intent)

        setContent {
            FeedbackApp(controller = controller, biometricGateway = biometricGateway)
        }
    }

    /**
     * How a setup link reaches the running controller: SetupLinkActivity starts this activity with
     * CLEAR_TOP and SINGLE_TOP, so an existing instance is reused and gets the intent here instead
     * of a second instance - and a second instance would mean a second FeedbackController whose
     * agent stops this one's.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSetupLink(intent)
    }

    /**
     * Read once and removed, so that a recreation for a rotation or a theme change does not offer
     * the same link a second time.
     */
    private fun consumeSetupLink(source: Intent?) {
        if (source == null || !::controller.isInitialized) {
            return
        }
        val offeredServer = source.getStringExtra(SetupLinkActivity.EXTRA_OFFERED_SERVER) ?: return
        source.removeExtra(SetupLinkActivity.EXTRA_OFFERED_SERVER)
        controller.applySetupLink(offeredServer)
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
