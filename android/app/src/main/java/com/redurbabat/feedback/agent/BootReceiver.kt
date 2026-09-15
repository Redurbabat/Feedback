package com.redurbabat.feedback.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the background connection back after the phone restarts or the app is updated.
 *
 * Without this the owner had to open the app once after every reboot, and nothing said so: the
 * device simply stayed offline in the control center. `START_STICKY` does not help - it covers the
 * system killing the service, not the system shutting down.
 *
 * Starting a foreground service from the background is otherwise refused, but `BOOT_COMPLETED` and
 * `MY_PACKAGE_REPLACED` are named exemptions, which is why this is a receiver and not a scheduled
 * job.
 *
 * Not `directBootAware`: this broadcast is wanted only after the user has unlocked, because the
 * device registration is sealed with a key that is not available before that. Arriving earlier
 * would mean failing to read it and switching the owner's choice off for no reason.
 *
 * Exported because the system is the sender. Both actions are protected broadcasts, so no app can
 * fake them - and the action is checked anyway, in [BootResumePolicy], which also enforces the part
 * that matters: this can only continue a connection the owner turned on, never turn one on.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val store = BackgroundConnectionStore(appContext)
        val decision = BootResumePolicy.decide(
            action = intent.action,
            ownerEnabledBackgroundConnection = store.isEnabled(),
            notificationsVisible = BackgroundAgentService.notificationsVisible(appContext),
        )
        when (decision) {
            BootResumeDecision.IGNORE -> Unit
            // Turned off here rather than left dangling, so the switch the owner sees next time
            // tells the truth instead of claiming a connection that cannot run.
            BootResumeDecision.DISABLE_BECAUSE_INVISIBLE -> store.setEnabled(false)
            BootResumeDecision.RESUME -> BackgroundAgentService.resume(appContext)
        }
    }
}
