package com.redurbabat.feedback.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.redurbabat.feedback.MainActivity
import com.redurbabat.feedback.R

/**
 * Runs one screen capture, visibly.
 *
 * Every requirement from protocol section 8.5.2 and 8.5.8 that lives on this side is here:
 *
 * - a foreground service of type `mediaProjection` for the whole duration, with an ongoing
 *   notification naming the receiver and carrying a Stop action that works without the server,
 * - `MediaProjection.Callback#onStop`, so the system ending the capture ends the stream,
 * - stop order: the projection is released **before** the service goes away. The other way round
 *   would leave a moment where the capture runs with no visible sign of it.
 *
 * What this cannot do, and does not pretend to: make its own notification unbypassable. The
 * indicator the owner can actually rely on is Android's, not ours (THREAT_MODEL 4.16).
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: ScreenEncoder? = null
    private val main = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The system ended it - a newer projection took over, or the user revoked it in the
            // system UI. Either way this stream is finished.
            finish(ScreenStopReason.PROJECTION_STOPPED)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_REQUEST_KEYFRAME -> encoder?.requestKeyframe()
            ACTION_STOP -> {
                val reason = ScreenStopReason.fromWire(intent.getStringExtra(EXTRA_REASON))
                    ?: ScreenStopReason.CLIENT_CANCELLED
                finish(reason)
            }
            ACTION_OWNER_STOP -> finish(ScreenStopReason.OWNER_STOPPED)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseCapture()
        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        val pending = ScreenCaptureCoordinator.pending
        if (pending == null) {
            stopSelf()
            return
        }

        // Foreground first, then the projection. Android 14 enforces this order, and it is the
        // right one anyway: the notification must never appear after the capture.
        startForegroundVisible()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = consentData(intent)
        if (data == null) {
            fail(ScreenStopReason.PROJECTION_STOPPED)
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val media = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (_: IllegalStateException) {
            null
        }
        if (media == null) {
            fail(ScreenStopReason.PROJECTION_STOPPED)
            return
        }
        projection = media
        media.registerCallback(projectionCallback, main)

        val activeEncoder = ScreenEncoder(pending.settings) { fail(ScreenStopReason.ENCODER_ERROR) }
        encoder = activeEncoder
        try {
            activeEncoder.start()
        } catch (_: Exception) {
            fail(ScreenStopReason.ENCODER_ERROR)
            return
        }

        val surface = activeEncoder.surface
        if (surface == null) {
            fail(ScreenStopReason.ENCODER_ERROR)
            return
        }

        virtualDisplay = media.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            pending.settings.width,
            pending.settings.height,
            AndroidScreenCapture.densityDpi(this),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )
        if (virtualDisplay == null) {
            fail(ScreenStopReason.PROJECTION_STOPPED)
        }
    }

    /** Reports the failure to the agent and shuts the capture down. */
    private fun fail(reason: ScreenStopReason) {
        finish(reason)
    }

    private fun finish(reason: ScreenStopReason) {
        ScreenCaptureCoordinator.stopped(reason)
        releaseCapture()
        stopSelf()
    }

    /**
     * Stops filming first, then tidies up.
     *
     * The order is the point: releasing the virtual display and the projection is what actually
     * ends the capture, and it must not wait for anything else.
     */
    private fun releaseCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        encoder?.release()
        encoder = null
    }

    private fun startForegroundVisible() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            },
        )
    }

    private fun notification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_OWNER_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_feedback_connection)
            .setContentTitle(ScreenSessionPresentation.notificationTitle())
            .setContentText(ScreenSessionPresentation.notificationBody())
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, ScreenSessionPresentation.notificationStopAction(), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bildschirmuebertragung",
            // Deliberately higher than the connection channel: this one says the screen is being
            // watched right now, which the owner must not miss.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Sichtbarer Hinweis, solange der Bildschirm uebertragen wird"
            setShowBadge(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun consentData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    companion object {
        const val ACTION_START = "com.redurbabat.feedback.screen.START"
        const val ACTION_STOP = "com.redurbabat.feedback.screen.STOP"
        const val ACTION_OWNER_STOP = "com.redurbabat.feedback.screen.OWNER_STOP"
        const val ACTION_REQUEST_KEYFRAME = "com.redurbabat.feedback.screen.KEYFRAME"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_REASON = "reason"

        private const val CHANNEL_ID = "feedback.screen.capture.v1"
        private const val NOTIFICATION_ID = 7102
        private const val VIRTUAL_DISPLAY_NAME = "feedback-screen"

        /** Starts the capture with the result of Android's own MediaProjection dialog. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
