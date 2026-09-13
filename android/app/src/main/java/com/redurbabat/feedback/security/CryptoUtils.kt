package com.redurbabat.feedback.security

import java.security.MessageDigest

/**
 * Pure encoding and digest helpers shared by protocol, pairing and storage code.
 *
 * This file must stay free of `android.*` imports so the exact same implementation runs in JVM
 * unit tests and on the device. `android.util.Base64` is deliberately not used.
 */
object CryptoUtils {

    private const val STANDARD_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private const val URL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private const val HEX_DIGITS = "0123456789abcdef"

    private val STANDARD_LOOKUP = buildLookup(STANDARD_ALPHABET)
    private val URL_LOOKUP = buildLookup(URL_ALPHABET)

    /** Standard Base64 with padding (RFC 4648 section 4). */
    object Base64 {
        fun encode(bytes: ByteArray): String = encodeInternal(bytes, STANDARD_ALPHABET, true)

        fun decode(value: String): ByteArray = decodeInternal(value, STANDARD_LOOKUP, true)
    }

    /** URL safe Base64 without padding (RFC 4648 section 5). */
    object Base64Url {
        fun encode(bytes: ByteArray): String = encodeInternal(bytes, URL_ALPHABET, false)

        fun decode(value: String): ByteArray = decodeInternal(value, URL_LOOKUP, false)
    }

    /** Lowercase hex, never locale dependent. */
    fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            chars[index * 2] = HEX_DIGITS[value ushr 4]
            chars[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(chars)
    }

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * Length aware comparison without early exit on the shared prefix. Length itself is not
     * treated as a secret; callers compare values of a fixed, publicly known size.
     */
    fun constantTimeEquals(first: ByteArray, second: ByteArray): Boolean {
        if (first.size != second.size) {
            return false
        }
        var difference = 0
        for (index in first.indices) {
            difference = difference or (first[index].toInt() xor second[index].toInt())
        }
        return difference == 0
    }

    private fun buildLookup(alphabet: String): IntArray {
        val lookup = IntArray(128) { -1 }
        for (index in alphabet.indices) {
            lookup[alphabet[index].code] = index
        }
        return lookup
    }

    private fun encodeInternal(bytes: ByteArray, alphabet: String, padded: Boolean): String {
        val builder = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index + 2 < bytes.size) {
            val first = bytes[index].toInt() and 0xff
            val second = bytes[index + 1].toInt() and 0xff
            val third = bytes[index + 2].toInt() and 0xff
            builder.append(alphabet[first ushr 2])
            builder.append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
            builder.append(alphabet[((second and 0x0f) shl 2) or (third ushr 6)])
            builder.append(alphabet[third and 0x3f])
            index += 3
        }
        when (bytes.size - index) {
            1 -> {
                val first = bytes[index].toInt() and 0xff
                builder.append(alphabet[first ushr 2])
                builder.append(alphabet[(first and 0x03) shl 4])
                if (padded) {
                    builder.append("==")
                }
            }

            2 -> {
                val first = bytes[index].toInt() and 0xff
                val second = bytes[index + 1].toInt() and 0xff
                builder.append(alphabet[first ushr 2])
                builder.append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
                builder.append(alphabet[(second and 0x0f) shl 2])
                if (padded) {
                    builder.append('=')
                }
            }

            else -> Unit
        }
        return builder.toString()
    }

    private fun decodeInternal(value: String, lookup: IntArray, padded: Boolean): ByteArray {
        if (value.isEmpty()) {
            return ByteArray(0)
        }

        val dataLength: Int
        if (padded) {
            require(value.length % 4 == 0) {
                "Base64 input length must be a multiple of 4"
            }
            var padding = 0
            if (value[value.length - 1] == '=') {
                padding = 1
                if (value[value.length - 2] == '=') {
                    padding = 2
                }
            }
            dataLength = value.length - padding
        } else {
            require(value.indexOf('=') < 0) {
                "Base64Url input must not contain padding"
            }
            dataLength = value.length
        }

        require(dataLength % 4 != 1) {
            "Base64 input has an invalid length"
        }

        val output = ByteArray(dataLength * 3 / 4)
        var outputIndex = 0
        var buffer = 0
        var bits = 0
        for (index in 0 until dataLength) {
            val character = value[index]
            val decoded = if (character.code < lookup.size) lookup[character.code] else -1
            require(decoded >= 0) {
                "Base64 input contains an invalid character"
            }
            buffer = (buffer shl 6) or decoded
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output[outputIndex] = ((buffer ushr bits) and 0xff).toByte()
                outputIndex++
            }
        }
        require(bits == 0 || (buffer and ((1 shl bits) - 1)) == 0) {
            "Base64 input has non-zero trailing bits"
        }
        return output
    }
}
