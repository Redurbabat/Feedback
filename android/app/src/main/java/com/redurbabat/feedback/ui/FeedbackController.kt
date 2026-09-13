package com.redurbabat.feedback.ui

import android.content.Context
import android.os.SystemClock
import com.redurbabat.feedback.agent.AgentConnectionState
import com.redurbabat.feedback.agent.BackgroundAgentService
import com.redurbabat.feedback.agent.BackgroundConnectionStore
import com.redurbabat.feedback.agent.DeviceAgentClient
import com.redurbabat.feedback.device.SystemInfoProvider
import com.redurbabat.feedback.network.FeedbackHttpException
import com.redurbabat.feedback.network.OkHttpJsonTransport
import com.redurbabat.feedback.network.ServerEndpoint
import com.redurbabat.feedback.pairing.AndroidDeviceMetadataProvider
import com.redurbabat.feedback.pairing.ServerPairingClient
import com.redurbabat.feedback.pairing.ServerPairingException
import com.redurbabat.feedback.pairing.ServerPairingSession
import com.redurbabat.feedback.pairing.ServerPairingStatus
import com.redurbabat.feedback.permissions.LocalCapabilityStore
import com.redurbabat.feedback.protocol.Capability
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
    val pairing: PairingUiPhase = PairingUiPhase.Idle,
    val paired: Boolean = false,
    val pairedDeviceName: String? = null,
    val pairedServer: String? = null,
    val pairedPublicDeviceId: String? = null,
    val pairedAt: String? = null,
    val agentState: AgentConnectionState = AgentConnectionState.STOPPED,
    val backgroundConnectionEnabled: Boolean = false,
    val systemInfoGrantedLocally: Boolean = false,
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
    private val backgroundStore = BackgroundConnectionStore(applicationContext)
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
            SensitiveAction.GRANT_SYSTEM_INFO -> applySystemInfoGrant(true)
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
        if (!_state.value.appUnlocked || !_state.value.paired) {
            return
        }
        if (granted) {
            // Handing out a capability is gated; withdrawing one never is.
            requestSensitiveAction(SensitiveAction.GRANT_SYSTEM_INFO)
        } else {
            applySystemInfoGrant(false)
        }
    }

    private fun applySystemInfoGrant(granted: Boolean) {
        if (!_state.value.appUnlocked || !_state.value.paired) {
            return
        }
        runCatching {
            localCapabilityStore.setGranted(Capability.SYSTEM_INFO, granted)
        }.onSuccess {
            _state.value = _state.value.copy(systemInfoGrantedLocally = granted)
            if (_state.value.backgroundConnectionEnabled) {
                BackgroundAgentService.notifyCapabilitiesChanged()
            } else {
                agent?.notifyCapabilitiesChanged()
            }
        }.onFailure {
            _state.value = _state.value.copy(
                globalMessage = "Die lokale Freigabe konnte nicht gespeichert werden.",
            )
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
            globalMessage = "Lokale Kopplung entfernt. Für einen globalen Widerruf das Control Center verwenden.",
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
        _state.value = FeedbackUiState(
            identity = identity,
            identityAvailable = identity != null,
            serverUrl = stored?.endpoint?.baseUrl.orEmpty(),
            paired = stored != null,
            pairedDeviceName = stored?.registration?.name,
            pairedServer = stored?.endpoint?.baseUrl,
            pairedPublicDeviceId = stored?.registration?.publicDeviceId,
            pairedAt = stored?.registration?.pairedAt,
            backgroundConnectionEnabled = backgroundEnabled,
            systemInfoGrantedLocally = localCapabilityStore.granted().contains(Capability.SYSTEM_INFO),
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
        if (connectionState == AgentConnectionState.REVOKED) {
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
                globalMessage = "Dieses Gerät wurde im Control Center widerrufen.",
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
