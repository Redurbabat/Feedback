package com.redurbabat.feedback.files

import com.redurbabat.feedback.security.CryptoUtils
import java.security.SecureRandom

/**
 * Session-scoped mapping between the opaque `fileId` on the wire and the local document it refers
 * to (protocol/PROTOCOL.md section 8.3.2).
 *
 * The mapping exists only in memory and only for the lifetime of one `files.read` remote session.
 * That is what makes an id unredeemable afterwards: there is nothing left to resolve it against,
 * so a replayed id answers NOT_FOUND rather than content.
 *
 * Ids are random, not derived from the document, so they leak nothing about the path even to
 * someone who can guess document identifiers. Repeated lookups of the same document return the
 * same id, so a listing the browser already holds stays valid within the session.
 *
 * Kept free of `android.*` so the identifier rules are unit tested.
 */
class FileIdRegistry(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val random: SecureRandom = SecureRandom(),
) {
    /** A document inside one share. [documentId] never leaves the device. */
    data class Reference(val shareId: String, val documentId: String)

    // Access-ordered so the oldest untouched entry is evicted first.
    private val byFileId = LinkedHashMap<String, Reference>(16, 0.75f, true)
    private val byReference = HashMap<Reference, String>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    val size: Int get() = byFileId.size

    /** Returns the stable id for [reference], minting one on first use. */
    fun idFor(reference: Reference): String {
        val existing = byReference[reference]
        if (existing != null) {
            // Touch the access order so a document still being browsed is not evicted.
            byFileId[existing]
            return existing
        }

        val fileId = mintId()
        byFileId[fileId] = reference
        byReference[reference] = fileId
        evictWhileOversized()
        return fileId
    }

    fun idFor(shareId: String, documentId: String): String =
        idFor(Reference(shareId, documentId))

    /** Null for an unknown, evicted or already released id. */
    fun resolve(fileId: String): Reference? = byFileId[fileId]

    /**
     * Null unless [fileId] resolves *and* belongs to [shareId]. Callers pass the share from the
     * request, so an id from one share can never be redeemed against another.
     */
    fun resolveWithin(shareId: String, fileId: String): Reference? =
        resolve(fileId)?.takeIf { it.shareId == shareId }

    /** Drops every id of one share, for example when the owner withdraws it. */
    fun releaseShare(shareId: String) {
        val stale = byFileId.entries
            .filter { it.value.shareId == shareId }
            .map { it.key }
        for (fileId in stale) {
            val reference = byFileId.remove(fileId)
            if (reference != null) {
                byReference.remove(reference)
            }
        }
    }

    /** Ends the session: every outstanding id stops resolving. */
    fun clear() {
        byFileId.clear()
        byReference.clear()
    }

    private fun mintId(): String {
        // 12 bytes is 16 base64url characters: far beyond guessing, and short enough to stay
        // comfortable inside a frame full of listing entries.
        val bytes = ByteArray(ID_BYTES)
        while (true) {
            random.nextBytes(bytes)
            val candidate = CryptoUtils.Base64Url.encode(bytes)
            if (!byFileId.containsKey(candidate)) {
                return candidate
            }
        }
    }

    private fun evictWhileOversized() {
        while (byFileId.size > maxEntries) {
            val oldest = byFileId.entries.firstOrNull() ?: return
            byFileId.remove(oldest.key)
            byReference.remove(oldest.value)
        }
    }

    companion object {
        const val ID_BYTES = 12

        /**
         * Browsing a large tree must not grow without bound. Beyond this the oldest ids are
         * dropped and simply have to be listed again.
         */
        const val DEFAULT_MAX_ENTRIES = 10_000
    }
}
