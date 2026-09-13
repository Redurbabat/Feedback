package com.redurbabat.feedback.screen

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.ContextCompat

/**
 * The real [ScreenCaptureSource]: consent Activity, foreground service, encoder.
 *
 * It holds no state of its own. Everything that has to survive a component boundary lives in
 * [ScreenCaptureCoordinator], and everything that has to be tested lives in
 * [ScreenRequestHandler].
 */
class AndroidScreenCapture(context: Context) : ScreenCaptureSource {

    private val appContext = context.applicationContext

    override fun requestCapture(
        streamId: String,
        settings: ScreenEncoderSettings,
        listener: ScreenCaptureListener,
    ) {
        ScreenCaptureCoordinator.begin(streamId, settings, listener)
        // Said before anything is shown, so the control center can say "waiting for the phone"
        // instead of leaving a request hanging with no explanation.
        listener.onConsent(ScreenConsentState.PENDING)
        ScreenCaptureCoordinator.launchConsent(appContext)
    }

    override fun requestKeyframe() {
        send(ScreenCaptureService.ACTION_REQUEST_KEYFRAME)
    }

    override fun stop(reason: ScreenStopReason) {
        // Cleared first, so the service's own stop path finds no listener and does not report a
        // second ending for something the caller already knows about.
        ScreenCaptureCoordinator.clear()
        deliver(
            Intent(appContext, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_STOP)
                .putExtra(ScreenCaptureService.EXTRA_REASON, reason.wireName),
        )
    }

    private fun send(action: String) {
        deliver(Intent(appContext, ScreenCaptureService::class.java).setAction(action))
    }

    /**
     * Plain `startService`, not `startForegroundService`: these intents ask a running capture to
     * do something. Starting a service just to stop it would put a capture notification on
     * screen for nothing.
     *
     * The throw is real and not theoretical: from Android 8 on, an app without a foreground
     * component cannot start a service. There is nothing to capture in that case either, so
     * swallowing it is the correct answer rather than a shrug.
     */
    private fun deliver(intent: Intent) {
        try {
            appContext.startService(intent)
        } catch (_: IllegalStateException) {
            // No running capture to reach.
        }
    }

    companion object {

        /**
         * The display size the encoder should mirror.
         *
         * Uses the real size including system bars: the projection mirrors the whole display, so
         * anything smaller would letterbox the picture.
         */
        fun displaySize(context: Context): Pair<Int, Int> {
            val windowManager = ContextCompat.getSystemService(context, WindowManager::class.java)
                ?: return Pair(0, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                return Pair(bounds.width(), bounds.height())
            }
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            return Pair(metrics.widthPixels, metrics.heightPixels)
        }

        fun densityDpi(context: Context): Int = context.resources.displayMetrics.densityDpi
    }
}
