package com.redurbabat.feedback.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.redurbabat.feedback.MainActivity
import com.redurbabat.feedback.R
import com.redurbabat.feedback.device.SystemInfoProvider
import com.redurbabat.feedback.files.FilesAgentFactory
import com.redurbabat.feedback.pairing.AndroidDeviceMetadataProvider
import com.redurbabat.feedback.permissions.LocalCapabilityStore
import com.redurbabat.feedback.screen.AndroidScreenCapture
import com.redurbabat.feedback.security.DeviceRegistrationStore
import com.redurbabat.feedback.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * User-enabled foreground service for keeping the authenticated device agent reachable while the
 * activity is not open.
 *
 * The service is deliberately visible: Android shows an ongoing notification with a Stop action.
 * It never starts itself from a hidden receiver and does not attempt to bypass platform background
 * restrictions. The owner must enable it from the visible app UI.
 */
class BackgroundAgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var backgroundStore: BackgroundConnectionStore
    private lateinit var registrationStore: DeviceRegistrationStore
    private lateinit var localCapabilities: LocalCapabilityStore
    private lateinit var secretStore: SecretStore
    private var agent: DeviceAgentClient? = null
    private var stateJob: Job? = null
    private var reconnectJob: Job? = null
    private val reconnectBackoff = AgentReconnectBackoff()

    override fun onCreate() {
        super.onCreate()
        backgroundStore = BackgroundConnectionStore(this)
        secretStore = SecretStore(this)
        registrationStore = DeviceRegistrationStore(secretStore)
        localCapabilities = LocalCapabilityStore(this)
        createNotificationChannel()
        startForegroundVisible(AgentConnectionState.CONNECTING)

        if (!backgroundStore.isEnabled()) {
            stopSelf()
            return
        }
        startAgentIfPossible()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                backgroundStore.setEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_CAPABILITIES_CHANGED -> {
                agent?.notifyCapabilitiesChanged()
            }

            ACTION_RECONNECT -> {
                reconnectJob?.cancel()
                reconnectJob = null
                agent?.start()
            }

            ACTION_START, null -> {
                if (backgroundStore.isEnabled() && agent == null) {
                    startAgentIfPossible()
                }
            }
        }
        return if (backgroundStore.isEnabled()) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        stateJob?.cancel()
        agent?.stop()
        agent = null
        currentAgent = null
        _state.value = AgentConnectionState.STOPPED
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAgentIfPossible() {
        if (agent != null) {
            return
        }
        val stored = runCatching { registrationStore.load() }.getOrNull()
        if (stored == null) {
            backgroundStore.setEnabled(false)
            stopSelf()
            return
        }

        val next = DeviceAgentClient(
            storedRegistration = stored,
            metadata = AndroidDeviceMetadataProvider.current(),
            localCapabilities = localCapabilities,
            systemInfoProvider = SystemInfoProvider(this),
            registrationStore = registrationStore,
            filesHandler = FilesAgentFactory.create(this, secretStore),
            screenCapture = AndroidScreenCapture(this),
            displaySize = { AndroidScreenCapture.displaySize(this) },
        )
        agent = next
        currentAgent = next
        stateJob = scope.launch {
            next.state.collect { connectionState ->
                _state.value = connectionState
                updateNotification(connectionState)
                when (connectionState) {
                    AgentConnectionState.ONLINE -> {
                        reconnectBackoff.reset()
                        reconnectJob?.cancel()
                        reconnectJob = null
                    }

                    AgentConnectionState.OFFLINE,
                    AgentConnectionState.ERROR,
                    -> scheduleReconnect(next)

                    AgentConnectionState.REVOKED -> {
                        backgroundStore.setEnabled(false)
                        localCapabilities.clear()
                        reconnectJob?.cancel()
                        reconnectJob = null
                        stopSelf()
                    }

                    // No reconnect and no stopSelf: the service stays up precisely so the
                    // notification keeps saying this, instead of the device going quiet in a way
                    // that is indistinguishable from a flat battery.
                    AgentConnectionState.UNTRUSTED_SERVER -> {
                        reconnectJob?.cancel()
                        reconnectJob = null
                    }

                    AgentConnectionState.STOPPED,
                    AgentConnectionState.CONNECTING,
                    -> Unit
                }
            }
        }
        next.start()
    }

    private fun scheduleReconnect(target: DeviceAgentClient) {
        if (!backgroundStore.isEnabled() || reconnectJob?.isActive == true) {
            return
        }
        reconnectJob = scope.launch {
            val delayMs = reconnectBackoff.nextDelayMillis()
            delay(delayMs)
            if (
                isActive &&
                backgroundStore.isEnabled() &&
                agent === target &&
                target.state.value in setOf(
                    AgentConnectionState.ERROR,
                    AgentConnectionState.OFFLINE,
                )
            ) {
                target.start()
            }
        }
    }

    private fun startForegroundVisible(state: AgentConnectionState) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(state),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    private fun updateNotification(state: AgentConnectionState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(state))
    }

    private fun notification(state: AgentConnectionState): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, BackgroundAgentService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_feedback_connection)
            .setContentTitle("Feedback-Verbindung aktiv")
            .setContentText(notificationText(state))
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "Beenden", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hintergrundverbindung",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Sichtbarer Status der vom Nutzer aktivierten Feedback-Hintergrundverbindung"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notificationText(state: AgentConnectionState): String = when (state) {
        AgentConnectionState.STOPPED -> "Verbindung wird beendet"
        AgentConnectionState.CONNECTING -> "Verbindung zum Control Center wird hergestellt"
        AgentConnectionState.ONLINE -> "Gerät ist für erlaubte Anfragen erreichbar"
        AgentConnectionState.OFFLINE -> "Offline · erneuter Verbindungsversuch folgt"
        AgentConnectionState.UNTRUSTED_SERVER -> "Server nicht wiedererkannt · Verbindung gestoppt"
        AgentConnectionState.REVOKED -> "Gerät wurde widerrufen"
        AgentConnectionState.ERROR -> "Verbindungsfehler · erneuter Versuch folgt"
    }

    companion object {
        private const val CHANNEL_ID = "feedback.background.connection.v1"
        private const val NOTIFICATION_ID = 7101
        private const val ACTION_START = "com.redurbabat.feedback.agent.START"
        private const val ACTION_STOP = "com.redurbabat.feedback.agent.STOP"
        private const val ACTION_CAPABILITIES_CHANGED =
            "com.redurbabat.feedback.agent.CAPABILITIES_CHANGED"
        private const val ACTION_RECONNECT = "com.redurbabat.feedback.agent.RECONNECT"

        private val _state = MutableStateFlow(AgentConnectionState.STOPPED)
        val state: StateFlow<AgentConnectionState> = _state.asStateFlow()

        @Volatile
        private var currentAgent: DeviceAgentClient? = null

        /**
         * Whether the notification this service must show can actually be shown. The background
         * connection is only allowed to exist while it is visible, so this is a precondition and
         * not a nicety.
         */
        fun notificationsVisible(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context.applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED

        /** Starts only after an explicit visible-app action by the owner. */
        fun start(context: Context) {
            val appContext = context.applicationContext
            if (!notificationsVisible(appContext)) {
                BackgroundConnectionStore(appContext).setEnabled(false)
                throw SecurityException("Notification permission is required for background mode")
            }

            BackgroundConnectionStore(appContext).setEnabled(true)
            launchService(appContext)
        }

        /**
         * Continues a connection the owner already enabled, after a reboot or an app update.
         *
         * Unlike [start] this never writes the preference. A broadcast may carry on what was
         * granted; granting stays with the owner in front of the app. Whether it may run at all is
         * decided in [BootResumePolicy] before this is called.
         */
        fun resume(context: Context) {
            launchService(context.applicationContext)
        }

        private fun launchService(appContext: Context) {
            val intent = Intent(appContext, BackgroundAgentService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            BackgroundConnectionStore(appContext).setEnabled(false)
            appContext.stopService(Intent(appContext, BackgroundAgentService::class.java))
            currentAgent = null
            _state.value = AgentConnectionState.STOPPED
        }

        fun notifyCapabilitiesChanged() {
            currentAgent?.notifyCapabilitiesChanged()
        }

        fun reconnect() {
            currentAgent?.start()
        }
    }
}
