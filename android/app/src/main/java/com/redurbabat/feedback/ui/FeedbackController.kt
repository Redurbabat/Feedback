package com.redurbabat.feedback.ui

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.redurbabat.feedback.BuildConfig
import com.redurbabat.feedback.agent.AgentConnectionState
import com.redurbabat.feedback.agent.BackgroundAgentService
import com.redurbabat.feedback.agent.BackgroundConnectionStore
import com.redurbabat.feedback.agent.DeviceAgentClient
import com.redurbabat.feedback.device.SystemInfoProvider
import com.redurbabat.feedback.files.FileShare
import com.redurbabat.feedback.files.FileShareException
import com.redurbabat.feedback.files.FileShareKind
import com.redurbabat.feedback.files.FileShareStore
import com.redurbabat.feedback.files.FilesAgentFactory
import com.redurbabat.feedback.network.FeedbackHttpException
import com.redurbabat.feedback.network.OkHttpJsonTransport
import com.redurbabat.feedback.network.ServerEndpoint
import com.redurbabat.feedback.pairing.AndroidDeviceMetadataProvider
import com.redurbabat.feedback.pairing.ServerPairingClient
import com.redurbabat.feedback.pairing.ServerPairingException
import com.redurbabat.feedback.pairing.ServerPairingSession
import com.redurbabat.feedback.pairing.ServerPairingStatus
import com.redurbabat.feedback.pairing.SetupLinkArrival
import com.redurbabat.feedback.pairing.SetupLinkStore
import com.redurbabat.feedback.permissions.LocalCapabilityStore
import com.redurbabat.feedback.protocol.Capability
import com.redurbabat.feedback.screen.AndroidScreenCapture
import com.redurbabat.feedback.security.AppLockPolicy
import com.redurbabat.feedback.security.AppLockReauthPolicy
import com.redurbabat.feedback.security.AppLockSettings
import com.redurbabat.feedback.security.AppLockStore
import com.redurbabat.feedback.security.AppUnlockResult
import com.redurbabat.feedback.security.AutoLockPolicy
import com.redurbabat.feedback.security.AutoLockTimeout
import com.redurbabat.feedback.security.SensitiveAction
import com.redurbabat.feedback.security.DeviceIdentity
import com.redurbabat.feedback.security.DeviceIdentityStore
import com.redurbabat.feedback.security.DeviceRegistrationStore
import com.redurbabat.feedback.security.SecretStore
import com.redurbabat.feedback.security.StoredDeviceRegistration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PairingUiPhase {
    data object Idle : PairingUiPhase
    data object Starting : PairingUiPhase

    data class Waiting(
        val displayCode: String,
        /**
         * The `feedback://pair?v=1&ticket=...` payload. The ticket is meant to be shown - that is
         * how the browser identifies this pairing - and dies with the session. The `deviceSecret`
         * and the device token never appear in UI state.
         */
        val qrPayload: String,
        val expiresAt: String,
    ) : PairingUiPhase

    data object Claiming : PairingUiPhase
    data class Failed(val message: String) : PairingUiPhase
}

data class FeedbackUiState(
    val identity: DeviceIdentity? = null,
    val identityAvailable: Boolean = false,
    val serverUrl: String = "",
    /**
     * `host[:port]` of the server this build was made for, or null when there is none and null
     * when it could not be rendered safely. The UI turns it into a sentence the owner can read -
     * a pre-filled text field alone would show the address without saying where it came from.
     */
    val buildServerAuthority: String? = null,
    /** What a setup link did, or why it was refused. Never silence. */
    val setupLinkNotice: String? = null,
    val setupLinkRejected: Boolean = false,
    /** The owner switched setup links off entirely. */
    val setupLinksIgnored: Boolean = false,
    val pairing: PairingUiPhase = PairingUiPhase.Idle,
    val paired: Boolean = false,
    val pairedDeviceName: String? = null,
    val pairedServer: String? = null,
    val pairedPublicDeviceId: String? = null,
    val pairedAt: String? = null,
    val agentState: AgentConnectionState = AgentConnectionState.STOPPED,
    val backgroundConnectionEnabled: Boolean = false,
    val systemInfoGrantedLocally: Boolean = false,
    val filesReadGrantedLocally: Boolean = false,
    val mediaPhotosGrantedLocally: Boolean = false,
    val mediaVideosGrantedLocally: Boolean = false,
    val screenViewGrantedLocally: Boolean = false,
    /** Wire form only: the local content URI of a share never enters UI state. */
    val fileShares: List<FileShare> = emptyList(),
    /** Areas Android no longer honours. Counted so the owner sees that a share stopped working. */
    val fileSharesUnavailable: Int = 0,
    val fileShareBusy: Boolean = false,
    val appLockConfigured: Boolean = false,
    val appUnlocked: Boolean = false,
    val appLockBusy: Boolean = false,
    val appLockFailedAttempts: Int = 0,
    val appLockRemainingLockoutMillis: Long = 0L,
    val appLockError: String? = null,
    val appLockUnavailable: Boolean = false,
    val appLockSetupDeferred: Boolean = false,
    val autoLockTimeout: AutoLockTimeout = AutoLockTimeout.DEFAULT,
    val biometricUnlockEnabled: Boolean = false,
    val biometricUnlockAvailable: Boolean = false,
    val pendingSensitiveAction: SensitiveAction? = null,
    val sensitiveActionBusy: Boolean = false,
    val sensitiveActionError: String? = null,
    val globalMessage: String? = null,
)

/**
 * Coordinator for the visible Android app.
 *
 * The device token and the `deviceSecret` never enter [FeedbackUiState]; the pairing ticket does,
 * because showing it as a QR code is its purpose, and it dies with the session. The token is persisted only
 * through [DeviceRegistrationStore], which seals the complete registration with Android Keystore.
 * A persistent connection is opt-in and handed to [BackgroundAgentService], which is a visible
 * foreground service with an ongoing notification and user-accessible stop action.
 *
 * The management UI itself is fail-closed behind an app-specific PIN/passphrase. Its verifier is
 * Keystore-sealed and failed attempts are persistently rate-limited.
 */
class FeedbackController(
    context: Context,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val identityStore = DeviceIdentityStore()
    private val secretStore = SecretStore(applicationContext)
    private val registrationStore = DeviceRegistrationStore(secretStore)
    private val appLockStore = AppLockStore(applicationContext, secretStore)
    private val localCapabilityStore = LocalCapabilityStore(applicationContext)
    private val fileShareStore = FileShareStore(applicationContext, secretStore)
    private val backgroundStore = BackgroundConnectionStore(applicationContext)
    private val setupLinkStore = SetupLinkStore(applicationContext)
    private val metadata = AndroidDeviceMetadataProvider.current()
    private val systemInfoProvider = SystemInfoProvider(applicationContext)

    private val _state = MutableStateFlow(FeedbackUiState())
    val state: StateFlow<FeedbackUiState> = _state.asStateFlow()

    private var pairingJob: Job? = null
    private var agentStateJob: Job? = null
    private var appLockJob: Job? = null
    private var lockoutCountdownJob: Job? = null
    private var agent: DeviceAgentClient? = null
    private val closed = AtomicBoolean(false)

    /**
     * Monotonic timestamps. The wall clock is user settable, so auto-lock and the
     * re-authentication window are never anchored to it.
     */
    private var lastAuthenticatedAtElapsedMillis: Long? = null
    private var backgroundedAtElapsedMillis: Long? = null

    /**
     * A setup link that arrived while the UI was still locked. Held rather than applied, because
     * a locked app must not change anything, and dropped only once it has been decided on. Null
     * means no link is waiting - an arrival whose link could not be read is not null, it is an
     * arrival that will be refused out loud.
     */
    private var pendingSetupLink: SetupLinkArrival? = null

    init {
        initialize()
    }

    // ---------------------------------------------------------------- app lock

    fun configureAppLock(secret: String, confirmation: String) {
        if (_state.value.appLockBusy || _state.value.appLockConfigured || closed.get()) {
            return
        }
        if (secret != confirmation) {
            _state.value = _state.value.copy(
                appLockError = "Die beiden Eingaben stimmen nicht überein.",
            )
            return
        }
        val secretChars = secret.toCharArray()
        if (!AppLockPolicy.isAcceptableSecret(secretChars)) {
            secretChars.fill('\u0000')
            _state.value = _state.value.copy(
                appLockError = "Verwende mindestens ${AppLockPolicy.MIN_PIN_DIGITS} Ziffern " +
                    "oder eine Passphrase mit mindestens " +
                    "${AppLockPolicy.MIN_PASSPHRASE_LENGTH} Zeichen.",
            )
            return
        }

        appLockJob?.cancel()
        _state.value = _state.value.copy(appLockBusy = true, appLockError = null)
        appLockJob = scope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    try {
                        appLockStore.configure(secretChars)
                        appLockStore.writeSettings(AppLockSettings.DEFAULT)
                    } finally {
                        secretChars.fill('\u0000')
                    }
                }
            }
            _state.value = if (result.isSuccess) {
                markAuthenticated()
                _state.value.copy(
                    appLockConfigured = true,
                    appUnlocked = true,
                    appLockBusy = false,
                    appLockFailedAttempts = 0,
                    appLockRemainingLockoutMillis = 0L,
                    appLockError = null,
                    autoLockTimeout = AppLockSettings.DEFAULT.autoLockTimeout,
                    biometricUnlockEnabled = AppLockSettings.DEFAULT.biometricUnlockEnabled,
                )
            } else {
                _state.value.copy(
                    appLockBusy = false,
                    appLockError = "Der lokale App-Schutz konnte nicht sicher gespeichert werden.",
                )
            }
            flushPendingSetupLink()
        }
    }

    /** The owner postponed the setup. Nothing is unlocked by this; only the prompt is silenced. */
    fun deferAppLockSetup() {
        if (_state.value.appLockConfigured || closed.get()) {
            return
        }
        runCatching { appLockStore.setSetupDeferred(true) }
        _state.value = _state.value.copy(
            appLockSetupDeferred = true,
            appLockError = null,
            globalMessage = "Ohne App-Schutz ist die Geräteverwaltung auf einem entsperrten " +
                "Telefon frei zugänglich. Du kannst ihn jederzeit unter Sicherheit aktivieren.",
        )
        // Postponing the setup is one of the ways the management UI first becomes visible.
        flushPendingSetupLink()
    }

    /** Opens the setup screen again after it was postponed or the lock was disabled. */
    fun startAppLockSetup() {
        if (_state.value.appLockConfigured || closed.get()) {
            return
        }
        runCatching { appLockStore.setSetupDeferred(false) }
        _state.value = _state.value.copy(
            appLockSetupDeferred = false,
            appLockError = null,
            globalMessage = null,
        )
    }

    fun unlockApp(secret: String) {
        val current = _state.value
        if (!current.appLockConfigured ||
            current.appUnlocked ||
            current.appLockBusy ||
            closed.get()
        ) {
            return
        }
        val secretChars = secret.toCharArray()
        _state.value = current.copy(appLockBusy = true, appLockError = null)
        appLockJob?.cancel()
        appLockJob = scope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    try {
                        appLockStore.verify(secretChars, System.currentTimeMillis())
                    } finally {
                        secretChars.fill('\u0000')
                    }
                }
            }
            applyUnlockResult(result.getOrNull())
        }
    }

    /**
     * Completes an unlock that a successful BiometricPrompt already authorised. Biometrics are a
     * convenience path on top of an existing PIN/passphrase: they apply only when the owner enabled
     * them, and they never clear an active lockout.
     */
    fun unlockWithBiometrics() {
        val current = _state.value
        if (!current.appLockConfigured || current.appUnlocked || closed.get()) {
            return
        }
        if (!current.biometricUnlockEnabled || !current.biometricUnlockAvailable) {
            return
        }
        val now = System.currentTimeMillis()
        val status = runCatching { appLockStore.status(now) }.getOrNull()
        val remaining = status?.lockout?.remainingMillis(now) ?: 0L
        if (remaining > 0L) {
            _state.value = current.copy(appLockRemainingLockoutMillis = remaining)
            startLockoutCountdown()
            return
        }
        markAuthenticated()
        _state.value = current.copy(
            appUnlocked = true,
            appLockBusy = false,
            appLockFailedAttempts = 0,
            appLockRemainingLockoutMillis = 0L,
            appLockError = null,
        )
        flushPendingSetupLink()
    }

    fun reportBiometricUnlockFailed(message: String?) {
        if (_state.value.appUnlocked) {
            return
        }
        _state.value = _state.value.copy(
            appLockError = message ?: "Die biometrische Entsperrung wurde abgebrochen.",
        )
    }

    /** Reported by the UI once it knows whether this device can actually prompt for biometrics. */
    fun setBiometricUnlockAvailable(available: Boolean) {
        if (_state.value.biometricUnlockAvailable == available) {
            return
        }
        _state.value = _state.value.copy(biometricUnlockAvailable = available)
    }

    fun setBiometricUnlockEnabled(enabled: Boolean) {
        val current = _state.value
        if (!current.appUnlocked || !current.appLockConfigured || closed.get()) {
            return
        }
        if (enabled && !current.biometricUnlockAvailable) {
            _state.value = current.copy(
                globalMessage = "Dieses Gerät bietet keine nutzbare biometrische Entsperrung an.",
            )
            return
        }
        writeSettings(
            AppLockSettings(
                autoLockTimeout = current.autoLockTimeout,
                biometricUnlockEnabled = enabled,
            ),
        )
    }

    fun setAutoLockTimeout(timeout: AutoLockTimeout) {
        val current = _state.value
        if (!current.appUnlocked || !current.appLockConfigured || closed.get()) {
            return
        }
        writeSettings(
            AppLockSettings(
                autoLockTimeout = timeout,
                biometricUnlockEnabled = current.biometricUnlockEnabled,
            ),
        )
    }

    private fun writeSettings(settings: AppLockSettings) {
        runCatching { appLockStore.writeSettings(settings) }
            .onSuccess {
                _state.value = _state.value.copy(
                    autoLockTimeout = settings.autoLockTimeout,
                    biometricUnlockEnabled = settings.biometricUnlockEnabled,
                )
            }
            .onFailure {
                _state.value = _state.value.copy(
                    globalMessage = "Die Einstellung konnte nicht sicher gespeichert werden.",
                )
            }
    }

    fun lockApp() {
        if (_state.value.appLockConfigured && _state.value.appUnlocked) {
            lastAuthenticatedAtElapsedMillis = null
            _state.value = _state.value.copy(
                appUnlocked = false,
                appLockBusy = false,
                appLockError = null,
                pendingSensitiveAction = null,
                sensitiveActionError = null,
                sensitiveActionBusy = false,
            )
            refreshLockoutFromStore()
        }
    }

    /** Called when the app leaves the foreground; starts the auto-lock idle window. */
    fun onEnterBackground() {
        if (!_state.value.appUnlocked) {
            return
        }
        backgroundedAtElapsedMillis = elapsedRealtime()
        if (_state.value.autoLockTimeout == AutoLockTimeout.IMMEDIATE) {
            lockApp()
        }
    }

    /** Called when the app returns to the foreground; applies the auto-lock decision. */
    fun onEnterForeground() {
        val backgroundedAt = backgroundedAtElapsedMillis ?: return
        backgroundedAtElapsedMillis = null
        if (!_state.value.appUnlocked) {
            return
        }
        val shouldLock = AutoLockPolicy.shouldLockAfterBackground(
            timeout = _state.value.autoLockTimeout,
            backgroundedAtElapsedMillis = backgroundedAt,
            nowElapsedMillis = elapsedRealtime(),
        )
        if (shouldLock) {
            lockApp()
        } else {
            // The owner may have withdrawn a document grant in Android settings while we were away.
            refreshFileShares()
        }
    }

    // ------------------------------------------------- sensitive local actions

    /**
     * Requests a sensitive action. When the last unlock is too old - or when the action never
     * accepts a recent unlock - the UI asks for the secret again instead of running straight away.
     */
    fun requestSensitiveAction(action: SensitiveAction) {
        val current = _state.value
        if (!current.appUnlocked || closed.get()) {
            return
        }
        if (action == SensitiveAction.DISABLE_APP_LOCK && !current.appLockConfigured) {
            return
        }
        val needsSecret = current.appLockConfigured && AppLockReauthPolicy.requiresReauthentication(
            action = action,
            lastAuthenticatedAtElapsedMillis = lastAuthenticatedAtElapsedMillis,
            nowElapsedMillis = elapsedRealtime(),
        )
        if (needsSecret) {
            _state.value = current.copy(
                pendingSensitiveAction = action,
                sensitiveActionError = null,
                globalMessage = null,
            )
        } else {
            runSensitiveAction(action)
        }
    }

    fun cancelSensitiveAction() {
        _state.value = _state.value.copy(
            pendingSensitiveAction = null,
            sensitiveActionError = null,
            sensitiveActionBusy = false,
        )
    }

    fun confirmSensitiveAction(secret: String) {
        val current = _state.value
        val action = current.pendingSensitiveAction ?: return
        if (current.sensitiveActionBusy || !current.appUnlocked || closed.get()) {
            return
        }
        val secretChars = secret.toCharArray()
        _state.value = current.copy(sensitiveActionBusy = true, sensitiveActionError = null)
        appLockJob?.cancel()
        appLockJob = scope.launch {
            val now = System.currentTimeMillis()
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    try {
                        if (action == SensitiveAction.DISABLE_APP_LOCK) {
                            appLockStore.disable(secretChars, now)
                        } else {
                            appLockStore.verify(secretChars, now)
                        }
                    } finally {
                        secretChars.fill('\u0000')
                    }
                }
            }

            when (val outcome = result.getOrNull()) {
                AppUnlockResult.Success -> {
                    markAuthenticated()
                    _state.value = _state.value.copy(
                        pendingSensitiveAction = null,
                        sensitiveActionBusy = false,
                        sensitiveActionError = null,
                    )
                    runSensitiveAction(action)
                }

                is AppUnlockResult.Invalid -> {
                    _state.value = _state.value.copy(
                        sensitiveActionBusy = false,
                        appLockFailedAttempts = outcome.failedAttempts,
                        sensitiveActionError = invalidSecretMessage(outcome.failedAttempts),
                    )
                    // A failure that triggered the rate limit also drops back to the lock screen,
                    // so the wait cannot be sat out inside an already unlocked session.
                    if (outcome.lockout.isActive) {
                        lockApp()
                    }
                }

                is AppUnlockResult.Locked -> {
                    _state.value = _state.value.copy(sensitiveActionBusy = false)
                    lockApp()
                }

                AppUnlockResult.NotConfigured, null -> {
                    _state.value = _state.value.copy(
                        sensitiveActionBusy = false,
                        sensitiveActionError = "Die Eingabe konnte nicht geprüft werden.",
                    )
                }
            }
        }
    }

    private fun runSensitiveAction(action: SensitiveAction) {
        when (action) {
            SensitiveAction.DISABLE_APP_LOCK -> {
                // disable() already removed the verifier when the secret was correct.
                lastAuthenticatedAtElapsedMillis = null
                runCatching { appLockStore.setSetupDeferred(true) }
                _state.value = _state.value.copy(
                    appLockConfigured = false,
                    appUnlocked = true,
                    appLockFailedAttempts = 0,
                    appLockRemainingLockoutMillis = 0L,
                    autoLockTimeout = AppLockSettings.DEFAULT.autoLockTimeout,
                    biometricUnlockEnabled = false,
                    appLockSetupDeferred = true,
                    globalMessage = "App-Schutz deaktiviert. Die Geräteverwaltung ist jetzt ohne " +
                        "lokale Eingabe erreichbar.",
                )
            }

            SensitiveAction.FORGET_REGISTRATION -> forgetLocalRegistrationConfirmed()
            SensitiveAction.GRANT_SYSTEM_INFO ->
                applyCapabilityGrant(Capability.SYSTEM_INFO, true)
            SensitiveAction.GRANT_FILES_READ ->
                applyCapabilityGrant(Capability.FILES_READ, true)
            SensitiveAction.GRANT_MEDIA_PHOTOS ->
                applyCapabilityGrant(Capability.MEDIA_PHOTOS_READ, true)
            SensitiveAction.GRANT_MEDIA_VIDEOS ->
                applyCapabilityGrant(Capability.MEDIA_VIDEOS_READ, true)
            SensitiveAction.GRANT_SCREEN_VIEW ->
                applyCapabilityGrant(Capability.SCREEN_VIEW, true)
        }
    }

    // ------------------------------------------------------------- lock helpers

    private fun applyUnlockResult(outcome: AppUnlockResult?) {
        val now = System.currentTimeMillis()
        _state.value = when (outcome) {
            AppUnlockResult.Success -> {
                markAuthenticated()
                _state.value.copy(
                    appUnlocked = true,
                    appLockBusy = false,
                    appLockFailedAttempts = 0,
                    appLockRemainingLockoutMillis = 0L,
                    appLockError = null,
                )
            }

            AppUnlockResult.NotConfigured -> _state.value.copy(
                appLockConfigured = false,
                appUnlocked = false,
                appLockBusy = false,
                appLockRemainingLockoutMillis = 0L,
                appLockError = "Die App-Sperre muss neu eingerichtet werden.",
            )

            is AppUnlockResult.Invalid -> _state.value.copy(
                appUnlocked = false,
                appLockBusy = false,
                appLockFailedAttempts = outcome.failedAttempts,
                appLockRemainingLockoutMillis = outcome.lockout.remainingMillis(now),
                appLockError = invalidSecretMessage(outcome.failedAttempts),
            )

            is AppUnlockResult.Locked -> _state.value.copy(
                appUnlocked = false,
                appLockBusy = false,
                appLockRemainingLockoutMillis = outcome.lockout.remainingMillis(now),
                appLockError = null,
            )

            null -> _state.value.copy(
                appUnlocked = false,
                appLockBusy = false,
                appLockError = "Die lokale App-Sperre konnte nicht geprüft werden.",
            )
        }
        startLockoutCountdown()
        // A setup link typically arrives at a locked app - tapping the link is what opens it.
        // Called unconditionally: the flush itself checks whether the UI is actually unlocked.
        flushPendingSetupLink()
    }

    private fun invalidSecretMessage(failedAttempts: Int): String {
        val free = AppLockPolicy.remainingFreeAttempts(failedAttempts)
        return when {
            free > 1 -> "Falsche Eingabe. Noch $free Versuche bis zur Wartezeit."
            free == 1 -> "Falsche Eingabe. Noch 1 Versuch bis zur Wartezeit."
            else -> "Falsche Eingabe. Zu viele Fehlversuche."
        }
    }

    private fun refreshLockoutFromStore() {
        val now = System.currentTimeMillis()
        val status = runCatching { appLockStore.status(now) }.getOrNull() ?: return
        _state.value = _state.value.copy(
            appLockFailedAttempts = status.failedAttempts,
            appLockRemainingLockoutMillis = status.lockout.remainingMillis(now),
        )
        startLockoutCountdown()
    }

    /** Ticks the visible "erneut versuchen in mm:ss" countdown down to zero. */
    private fun startLockoutCountdown() {
        lockoutCountdownJob?.cancel()
        if (_state.value.appLockRemainingLockoutMillis <= 0L) {
            return
        }
        lockoutCountdownJob = scope.launch {
            while (_state.value.appLockRemainingLockoutMillis > 0L && !closed.get()) {
                delay(LOCKOUT_TICK_MILLIS)
                val now = System.currentTimeMillis()
                val status = runCatching { appLockStore.status(now) }.getOrNull()
                _state.value = _state.value.copy(
                    appLockRemainingLockoutMillis = status?.lockout?.remainingMillis(now) ?: 0L,
                )
            }
        }
    }

    private fun markAuthenticated() {
        lastAuthenticatedAtElapsedMillis = elapsedRealtime()
    }

    // ------------------------------------------------------------- setup links

    /**
     * Takes one arrived setup link, as read and rebuilt by
     * [com.redurbabat.feedback.pairing.SetupLinkActivity]. Every call is a link that reached the
     * app; whether it could be read is carried inside [SetupLinkArrival], not by the absence of a
     * call, so an unreadable one still gets an answer on screen.
     *
     * Applying is deliberately not done here. [setServerUrl] returns immediately while the app is
     * locked, and a locked app is exactly the state a tapped link arrives in - the owner taps the
     * link, the app opens, the lock screen is what they see. Dropping the link there would make
     * the feature work only for owners without a local lock. So it is held and decided later, in
     * the one place that decides.
     */
    fun applySetupLink(arrival: SetupLinkArrival) {
        if (closed.get()) {
            return
        }
        pendingSetupLink = arrival
        flushPendingSetupLink()
    }

    /**
     * The single point where a buffered setup link is applied. Called once when the link arrives
     * and again from every path that unlocks the UI, so a link that waited behind the lock is not
     * lost and is never applied twice.
     *
     * What the arrival means is [SetupLinkPresentation]'s answer, and the origin rule behind it is
     * the pairing module's - neither belongs in a controller method. What is left here is writing
     * the outcome into the state, and there is no branch that writes nothing.
     */
    private fun flushPendingSetupLink() {
        if (closed.get() || !_state.value.appUnlocked) {
            return
        }
        val arrival = pendingSetupLink ?: return
        pendingSetupLink = null

        val current = _state.value
        val outcome = SetupLinkPresentation.outcome(
            arrival = arrival,
            buildDefaultOrigin = BuildConfig.DEFAULT_SERVER_URL,
            registeredOrigin = current.pairedServer,
            linksIgnored = current.setupLinksIgnored,
            paired = current.paired,
            pairingInProgress = current.pairing !is PairingUiPhase.Idle &&
                current.pairing !is PairingUiPhase.Failed,
        )
        when (outcome) {
            is SetupLinkOutcome.Announced -> publishSetupLinkOutcome(
                message = outcome.message,
                rejected = outcome.rejected,
            )

            is SetupLinkOutcome.FillsAddressField -> _state.value = current.copy(
                serverUrl = outcome.server.baseUrl,
                pairing = PairingUiPhase.Idle,
                setupLinkNotice = outcome.notice,
                setupLinkRejected = false,
            )
        }
    }

    /**
     * The revocation path for this feature: setup links off, persistently, with no way for a link
     * to turn them back on.
     */
    fun setSetupLinksIgnored(ignored: Boolean) {
        if (!_state.value.appUnlocked || closed.get()) {
            return
        }
        if (ignored) {
            pendingSetupLink = null
        }
        runCatching { setupLinkStore.setIgnored(ignored) }
            .onSuccess {
                _state.value = _state.value.copy(
                    setupLinksIgnored = ignored,
                    setupLinkNotice = null,
                    setupLinkRejected = false,
                )
                if (!ignored) {
                    flushPendingSetupLink()
                }
            }
            .onFailure {
                _state.value = _state.value.copy(
                    globalMessage = "Die Einstellung konnte nicht gespeichert werden.",
                )
            }
    }

    /**
     * The outcome goes where the owner can actually see it. The pairing card, and with it the
     * setup-link line, is only on screen while the device is unpaired.
     */
    private fun publishSetupLinkOutcome(message: String, rejected: Boolean) {
        _state.value = if (_state.value.paired) {
            _state.value.copy(globalMessage = message)
        } else {
            _state.value.copy(setupLinkNotice = message, setupLinkRejected = rejected)
        }
    }

    fun setServerUrl(value: String) {
        if (!_state.value.appUnlocked) {
            return
        }
        if (_state.value.pairing is PairingUiPhase.Starting ||
            _state.value.pairing is PairingUiPhase.Waiting ||
            _state.value.pairing is PairingUiPhase.Claiming
        ) {
            return
        }
        _state.value = _state.value.copy(
            serverUrl = value,
            pairing = if (_state.value.pairing is PairingUiPhase.Failed) {
                PairingUiPhase.Idle
            } else {
                _state.value.pairing
            },
            // Typing over the address answers whatever the link said, so the line about it goes.
            setupLinkNotice = null,
            setupLinkRejected = false,
            globalMessage = null,
        )
    }

    fun startPairing() {
        if (closed.get() || !_state.value.appUnlocked || !_state.value.identityAvailable || _state.value.paired) {
            return
        }
        pairingJob?.cancel()
        pairingJob = scope.launch {
            val endpoint = try {
                ServerEndpoint.parse(_state.value.serverUrl)
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    pairing = PairingUiPhase.Failed(
                        "Bitte eine gültige HTTPS-Adresse des Feedback-Servers eingeben.",
                    ),
                )
                return@launch
            }

            _state.value = _state.value.copy(
                serverUrl = endpoint.baseUrl,
                pairing = PairingUiPhase.Starting,
                globalMessage = null,
            )

            val client = ServerPairingClient(
                transport = OkHttpJsonTransport(endpoint),
                identityStore = identityStore,
            )

            val session = try {
                client.start(metadata)
            } catch (error: Exception) {
                failPairing(error)
                return@launch
            }

            _state.value = _state.value.copy(
                pairing = PairingUiPhase.Waiting(
                    displayCode = session.displayCode,
                    qrPayload = session.qrPayload,
                    expiresAt = session.expiresAt,
                ),
            )

            pollUntilFinished(endpoint, client, session)
        }
    }

    fun cancelPairing() {
        if (!_state.value.appUnlocked) {
            return
        }
        pairingJob?.cancel()
        pairingJob = null
        if (!_state.value.paired) {
            _state.value = _state.value.copy(pairing = PairingUiPhase.Idle, globalMessage = null)
        }
    }

    fun setSystemInfoGranted(granted: Boolean) {
        setCapabilityGranted(Capability.SYSTEM_INFO, granted)
    }

    // ------------------------------------------------- local read capabilities

    /**
     * The local half of a read capability.
     *
     * Granting is gated behind the app-lock secret; withdrawing is not. One generic path rather
     * than one method per capability: four near-copies would be four places to forget a check.
     */
    fun setCapabilityGranted(capability: Capability, granted: Boolean) {
        if (!_state.value.appUnlocked || !_state.value.paired) {
            return
        }
        if (granted) {
            val action = grantActionFor(capability) ?: return
            requestSensitiveAction(action)
        } else {
            applyCapabilityGrant(capability, false)
        }
    }

    private fun grantActionFor(capability: Capability): SensitiveAction? = when (capability) {
        Capability.SYSTEM_INFO -> SensitiveAction.GRANT_SYSTEM_INFO
        Capability.FILES_READ -> SensitiveAction.GRANT_FILES_READ
        Capability.MEDIA_PHOTOS_READ -> SensitiveAction.GRANT_MEDIA_PHOTOS
        Capability.MEDIA_VIDEOS_READ -> SensitiveAction.GRANT_MEDIA_VIDEOS
        Capability.SCREEN_VIEW -> SensitiveAction.GRANT_SCREEN_VIEW
        else -> null
    }

    private fun applyCapabilityGrant(capability: Capability, granted: Boolean) {
        if (!_state.value.appUnlocked || !_state.value.paired) {
            return
        }
        runCatching {
            localCapabilityStore.setGranted(capability, granted)
        }.onSuccess {
            _state.value = when (capability) {
                Capability.SYSTEM_INFO -> _state.value.copy(systemInfoGrantedLocally = granted)
                Capability.FILES_READ -> _state.value.copy(filesReadGrantedLocally = granted)
                Capability.MEDIA_PHOTOS_READ ->
                    _state.value.copy(mediaPhotosGrantedLocally = granted)
                Capability.MEDIA_VIDEOS_READ ->
                    _state.value.copy(mediaVideosGrantedLocally = granted)
                Capability.SCREEN_VIEW -> _state.value.copy(screenViewGrantedLocally = granted)
                else -> _state.value
            }
            notifyAgentCapabilitiesChanged()
        }.onFailure {
            _state.value = _state.value.copy(
                globalMessage = "Die lokale Freigabe konnte nicht gespeichert werden.",
            )
        }
    }

    /**
     * Records an area the owner just picked in Android's own document picker.
     *
     * The URI arrives from the system picker, so the consent already happened outside this app.
     * What still has to hold here is that Android hands over a persistable read grant and a name
     * the protocol accepts - otherwise the pick is refused rather than repaired.
     */
    fun addFileShare(uri: Uri, kind: FileShareKind) {
        val current = _state.value
        if (!current.appUnlocked || !current.paired || current.fileShareBusy || closed.get()) {
            return
        }
        if (current.fileShares.size >= FileShareStore.MAX_SHARES) {
            _state.value = current.copy(
                globalMessage = "Es sind höchstens ${FileShareStore.MAX_SHARES} freigegebene " +
                    "Bereiche möglich. Entferne zuerst einen davon.",
            )
            return
        }

        _state.value = current.copy(fileShareBusy = true, globalMessage = null)
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    val name = fileShareStore.resolveDisplayName(uri, kind)
                        ?: throw FileShareException("Android reported no usable display name")
                    fileShareStore.add(uri, kind, name, System.currentTimeMillis())
                    name
                }
            }
            val message = outcome.fold(
                onSuccess = { name -> "\"$name\" ist jetzt freigegeben." },
                onFailure = {
                    "Der Bereich konnte nicht freigegeben werden. Android hat keinen dauerhaften " +
                        "Lesezugriff oder keinen verwendbaren Namen geliefert."
                },
            )
            reloadFileShares(message)
        }
    }

    /**
     * Records a photo-picker selection as one shared area.
     *
     * The picker is Android's own consent surface and needs no runtime permission: it hands over
     * exactly what the owner selected. Photos and videos are separate selections because
     * media.photos.read and media.videos.read are separate capabilities.
     */
    fun addMediaShare(uris: List<Uri>, capability: Capability) {
        val current = _state.value
        if (!current.appUnlocked || !current.paired || current.fileShareBusy || closed.get()) {
            return
        }
        if (capability != Capability.MEDIA_PHOTOS_READ && capability != Capability.MEDIA_VIDEOS_READ) {
            return
        }
        if (current.fileShares.size >= FileShareStore.MAX_SHARES) {
            _state.value = current.copy(
                globalMessage = "Es sind höchstens ${FileShareStore.MAX_SHARES} freigegebene " +
                    "Bereiche möglich. Entferne zuerst einen davon.",
            )
            return
        }

        val label = if (capability == Capability.MEDIA_PHOTOS_READ) "Fotoauswahl" else "Videoauswahl"
        val displayName = "$label (${uris.size})"

        _state.value = current.copy(fileShareBusy = true, globalMessage = null)
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    fileShareStore.addCollection(uris, capability, displayName, System.currentTimeMillis())
                }
            }
            val message = outcome.fold(
                onSuccess = { share ->
                    if (share.persistent) {
                        "\"$displayName\" ist jetzt freigegeben."
                    } else {
                        // Said plainly rather than hidden: the owner should know this one may not
                        // survive a restart.
                        "\"$displayName\" ist freigegeben. Android hat dafür keinen dauerhaften " +
                            "Zugriff erteilt - nach einem Neustart der App kann die Auswahl " +
                            "erneut nötig sein."
                    }
                },
                onFailure = {
                    "Die Auswahl konnte nicht freigegeben werden."
                },
            )
            reloadFileShares(message)
        }
    }

    /** Withdraws one area and releases the Android permission that went with it. */
    fun removeFileShare(shareId: String) {
        val current = _state.value
        if (!current.appUnlocked || current.fileShareBusy || closed.get()) {
            return
        }
        val name = current.fileShares.firstOrNull { it.shareId == shareId }?.displayName
        _state.value = current.copy(fileShareBusy = true, globalMessage = null)
        scope.launch {
            withContext(Dispatchers.Default) {
                runCatching { fileShareStore.remove(shareId) }
            }
            reloadFileShares(
                if (name == null) {
                    "Freigabe entfernt."
                } else {
                    "\"$name\" ist nicht mehr freigegeben."
                },
            )
        }
    }

    /** Drops records for grants Android already withdrew. Nothing readable is removed. */
    fun forgetUnavailableFileShares() {
        val current = _state.value
        if (!current.appUnlocked || current.fileShareBusy || closed.get()) {
            return
        }
        _state.value = current.copy(fileShareBusy = true, globalMessage = null)
        scope.launch {
            val removed = withContext(Dispatchers.Default) {
                runCatching { fileShareStore.forgetUnavailable() }.getOrDefault(0)
            }
            reloadFileShares(
                if (removed <= 0) null else "Nicht mehr erreichbare Freigaben entfernt.",
            )
        }
    }

    /** Re-reads the inventory, so a grant revoked in Android settings shows up here. */
    fun refreshFileShares() {
        if (!_state.value.paired || _state.value.fileShareBusy || closed.get()) {
            return
        }
        scope.launch { reloadFileShares(null) }
    }

    private suspend fun reloadFileShares(message: String?) {
        val inventory = withContext(Dispatchers.Default) {
            runCatching { fileShareStore.inventory() }.getOrNull()
        }
        if (closed.get()) {
            return
        }
        _state.value = _state.value.copy(
            fileShares = inventory?.available.orEmpty().map { it.toWire() },
            fileSharesUnavailable = inventory?.unavailableCount ?: 0,
            fileShareBusy = false,
            globalMessage = message ?: _state.value.globalMessage,
        )
    }

    private fun notifyAgentCapabilitiesChanged() {
        if (_state.value.backgroundConnectionEnabled) {
            BackgroundAgentService.notifyCapabilitiesChanged()
        } else {
            agent?.notifyCapabilitiesChanged()
        }
    }

    fun setBackgroundConnectionEnabled(enabled: Boolean) {
        if (!_state.value.appUnlocked || !_state.value.paired || closed.get()) {
            return
        }
        if (enabled == _state.value.backgroundConnectionEnabled) {
            return
        }

        val stored = runCatching { registrationStore.load() }.getOrNull()
        if (stored == null) {
            _state.value = _state.value.copy(
                paired = false,
                backgroundConnectionEnabled = false,
                globalMessage = "Keine gültige lokale Registrierung gefunden. Bitte erneut koppeln.",
            )
            return
        }

        if (enabled) {
            agentStateJob?.cancel()
            agentStateJob = null
            agent?.stop()
            agent = null
            try {
                BackgroundAgentService.start(applicationContext)
                _state.value = _state.value.copy(
                    backgroundConnectionEnabled = true,
                    agentState = AgentConnectionState.CONNECTING,
                    globalMessage = "Hintergrundverbindung aktiviert. Android zeigt dafür eine dauerhafte Benachrichtigung.",
                )
                observeBackgroundAgent()
            } catch (_: Exception) {
                backgroundStore.setEnabled(false)
                _state.value = _state.value.copy(
                    backgroundConnectionEnabled = false,
                    globalMessage = "Die Hintergrundverbindung konnte von Android nicht gestartet werden.",
                )
                startAgent(stored)
            }
        } else {
            BackgroundAgentService.stop(applicationContext)
            _state.value = _state.value.copy(
                backgroundConnectionEnabled = false,
                globalMessage = "Hintergrundverbindung beendet. Solange die App geöffnet ist, bleibt der Agent verbunden.",
            )
            startAgent(stored)
        }
    }

    fun reportBackgroundNotificationPermissionDenied() {
        if (!_state.value.appUnlocked) {
            return
        }
        _state.value = _state.value.copy(
            backgroundConnectionEnabled = false,
            globalMessage = "Für die sichtbare Hintergrundverbindung sind Benachrichtigungen erforderlich. Die Verbindung wurde nicht aktiviert.",
        )
    }

    fun reconnectAgent() {
        if (!_state.value.appUnlocked || !_state.value.paired || closed.get()) {
            return
        }
        if (_state.value.backgroundConnectionEnabled) {
            BackgroundAgentService.reconnect()
        } else {
            agent?.start()
        }
    }

    /**
     * Removes only the local credential. The server-side device record is intentionally left
     * untouched; owners should use the Control Center revoke action when they want global revoke.
     */
    fun forgetLocalRegistration() {
        requestSensitiveAction(SensitiveAction.FORGET_REGISTRATION)
    }

    private fun forgetLocalRegistrationConfirmed() {
        if (!_state.value.appUnlocked) {
            return
        }
        pairingJob?.cancel()
        pairingJob = null
        agentStateJob?.cancel()
        agentStateJob = null
        BackgroundAgentService.stop(applicationContext)
        backgroundStore.setEnabled(false)
        agent?.stop()
        agent = null
        registrationStore.clear()
        localCapabilityStore.clear()
        // Consent does not survive the pairing it was given for. Re-pairing starts from nothing
        // shared, rather than silently reviving areas the owner picked for an older registration.
        runCatching { fileShareStore.clear() }
        _state.value = _state.value.copy(
            pairing = PairingUiPhase.Idle,
            paired = false,
            pairedDeviceName = null,
            pairedServer = null,
            pairedPublicDeviceId = null,
            pairedAt = null,
            agentState = AgentConnectionState.STOPPED,
            backgroundConnectionEnabled = false,
            systemInfoGrantedLocally = false,
            filesReadGrantedLocally = false,
            mediaPhotosGrantedLocally = false,
            mediaVideosGrantedLocally = false,
            screenViewGrantedLocally = false,
            fileShares = emptyList(),
            fileSharesUnavailable = 0,
            globalMessage = "Lokale Kopplung entfernt und alle Dateifreigaben aufgehoben. " +
                "Für einen globalen Widerruf das Control Center verwenden.",
        )
    }

    fun clearMessage() {
        if (_state.value.appUnlocked) {
            _state.value = _state.value.copy(globalMessage = null)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        pairingJob?.cancel()
        agentStateJob?.cancel()
        appLockJob?.cancel()
        lockoutCountdownJob?.cancel()
        agent?.stop()
        agent = null
        scope.cancel()
    }

    private fun initialize() {
        val identity = runCatching { identityStore.loadOrCreate() }.getOrNull()
        val stored = runCatching { registrationStore.load() }.getOrNull()
        val backgroundEnabled = stored != null && backgroundStore.isEnabled()
        val now = System.currentTimeMillis()
        // Fail closed: when the lock state cannot be read the UI stays locked rather than open.
        val appLockStatusResult = runCatching { appLockStore.status(now) }
        val appLockStatus = appLockStatusResult.getOrNull()
        val appLockConfigured = appLockStatus?.configured ?: true
        val settings = appLockStatus?.settings ?: AppLockSettings.DEFAULT
        // The server this build was made for. Empty when the build carries none, in which case
        // nothing is pre-filled and every setup link is refused for want of anything to match.
        val buildServer = ServerEndpoint.parseOrNull(BuildConfig.DEFAULT_SERVER_URL)
        _state.value = FeedbackUiState(
            identity = identity,
            identityAvailable = identity != null,
            serverUrl = stored?.endpoint?.baseUrl ?: buildServer?.baseUrl.orEmpty(),
            buildServerAuthority = buildServer?.let(SetupLinkPresentation::displayAuthority),
            setupLinksIgnored = runCatching { setupLinkStore.isIgnored() }.getOrDefault(false),
            paired = stored != null,
            pairedDeviceName = stored?.registration?.name,
            pairedServer = stored?.endpoint?.baseUrl,
            pairedPublicDeviceId = stored?.registration?.publicDeviceId,
            pairedAt = stored?.registration?.pairedAt,
            backgroundConnectionEnabled = backgroundEnabled,
            systemInfoGrantedLocally = localCapabilityStore.granted().contains(Capability.SYSTEM_INFO),
            filesReadGrantedLocally = localCapabilityStore.granted().contains(Capability.FILES_READ),
            mediaPhotosGrantedLocally =
                localCapabilityStore.granted().contains(Capability.MEDIA_PHOTOS_READ),
            mediaVideosGrantedLocally =
                localCapabilityStore.granted().contains(Capability.MEDIA_VIDEOS_READ),
            screenViewGrantedLocally =
                localCapabilityStore.granted().contains(Capability.SCREEN_VIEW),
            appLockConfigured = appLockConfigured,
            // An unlocked session is process local and is never restored after a restart.
            appUnlocked = AutoLockPolicy.shouldLockOnStart(appLockConfigured).not(),
            appLockFailedAttempts = appLockStatus?.failedAttempts ?: 0,
            appLockRemainingLockoutMillis = appLockStatus?.lockout?.remainingMillis(now) ?: 0L,
            appLockUnavailable = appLockStatusResult.isFailure,
            appLockSetupDeferred = appLockStatus?.setupDeferred ?: false,
            autoLockTimeout = settings.autoLockTimeout,
            biometricUnlockEnabled = settings.biometricUnlockEnabled,
            appLockError = if (appLockStatusResult.isFailure) {
                "Die vorhandene App-Sperre konnte nicht sicher geladen werden. " +
                    "Die Oberfläche bleibt gesperrt."
            } else {
                null
            },
            globalMessage = if (identity == null) {
                "Die Geräteidentität konnte nicht aus dem Android Keystore geladen werden."
            } else {
                null
            },
        )
        startLockoutCountdown()

        if (stored != null) {
            if (backgroundEnabled) {
                try {
                    BackgroundAgentService.start(applicationContext)
                    observeBackgroundAgent()
                } catch (_: Exception) {
                    backgroundStore.setEnabled(false)
                    _state.value = _state.value.copy(
                        backgroundConnectionEnabled = false,
                        globalMessage = "Die gespeicherte Hintergrundverbindung konnte nicht gestartet werden. Der Agent läuft nur solange die App geöffnet ist.",
                    )
                    startAgent(stored)
                }
            } else {
                startAgent(stored)
            }
            refreshFileShares()
        }
    }

    private suspend fun pollUntilFinished(
        endpoint: ServerEndpoint,
        client: ServerPairingClient,
        session: ServerPairingSession,
    ) {
        while (true) {
            delay(session.pollIntervalMs)
            val status = try {
                client.status(session)
            } catch (error: Exception) {
                failPairing(error)
                return
            }

            when (status) {
                ServerPairingStatus.PENDING -> Unit
                ServerPairingStatus.APPROVED -> {
                    _state.value = _state.value.copy(pairing = PairingUiPhase.Claiming)
                    val registration = try {
                        client.claim(session)
                    } catch (error: Exception) {
                        failPairing(error)
                        return
                    }
                    try {
                        registrationStore.save(endpoint, registration)
                    } catch (_: Exception) {
                        _state.value = _state.value.copy(
                            pairing = PairingUiPhase.Failed(
                                "Die Kopplung wurde bestätigt, aber das Geräte-Token konnte nicht sicher gespeichert werden.",
                            ),
                        )
                        return
                    }
                    val stored = StoredDeviceRegistration(endpoint, registration)
                    _state.value = _state.value.copy(
                        pairing = PairingUiPhase.Idle,
                        paired = true,
                        pairedDeviceName = registration.name,
                        pairedServer = endpoint.baseUrl,
                        pairedPublicDeviceId = registration.publicDeviceId,
                        pairedAt = registration.pairedAt,
                        backgroundConnectionEnabled = false,
                        globalMessage = "Gerät erfolgreich gekoppelt.",
                    )
                    backgroundStore.setEnabled(false)
                    startAgent(stored)
                    return
                }

                ServerPairingStatus.REJECTED -> {
                    _state.value = _state.value.copy(
                        pairing = PairingUiPhase.Failed("Die Kopplung wurde im Control Center abgelehnt."),
                    )
                    return
                }

                ServerPairingStatus.EXPIRED -> {
                    _state.value = _state.value.copy(
                        pairing = PairingUiPhase.Failed("Der Kopplungscode ist abgelaufen. Bitte erneut starten."),
                    )
                    return
                }

                ServerPairingStatus.CONSUMED -> {
                    _state.value = _state.value.copy(
                        pairing = PairingUiPhase.Failed(
                            "Diese Kopplung wurde bereits verwendet. Bitte einen neuen Code erzeugen.",
                        ),
                    )
                    return
                }
            }
        }
    }

    private fun startAgent(stored: StoredDeviceRegistration) {
        agentStateJob?.cancel()
        agent?.stop()
        val next = DeviceAgentClient(
            storedRegistration = stored,
            metadata = metadata,
            localCapabilities = localCapabilityStore,
            systemInfoProvider = systemInfoProvider,
            registrationStore = registrationStore,
            filesHandler = FilesAgentFactory.create(applicationContext, secretStore),
            screenCapture = AndroidScreenCapture(applicationContext),
            displaySize = { AndroidScreenCapture.displaySize(applicationContext) },
        )
        agent = next
        agentStateJob = scope.launch {
            next.state.collect { connectionState ->
                applyAgentState(connectionState)
            }
        }
        next.start()
    }

    private fun observeBackgroundAgent() {
        agentStateJob?.cancel()
        agentStateJob = scope.launch {
            BackgroundAgentService.state.collect { connectionState ->
                if (closed.get()) {
                    return@collect
                }
                if (
                    connectionState == AgentConnectionState.STOPPED &&
                    _state.value.backgroundConnectionEnabled &&
                    !backgroundStore.isEnabled()
                ) {
                    _state.value = _state.value.copy(backgroundConnectionEnabled = false)
                    val stored = runCatching { registrationStore.load() }.getOrNull()
                    if (stored != null) {
                        startAgent(stored)
                    }
                    return@collect
                }
                applyAgentState(connectionState)
            }
        }
    }

    private fun applyAgentState(connectionState: AgentConnectionState) {
        if (closed.get()) {
            return
        }
        if (connectionState == AgentConnectionState.UNTRUSTED_SERVER) {
            // Deliberately not clearing the registration: the owner decides what happened. It may
            // be a server restored without its key, and it may be that the address changed hands
            // (THREAT_MODEL 4.20, 4.21). Either way the token stays put and nothing reconnects.
            _state.value = _state.value.copy(
                agentState = connectionState,
                globalMessage = "Die Verbindung wurde gestoppt: unter dieser Adresse antwortet " +
                    "nicht mehr der Server, mit dem dieses Gerät gekoppelt wurde. Es wurde nichts " +
                    "gesendet. Wenn du den Server neu aufgesetzt hast, koppele das Gerät bewusst " +
                    "neu; sonst prüfe, wem die Adresse jetzt gehört.",
            )
            return
        }
        if (connectionState == AgentConnectionState.REVOKED ||
            connectionState == AgentConnectionState.PAIRING_ENDED
        ) {
            backgroundStore.setEnabled(false)
            localCapabilityStore.clear()
            _state.value = _state.value.copy(
                paired = false,
                pairedDeviceName = null,
                pairedServer = null,
                pairedPublicDeviceId = null,
                pairedAt = null,
                agentState = connectionState,
                backgroundConnectionEnabled = false,
                systemInfoGrantedLocally = false,
                // Two different sentences on purpose. Both end the pairing, but only one of them
                // is something the owner did, and telling them they revoked a device they never
                // touched sends them looking for a mistake that was never made.
                globalMessage = if (connectionState == AgentConnectionState.REVOKED) {
                    "Dieses Gerät wurde im Control Center widerrufen."
                } else {
                    "Der Server nimmt die Anmeldung dieses Geräts nicht mehr an. Das passiert, " +
                        "wenn ein Gerät sehr lange nicht verbunden war. Koppele es einfach neu."
                },
            )
        } else {
            _state.value = _state.value.copy(agentState = connectionState)
        }
    }

    private fun failPairing(error: Exception) {
        _state.value = _state.value.copy(
            pairing = PairingUiPhase.Failed(pairingErrorMessage(error)),
        )
    }

    private fun pairingErrorMessage(error: Exception): String = when (error) {
        is FeedbackHttpException -> when (error.protocolCode) {
            "RATE_LIMITED" -> "Zu viele Kopplungsversuche. Bitte kurz warten und erneut versuchen."
            "PAIRING_EXPIRED" -> "Die Kopplung ist abgelaufen. Bitte erneut starten."
            "PAIRING_ALREADY_USED" -> "Diese Kopplung wurde bereits verwendet. Bitte erneut starten."
            "INVALID_MESSAGE" -> "Der Server hat die Geräteanfrage abgelehnt. Server- und App-Version prüfen."
            else -> "Der Feedback-Server hat die Anfrage abgelehnt (${error.statusCode})."
        }

        is ServerPairingException -> "Die sichere Kopplung mit dem Server ist fehlgeschlagen."
        else -> "Der Server ist nicht erreichbar oder die TLS-Verbindung konnte nicht aufgebaut werden."
    }

    private companion object {
        const val LOCKOUT_TICK_MILLIS = 500L
    }
}
