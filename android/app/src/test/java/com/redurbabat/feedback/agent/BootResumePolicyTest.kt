package com.redurbabat.feedback.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reboot case is the one nobody notices when it is wrong: the phone simply stays offline, and
 * a rule that answers "no" looks exactly like a rule that works. So every branch is pinned here.
 */
class BootResumePolicyTest {

    private fun decide(
        action: String?,
        enabled: Boolean = true,
        visible: Boolean = true,
    ) = BootResumePolicy.decide(action, enabled, visible)

    @Test
    fun `resumes after a reboot and after an app update`() {
        assertEquals(BootResumeDecision.RESUME, decide(BootResumePolicy.ACTION_BOOT_COMPLETED))
        assertEquals(BootResumeDecision.RESUME, decide(BootResumePolicy.ACTION_MY_PACKAGE_REPLACED))
    }

    /** The whole point of the rule: a broadcast continues a choice, it never makes one. */
    @Test
    fun `never resumes what the owner did not switch on`() {
        assertEquals(
            BootResumeDecision.IGNORE,
            decide(BootResumePolicy.ACTION_BOOT_COMPLETED, enabled = false),
        )
        assertEquals(
            BootResumeDecision.IGNORE,
            decide(BootResumePolicy.ACTION_MY_PACKAGE_REPLACED, enabled = false),
        )
    }

    @Test
    fun `ignores every other action`() {
        for (action in listOf(
            null,
            "",
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.PACKAGE_REPLACED",
            "android.intent.action.USER_PRESENT",
            "com.redurbabat.feedback.agent.START",
            "BOOT_COMPLETED",
        )) {
            assertEquals(action, BootResumeDecision.IGNORE, decide(action))
        }
    }

    /**
     * Without the notification the connection would run unseen. Switching the stored choice off is
     * the honest answer - the switch the owner finds next time then matches reality.
     */
    @Test
    fun `switches the choice off instead of running unseen`() {
        assertEquals(
            BootResumeDecision.DISABLE_BECAUSE_INVISIBLE,
            decide(BootResumePolicy.ACTION_BOOT_COMPLETED, visible = false),
        )
    }

    /** A missing notification must not resurrect a choice that was already off. */
    @Test
    fun `an invisible notification does not matter when nothing was switched on`() {
        assertEquals(
            BootResumeDecision.IGNORE,
            decide(BootResumePolicy.ACTION_BOOT_COMPLETED, enabled = false, visible = false),
        )
    }
}
