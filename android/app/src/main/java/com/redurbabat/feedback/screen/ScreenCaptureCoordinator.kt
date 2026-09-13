package com.redurbabat.feedback.screen

import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicReference

/**
 * The one place that knows which stream is being captured right now.
 *
 * It exists because Android splits this flow across component boundaries that cannot pass object
 * references: the agent asks, an Activity collects two consents, and a Service does the capturing.
 * Without a single owner of that state, "is a capture running" would be answered differently in
 * three places.
 *
 * Everything here is guarded, because frames arrive on the encoder thread while the WebSocket
 * thread is reading them.
 */
object ScreenCaptureCoordinator {

    /** What a capture needs before it can start; set when the agent accepts a `screen.start`. */
    data class Pending(
        val streamId: String,
        val settings: ScreenEncoderSettings,
    )

    private val listenerRef = AtomicReference<ScreenCaptureListener?>(null)
    private val pendingRef = AtomicReference<Pending?>(null)

    val pending: Pending? get() = pendingRef.get()

    val isActive: Boolean get() = listenerRef.get() != null

    fun begin(streamId: String, settings: ScreenEncoderSettings, listener: ScreenCaptureListener) {
        pendingRef.set(Pending(streamId, settings))
        // Only one capture exists at a time, and one agent connection means one handler, which
        // refuses a second start itself. Should a second connection ever get here anyway, the
        // request it displaces is told it is over instead of waiting for frames that now belong
        // to someone else. It cannot skip a consent either way: the new request goes through
        // both dialogs like any other.
        listenerRef.getAndSet(listener)?.onStopped(ScreenStopReason.CLIENT_CANCELLED)
    }

    fun consent(state: ScreenConsentState) {
        val listener = listenerRef.get() ?: return
        listener.onConsent(state)
        if (state == ScreenConsentState.DECLINED) {
            clear()
        }
    }

    fun started(config: ScreenStreamConfig) {
        listenerRef.get()?.onStarted(config)
    }

    fun frame(frame: EncodedScreenFrame) {
        listenerRef.get()?.onFrame(frame)
    }

    /** Reports the end exactly once, whoever gets there first. */
    fun stopped(reason: ScreenStopReason) {
        val listener = listenerRef.getAndSet(null) ?: return
        pendingRef.set(null)
        listener.onStopped(reason)
    }

    /** Forgets the capture without reporting anything - the caller already knows. */
    fun clear() {
        listenerRef.set(null)
        pendingRef.set(null)
    }

    /**
     * Starts the consent Activity.
     *
     * A full Activity rather than a dialog from the service: only an Activity can launch
     * Android's own MediaProjection dialog, and the owner has to see who is asking before that
     * dialog appears (protocol section 8.5.2).
     */
    fun launchConsent(context: Context) {
        val intent = Intent(context, ScreenConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
    }
}
