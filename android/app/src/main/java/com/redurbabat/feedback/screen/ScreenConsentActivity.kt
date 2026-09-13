package com.redurbabat.feedback.screen

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The first of the two consents (protocol section 8.5.2).
 *
 * Android's MediaProjection dialog says *that* the screen will be recorded. It cannot say who
 * asked, what ends up in the picture, or that no sound is captured. This screen says that, and
 * then hands over to Android's dialog, which is the consent that actually counts.
 *
 * It never pre-answers, pre-selects, or styles itself to look like the system dialog. Declining
 * here means no capture, and the agent hears `declined` rather than waiting out a timeout.
 *
 * The UI is built in code rather than from a layout resource so that
 * [ScreenSessionPresentation] stays the single place the wording lives - it is reviewable and
 * unit tested there, which matters more here than in most screens.
 */
class ScreenConsentActivity : Activity() {

    private var decided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This window names who is asking. Nothing recording the display should be able to read
        // it back out, and the app lock uses the same flag for the same reason.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setFinishOnTouchOutside(false)

        if (ScreenCaptureCoordinator.pending == null) {
            // Nothing is waiting for an answer: the request expired or was withdrawn.
            decided = true
            finish()
            return
        }

        setTitle(ScreenSessionPresentation.consentTitle())
        setContentView(buildContent())
    }

    override fun onDestroy() {
        // Leaving without an answer is an answer, and this is also the back button's path:
        // the agent must not be left waiting for a dialog that is no longer on screen.
        if (!decided) {
            decided = true
            ScreenCaptureCoordinator.consent(ScreenConsentState.DECLINED)
        }
        super.onDestroy()
    }

    private fun buildContent(): View {
        val padding = (resources.displayMetrics.density * 20).toInt()
        val gap = (resources.displayMetrics.density * 12).toInt()

        val body = TextView(this).apply {
            text = ScreenSessionPresentation.consentBody()
            setPadding(0, 0, 0, gap)
        }
        val allow = Button(this).apply {
            text = ScreenSessionPresentation.consentAllow()
            setOnClickListener { askAndroid() }
        }
        val decline = Button(this).apply {
            text = ScreenSessionPresentation.consentDecline()
            setOnClickListener { decline() }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(body)
            addView(
                LinearLayout(this@ScreenConsentActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    // Decline first, so the destructive-looking button is not the one under the
                    // thumb by accident.
                    addView(decline)
                    addView(allow)
                },
            )
        }
    }

    private fun askAndroid() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            decline()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) {
            return
        }
        if (resultCode != RESULT_OK || data == null) {
            decline()
            return
        }
        decided = true
        // Reported before the service starts, so the control center stops showing "waiting for
        // the phone" as soon as the owner actually answered.
        ScreenCaptureCoordinator.consent(ScreenConsentState.GRANTED)
        ScreenCaptureService.start(this, resultCode, data)
        finish()
    }

    private fun decline() {
        decided = true
        ScreenCaptureCoordinator.consent(ScreenConsentState.DECLINED)
        finish()
    }

    private companion object {
        const val REQUEST_PROJECTION = 4501
    }
}
