package com.redurbabat.feedback.security

import android.content.Context
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.json.JSONObject

sealed interface AppUnlockResult {
    data object Success : AppUnlockResult
    data object NotConfigured : AppUnlockResult
    data class Invalid(val failedAttempts: Int, val lockout: AppLockLockout) : AppUnlockResult
    data class Locked(val lockout: AppLockLockout) : AppUnlockResult
}

/** Everything the UI needs to render the lock without touching the verifier itself. */
data class AppLockStatus(
    val configured: Boolean,
    val failedAttempts: Int,
    val lockout: AppLockLockout,
    val settings: AppLockSettings,
    /** The owner saw the setup screen and chose to postpone it. */
    val setupDeferred: Boolean,
)

class AppLockException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Local UI lock verifier.
 *
 * The derived verifier, its salt and the lock settings are sealed by [SecretStore]'s
 * Android-Keystore key. The plaintext PIN/passphrase is never persisted. Failed-attempt counters
 * live in private app storage and are persisted with `commit()` *before* an invalid result is
 * returned, so killing the process mid-attempt cannot clear the rate limit.
 *
 * Known limit: the attempt counter is ordinary private app storage. It resists an app-level
 * attacker and an uninstall-free restore, not an attacker who already has root on the device. That
 * case is out of scope for a local lock and is documented in the security model rather than papered
 * over here.
 */
class AppLockStore(
    context: Context,
    private val secretStore: SecretStore,
    private val random: SecureRandom = SecureRandom(),
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        META_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun isConfigured(): Boolean = secretStore.get(VERIFIER_KEY) != null

    @Synchronized
    fun status(nowEpochMillis: Long): AppLockStatus = AppLockStatus(
        configured = isConfigured(),
        failedAttempts = preferences.getInt(KEY_FAILED_ATTEMPTS, 0),
        lockout = readLockout(nowEpochMillis),
        settings = readSettings(),
        setupDeferred = preferences.getBoolean(KEY_SETUP_DEFERRED, false),
    )

    /**
     * Remembers that the owner postponed the setup, so the prompt is not shown on every start.
     *
     * This flag is plain private storage on purpose: it only suppresses a reminder. It can never
     * grant access, and a configured lock ignores it entirely.
     */
    @Synchronized
    fun setSetupDeferred(deferred: Boolean) {
        preferences.edit().putBoolean(KEY_SETUP_DEFERRED, deferred).apply()
    }

    @Synchronized
    fun configure(secret: CharArray) {
        require(AppLockPolicy.isAcceptableSecret(secret)) {
            "PIN or passphrase does not meet the local lock policy"
        }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val algorithm = preferredAlgorithm()
        val hash = derive(secret, salt, algorithm, PBKDF2_ITERATIONS)
        val record = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("algorithm", algorithm)
            .put("iterations", PBKDF2_ITERATIONS)
            .put("salt", CryptoUtils.Base64.encode(salt))
            .put("hash", CryptoUtils.Base64.encode(hash))
        secretStore.put(VERIFIER_KEY, record.toString())
        preferences.edit().putBoolean(KEY_SETUP_DEFERRED, false).apply()
        resetFailures()
    }

    @Synchronized
    fun verify(secret: CharArray, nowEpochMillis: Long): AppUnlockResult =
        checkSecret(secret, nowEpochMillis)

    /**
     * Turns the lock off, but only against a correct secret and only outside an active lockout.
     * Disabling therefore cannot be used as an unlock oracle that skips the rate limit.
     */
    @Synchronized
    fun disable(secret: CharArray, nowEpochMillis: Long): AppUnlockResult {
        val result = checkSecret(secret, nowEpochMillis)
        if (result is AppUnlockResult.Success) {
            secretStore.remove(VERIFIER_KEY)
            secretStore.remove(SETTINGS_KEY)
            resetFailures()
        }
        return result
    }

    @Synchronized
    fun readSettings(): AppLockSettings {
        val stored = try {
            secretStore.get(SETTINGS_KEY)
        } catch (_: SecretStoreException) {
            // A settings blob that no longer authenticates is never trusted; the safe default wins.
            null
        }
        return AppLockSettings.decodeOrNull(stored) ?: AppLockSettings.DEFAULT
    }

    @Synchronized
    fun writeSettings(settings: AppLockSettings) {
        secretStore.put(SETTINGS_KEY, settings.encode())
    }

    private fun checkSecret(secret: CharArray, nowEpochMillis: Long): AppUnlockResult {
        val lockout = readLockout(nowEpochMillis)
        if (lockout.remainingMillis(nowEpochMillis) > 0L) {
            return AppUnlockResult.Locked(lockout)
        }

        val encoded = secretStore.get(VERIFIER_KEY) ?: return AppUnlockResult.NotConfigured
        val verifier = parseVerifier(encoded)
        val candidate = derive(
            secret = secret,
            salt = verifier.salt,
            algorithm = verifier.algorithm,
            iterations = verifier.iterations,
        )
        if (CryptoUtils.constantTimeEquals(candidate, verifier.hash)) {
            resetFailures()
            return AppUnlockResult.Success
        }

        val failedAttempts = preferences.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val nextLockout = AppLockPolicy.lockoutAfterFailure(failedAttempts, nowEpochMillis)
        persistAttemptState(failedAttempts, nextLockout)
        return AppUnlockResult.Invalid(failedAttempts = failedAttempts, lockout = nextLockout)
    }

    /**
     * Reads the stored lockout and re-anchors it when the wall clock moved backwards, so a rewind
     * cannot shorten a pending wait once the clock is corrected again.
     */
    private fun readLockout(nowEpochMillis: Long): AppLockLockout {
        migrateLegacyLockoutIfPresent(nowEpochMillis)

        val duration = preferences.getLong(KEY_LOCKOUT_DURATION, 0L)
        if (duration <= 0L) {
            return AppLockLockout.NONE
        }
        val stored = AppLockLockout(
            startedAtEpochMillis = preferences.getLong(KEY_LOCKOUT_STARTED_AT, 0L),
            durationMillis = duration,
        )
        if (nowEpochMillis >= stored.startedAtEpochMillis) {
            return stored
        }
        val reAnchored = stored.reAnchoredTo(nowEpochMillis)
        persistAttemptState(preferences.getInt(KEY_FAILED_ATTEMPTS, 0), reAnchored)
        return reAnchored
    }

    /** One-time carry-over of the earlier absolute-deadline format. */
    private fun migrateLegacyLockoutIfPresent(nowEpochMillis: Long) {
        val legacyUntil = preferences.getLong(KEY_LEGACY_LOCKOUT_UNTIL, 0L)
        if (legacyUntil <= 0L) {
            return
        }
        val remaining = (legacyUntil - nowEpochMillis).coerceAtLeast(0L)
        val stored = preferences.edit()
            .remove(KEY_LEGACY_LOCKOUT_UNTIL)
            .putLong(KEY_LOCKOUT_STARTED_AT, nowEpochMillis)
            .putLong(KEY_LOCKOUT_DURATION, remaining)
            .commit()
        if (!stored) {
            throw AppLockException("Local lock state could not be migrated")
        }
    }

    private fun persistAttemptState(failedAttempts: Int, lockout: AppLockLockout) {
        val stored = preferences.edit()
            .putInt(KEY_FAILED_ATTEMPTS, failedAttempts)
            .putLong(KEY_LOCKOUT_STARTED_AT, lockout.startedAtEpochMillis)
            .putLong(KEY_LOCKOUT_DURATION, lockout.durationMillis)
            .commit()
        if (!stored) {
            throw AppLockException("Local lock attempt counter could not be persisted")
        }
    }

    private fun resetFailures() {
        val stored = preferences.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_STARTED_AT, 0L)
            .putLong(KEY_LOCKOUT_DURATION, 0L)
            .remove(KEY_LEGACY_LOCKOUT_UNTIL)
            .commit()
        if (!stored) {
            throw AppLockException("Local lock state could not be persisted")
        }
    }

    private fun parseVerifier(value: String): Verifier = try {
        val json = JSONObject(value)
        require(json.getInt("version") == FORMAT_VERSION) { "Unsupported app lock format" }
        val algorithm = json.getString("algorithm")
        val iterations = json.getInt("iterations")
        require(iterations in MIN_ACCEPTED_ITERATIONS..MAX_ACCEPTED_ITERATIONS) {
            "Invalid app lock iteration count"
        }
        val salt = CryptoUtils.Base64.decode(json.getString("salt"))
        val hash = CryptoUtils.Base64.decode(json.getString("hash"))
        require(salt.size >= 16) { "Invalid app lock salt" }
        require(hash.size == HASH_BYTES) { "Invalid app lock hash" }
        Verifier(algorithm, iterations, salt, hash)
    } catch (error: Exception) {
        throw AppLockException("Stored app lock verifier is malformed", error)
    }

    private fun preferredAlgorithm(): String = try {
        SecretKeyFactory.getInstance(ALGORITHM_SHA256)
        ALGORITHM_SHA256
    } catch (_: GeneralSecurityException) {
        try {
            SecretKeyFactory.getInstance(ALGORITHM_SHA1)
            ALGORITHM_SHA1
        } catch (error: GeneralSecurityException) {
            throw AppLockException("No supported PBKDF2 provider is available", error)
        }
    }

    private fun derive(
        secret: CharArray,
        salt: ByteArray,
        algorithm: String,
        iterations: Int,
    ): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, HASH_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } catch (error: GeneralSecurityException) {
            throw AppLockException("App lock verifier could not be derived", error)
        } finally {
            spec.clearPassword()
        }
    }

    private data class Verifier(
        val algorithm: String,
        val iterations: Int,
        val salt: ByteArray,
        val hash: ByteArray,
    )

    companion object {
        private const val META_PREFERENCES = "feedback.app.lock.meta.v1"
        private const val VERIFIER_KEY = "app.lock.verifier.v1"
        private const val SETTINGS_KEY = "app.lock.settings.v1"
        private const val KEY_FAILED_ATTEMPTS = "failedAttempts"
        private const val KEY_SETUP_DEFERRED = "setupDeferred"
        private const val KEY_LOCKOUT_STARTED_AT = "lockoutStartedAt"
        private const val KEY_LOCKOUT_DURATION = "lockoutDuration"
        private const val KEY_LEGACY_LOCKOUT_UNTIL = "lockoutUntil"
        private const val FORMAT_VERSION = 1
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
        private const val PBKDF2_ITERATIONS = 210_000
        private const val MIN_ACCEPTED_ITERATIONS = 100_000
        private const val MAX_ACCEPTED_ITERATIONS = 2_000_000
        private const val ALGORITHM_SHA256 = "PBKDF2WithHmacSHA256"
        private const val ALGORITHM_SHA1 = "PBKDF2WithHmacSHA1"
    }
}
