package com.redurbabat.feedback.pairing

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.redurbabat.feedback.MainActivity

/**
 * The entry point a tapped setup link lands on. It has no UI: it validates, forwards the result to
 * [MainActivity] and finishes.
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
 * the raw intent is never passed on: what MainActivity receives is a string this activity parsed
 * and rebuilt, and what the controller does with it is decided by the origin rule there, not here.
 */
class SetupLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Anything that is not a VIEW intent did not come through the link filter. It gets the
        // same treatment as an unparsable link: the app opens, and nothing is offered.
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
        val offeredServer = link?.let(SetupLink::parseServerOrNull)?.baseUrl

        val forward = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OFFERED_SERVER, offeredServer)
        startActivity(forward)
        finish()
    }

    companion object {
        /**
         * A normalised `https://host[:port]` origin, or null when the link was not one. Never the
         * link itself.
         */
        const val EXTRA_OFFERED_SERVER = "com.redurbabat.feedback.extra.OFFERED_SERVER"
    }
}
