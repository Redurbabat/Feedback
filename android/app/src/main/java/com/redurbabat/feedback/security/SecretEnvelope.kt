package com.redurbabat.feedback.security

/**
 * Serialised shape of one encrypted value: `v1:<base64url iv>:<base64url ciphertext>`.
 *
 * Kept separate from [SecretStore] and free of `android.*` so the on-disk format itself is unit
 * testable. The ciphertext includes the AES-GCM authentication tag.
 */
class SecretEnvelope(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    init {
        require(iv.size == IV_LENGTH_BYTES) {
            "AES-GCM IV must be $IV_LENGTH_BYTES bytes"
        }
        require(ciphertext.isNotEmpty()) {
            "Ciphertext must not be empty"
        }
    }

    fun encode(): String = VERSION_PREFIX +
        SEPARATOR +
        CryptoUtils.Base64Url.encode(iv) +
        SEPARATOR +
        CryptoUtils.Base64Url.encode(ciphertext)

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is SecretEnvelope) {
            return false
        }
        return iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int = 31 * iv.contentHashCode() + ciphertext.contentHashCode()

    /** Never prints key material or ciphertext. */
    override fun toString(): String = "SecretEnvelope(version=$VERSION_PREFIX)"

    companion object {
        const val VERSION_PREFIX = "v1"
        const val IV_LENGTH_BYTES = 12
        private const val SEPARATOR = ":"

        /** Strict parser; throws [IllegalArgumentException] for anything that is not v1 shaped. */
        fun decode(value: String): SecretEnvelope {
            val parts = value.split(SEPARATOR)
            require(parts.size == 3) {
                "Secret envelope must have three segments"
            }
            require(parts[0] == VERSION_PREFIX) {
                "Unsupported secret envelope version"
            }
            val iv = CryptoUtils.Base64Url.decode(parts[1])
            val ciphertext = CryptoUtils.Base64Url.decode(parts[2])
            return SecretEnvelope(iv, ciphertext)
        }
    }
}
