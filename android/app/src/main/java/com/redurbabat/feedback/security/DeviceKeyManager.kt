package com.redurbabat.feedback.security

/**
 * Access to the device identity key pair. The private key never leaves the secure store; this
 * interface deliberately exposes no way to export or log it.
 */
interface DeviceKeyManager {

    /** Creates the key pair if it does not exist yet. Idempotent. */
    fun ensureKeyPair()

    /** X.509 SubjectPublicKeyInfo (SPKI) DER encoding of the public key. */
    fun publicKeySpki(): ByteArray

    /** SHA256withECDSA signature (ASN.1/DER) over the given payload. */
    fun sign(payload: ByteArray): ByteArray
}

/** Typed failure of every device identity operation, always carrying its cause. */
class DeviceIdentityException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
