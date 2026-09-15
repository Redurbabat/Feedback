package com.redurbabat.feedback

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.redurbabat.feedback.pairing.SetupLinkHandoff
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
        consumeSetupLink()

        setContent {
            FeedbackApp(controller = controller, biometricGateway = biometricGateway)
        }
    }

    /**
     * How a setup link reaches the running controller: SetupLinkActivity starts this activity with
     * CLEAR_TOP and SINGLE_TOP, so an existing instance is reused and is woken here instead of a
     * second instance being built - and a second instance would mean a second FeedbackController
     * whose agent stops this one's.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSetupLink()
    }

    /**
     * The link is fetched from [SetupLinkHandoff], never from the intent.
     *
     * This activity is the LAUNCHER entry and therefore exported: any installed app can start it,
     * with any extras it likes. An extra read here would let such a caller claim that a setup link
     * offered an address when none was tapped - and the sentence the controller then writes sits in
     * exactly the panel whose job it is to tell the owner where the server address came from. The
     * origin rule would still refuse every foreign server, so nothing would be paired wrongly; what
     * would be wrong is the story on screen. [SetupLinkHandoff] cannot be written from outside this
     * process, so there is no caller to trust and none to check.
     *
     * Reading clears the handoff, so a recreation for a rotation or a theme change does not offer
     * the same link a second time. The cold-start case - the tapped link is what launched the app -
     * is covered by the call in `onCreate`: SetupLinkActivity runs in this process and finishes its
     * write before this activity is created.
     */
    private fun consumeSetupLink() {
        if (!::controller.isInitialized) {
            return
        }
        val arrival = SetupLinkHandoff.take() ?: return
        controller.applySetupLink(arrival)
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
