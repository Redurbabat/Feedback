package com.redurbabat.feedback.agent

/** What a system broadcast may do with the background connection. */
enum class BootResumeDecision {
    /** Continue what the owner switched on. */
    RESUME,

    /** Not our broadcast, or the owner never switched anything on. */
    IGNORE,

    /**
     * The owner switched it on, but the visible notification can no longer be shown. The stored
     * choice is turned off rather than run without it: a background connection the owner cannot
     * see on the status bar is exactly the hidden remote control this project refuses to build.
     */
    DISABLE_BECAUSE_INVISIBLE,
}

/**
 * Whether a system broadcast may resume the background connection.
 *
 * Separate from the receiver and free of `android.*` so the rule can actually be tested. The
 * receiver itself only runs on a device, and a rule that silently answers "no" looks exactly like
 * a rule that works - the reboot case is invisible until someone notices their phone went quiet.
 *
 * The rule is one-way on purpose. A broadcast may only RESUME what the owner has already turned
 * on; it can never turn it on. Otherwise a reboot would become a way to acquire a background
 * connection that was never granted.
 */
object BootResumePolicy {

    /** Delivered after the user has unlocked, which is when the sealed registration is readable. */
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"

    /** An app update stops the service just as a reboot does, and is the same kind of resume. */
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

    private val RESUMABLE_ACTIONS = setOf(ACTION_BOOT_COMPLETED, ACTION_MY_PACKAGE_REPLACED)

    fun decide(
        action: String?,
        ownerEnabledBackgroundConnection: Boolean,
        notificationsVisible: Boolean,
    ): BootResumeDecision {
        if (action !in RESUMABLE_ACTIONS) {
            return BootResumeDecision.IGNORE
        }
        if (!ownerEnabledBackgroundConnection) {
            return BootResumeDecision.IGNORE
        }
        if (!notificationsVisible) {
            return BootResumeDecision.DISABLE_BECAUSE_INVISIBLE
        }
        return BootResumeDecision.RESUME
    }
}
