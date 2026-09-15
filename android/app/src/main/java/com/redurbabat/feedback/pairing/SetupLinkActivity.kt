package com.redurbabat.feedback.pairing

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.redurbabat.feedback.MainActivity

/**
 * The entry point a tapped setup link lands on. It has no UI: it validates, leaves the result where
 * [MainActivity] picks it up and finishes.
 *
 * It exists as its own activity so that [MainActivity] does not have to change its launch mode. A
 * link that arrives while the management UI is already running must reach the instance that is
 * running, not create a second one - a second [MainActivity] would build a second
 * FeedbackController, and that one's agent would stop the first one's. Making MainActivity
 * `singleTask` would solve that and break other things; `singleTop` would not solve it at all,
 * because a link tapped while the MediaProjection dialog is on top of MainActivity does not find
 * MainActivity at the top of the task. A separate trampoline with CLEAR_TOP avoids the question.
 *
 * Exported, because a link from the browser is by definition an outside caller. That is also why
 * neither the raw intent nor the link itself is passed on: what MainActivity gets is an origin this
 * activity parsed and rebuilt, and what the controller does with it is decided by the origin rule
 * there, not here. It travels through [SetupLinkHandoff] rather than as an intent extra, because
 * MainActivity is exported too and must not accept that origin from anybody else.
 */
class SetupLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Anything that is not a VIEW intent carrying a URI did not come through the link filter
        // and is not a link at all, so nothing is handed over: the app opens and says nothing.
        // A link that is present but unreadable is the opposite case - it is an arrival, and it is
        // announced as a refusal rather than dropped.
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
        if (link != null) {
            SetupLinkHandoff.offer(
                SetupLinkArrival(offeredOrigin = SetupLink.parseServerOrNull(link)?.baseUrl),
            )
        }

        val forward = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(forward)
        finish()
    }
}
