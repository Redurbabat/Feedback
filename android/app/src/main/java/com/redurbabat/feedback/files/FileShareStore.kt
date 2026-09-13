package com.redurbabat.feedback.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.redurbabat.feedback.security.CryptoUtils
import com.redurbabat.feedback.security.SecretStore
import com.redurbabat.feedback.security.SecretStoreException
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One area the owner picked, together with the local URI that never leaves the device. */
data class StoredFileShare(
    val shareId: String,
    val uri: Uri,
    val displayName: String,
    val kind: FileShareKind,
    val addedAtEpochMillis: Long,
) {
    /** The wire form, which carries the opaque id and the display name but never the URI. */
    fun toWire(): FileShare = FileShare(
        shareId = shareId,
        displayName = displayName,
        kind = kind,
        addedAtEpochMillis = addedAtEpochMillis,
    )
}

class FileShareException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The owner's Storage Access Framework grants.
 *
 * Feedback never asks for `MANAGE_EXTERNAL_STORAGE` or any broad storage permission. It can only
 * see what the owner explicitly handed over through the system picker, and it holds that access
 * through persistable URI permissions - which the owner can also revoke from Android's own
 * settings, independently of this app.
 *
 * The grants are stored through [SecretStore], so the content URIs sit behind the Android Keystore
 * rather than in plain preferences: a URI is a capability here, not just a label.
 */
class FileShareStore(
    context: Context,
    private val secretStore: SecretStore,
    private val random: SecureRandom = SecureRandom(),
) {
    private val applicationContext = context.applicationContext
    private val contentResolver = applicationContext.contentResolver

    @Synchronized
    fun shares(): List<StoredFileShare> {
        val stored = try {
            secretStore.get(STORE_KEY)
        } catch (_: SecretStoreException) {
            // A blob that no longer authenticates is not trusted into a share list.
            return emptyList()
        } ?: return emptyList()

        val parsed = parseOrNull(stored) ?: return emptyList()
        // Android may have dropped a grant behind our back, for example after the owner revoked it
        // in system settings or the volume disappeared. Such a share is not reported as available.
        return parsed.filter(::hasPersistedPermission)
    }

    /**
     * Records a share the owner picked and takes the persistable read permission for it.
     *
     * Only the read flag is taken. Feedback has no write path, so asking for write access would
     * grant more than the feature needs.
     */
    @Synchronized
    fun add(uri: Uri, kind: FileShareKind, displayName: String, nowEpochMillis: Long): StoredFileShare {
        if (!FileNames.isAcceptableDisplayName(displayName)) {
            throw FileShareException("Share display name is not acceptable")
        }

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (error: SecurityException) {
            throw FileShareException("Android did not grant persistent read access", error)
        }

        val current = shares().toMutableList()
        val existing = current.firstOrNull { it.uri == uri }
        if (existing != null) {
            return existing
        }
        if (current.size >= MAX_SHARES) {
            throw FileShareException("Too many shared areas")
        }

        val share = StoredFileShare(
            shareId = mintShareId(current.map(StoredFileShare::shareId).toSet()),
            uri = uri,
            displayName = displayName,
            kind = kind,
            addedAtEpochMillis = nowEpochMillis,
        )
        current.add(share)
        persist(current)
        return share
    }

    /** Withdraws a share and releases the Android permission that went with it. */
    @Synchronized
    fun remove(shareId: String) {
        val current = shares()
        val target = current.firstOrNull { it.shareId == shareId } ?: return
        persist(current.filterNot { it.shareId == shareId })
        releasePermission(target.uri)
    }

    @Synchronized
    fun clear() {
        for (share in shares()) {
            releasePermission(share.uri)
        }
        secretStore.remove(STORE_KEY)
    }

    fun find(shareId: String): StoredFileShare? = shares().firstOrNull { it.shareId == shareId }

    /**
     * The document id a share starts at. For a tree that is the tree root; for a single picked
     * file it is the document itself.
     */
    fun rootDocumentId(share: StoredFileShare): String? = try {
        when (share.kind) {
            FileShareKind.TREE -> DocumentsContract.getTreeDocumentId(share.uri)
            FileShareKind.FILE -> DocumentsContract.getDocumentId(share.uri)
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun persist(shares: List<StoredFileShare>) {
        val array = JSONArray()
        for (share in shares) {
            array.put(
                JSONObject()
                    .put(FIELD_SHARE_ID, share.shareId)
                    .put(FIELD_URI, share.uri.toString())
                    .put(FIELD_DISPLAY_NAME, share.displayName)
                    .put(FIELD_KIND, share.kind.wireName)
                    .put(FIELD_ADDED_AT, share.addedAtEpochMillis),
            )
        }
        val record = JSONObject()
            .put(FIELD_VERSION, FORMAT_VERSION)
            .put(FIELD_SHARES, array)
        secretStore.put(STORE_KEY, record.toString())
    }

    private fun parseOrNull(value: String): List<StoredFileShare>? = try {
        val json = JSONObject(value)
        if (json.getInt(FIELD_VERSION) != FORMAT_VERSION) {
            null
        } else {
            val array = json.getJSONArray(FIELD_SHARES)
            val result = ArrayList<StoredFileShare>(array.length())
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val shareId = item.getString(FIELD_SHARE_ID)
                val displayName = item.getString(FIELD_DISPLAY_NAME)
                val kind = FileShareKind.fromWire(item.getString(FIELD_KIND))
                if (kind == null ||
                    !FileNames.isAcceptableOpaqueId(shareId) ||
                    !FileNames.isAcceptableDisplayName(displayName)
                ) {
                    return null
                }
                result.add(
                    StoredFileShare(
                        shareId = shareId,
                        uri = Uri.parse(item.getString(FIELD_URI)),
                        displayName = displayName,
                        kind = kind,
                        addedAtEpochMillis = item.getLong(FIELD_ADDED_AT),
                    ),
                )
            }
            result
        }
    } catch (_: JSONException) {
        null
    }

    private fun hasPersistedPermission(share: StoredFileShare): Boolean =
        contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == share.uri
        }

    private fun releasePermission(uri: Uri) {
        try {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Already gone, for example because the owner revoked it in system settings.
        }
    }

    private fun mintShareId(taken: Set<String>): String {
        val bytes = ByteArray(SHARE_ID_BYTES)
        while (true) {
            random.nextBytes(bytes)
            val candidate = CryptoUtils.Base64Url.encode(bytes)
            if (candidate !in taken) {
                return candidate
            }
        }
    }

    companion object {
        private const val STORE_KEY = "files.shares.v1"
        private const val FORMAT_VERSION = 1
        private const val FIELD_VERSION = "version"
        private const val FIELD_SHARES = "shares"
        private const val FIELD_SHARE_ID = "shareId"
        private const val FIELD_URI = "uri"
        private const val FIELD_DISPLAY_NAME = "displayName"
        private const val FIELD_KIND = "kind"
        private const val FIELD_ADDED_AT = "addedAt"
        private const val SHARE_ID_BYTES = 12

        /**
         * Bounded so one shares listing always fits comfortably inside a single frame, well under
         * FILE_MAX_LIST_ENTRIES.
         */
        const val MAX_SHARES = 32
    }
}
