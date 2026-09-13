package com.redurbabat.feedback.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.redurbabat.feedback.protocol.Capability
import com.redurbabat.feedback.security.CryptoUtils
import com.redurbabat.feedback.security.SecretStore
import com.redurbabat.feedback.security.SecretStoreException
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One area the owner picked, together with the local URIs that never leave the device.
 *
 * [uris] holds exactly one entry for a tree or a single file, and one per item for a collection
 * from the photo picker.
 *
 * [persistent] records whether Android actually granted a persistable permission. The document
 * picker always does; whether the photo picker does is not something this code can assume, so it
 * is stored rather than guessed, and availability is checked differently for the two cases.
 */
data class StoredFileShare(
    val shareId: String,
    val uris: List<Uri>,
    val displayName: String,
    val kind: FileShareKind,
    val capability: Capability,
    val persistent: Boolean,
    val addedAtEpochMillis: Long,
) {
    /** The single URI of a tree or file share. */
    val primaryUri: Uri get() = uris.first()

    /** The wire form, which carries the opaque id and the display name but never a URI. */
    fun toWire(): FileShare = FileShare(
        shareId = shareId,
        displayName = displayName,
        kind = kind,
        capability = capability,
        addedAtEpochMillis = addedAtEpochMillis,
    )
}

/**
 * What the store currently holds: the areas Android still honours, and how many it no longer does.
 *
 * The unavailable ones are counted rather than returned. They must never reach a listing - the
 * grant is gone - but dropping them silently would hide from the owner that something they shared
 * stopped working.
 */
data class FileShareInventory(
    val available: List<StoredFileShare>,
    val unavailableCount: Int,
)

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

    fun shares(): List<StoredFileShare> = inventory().available

    @Synchronized
    fun inventory(): FileShareInventory {
        val stored = try {
            secretStore.get(STORE_KEY)
        } catch (_: SecretStoreException) {
            // A blob that no longer authenticates is not trusted into a share list.
            return EMPTY_INVENTORY
        } ?: return EMPTY_INVENTORY

        val parsed = parseOrNull(stored) ?: return EMPTY_INVENTORY
        // Android may have dropped a grant behind our back, for example after the owner revoked it
        // in system settings or the volume disappeared. Such a share is not reported as available.
        val available = parsed.filter(::hasAccess)
        return FileShareInventory(
            available = available,
            unavailableCount = parsed.size - available.size,
        )
    }

    /**
     * Drops the records Android no longer honours. Nothing readable is removed: only entries that
     * already fail [hasAccess], which can never be listed or opened anyway.
     */
    @Synchronized
    fun forgetUnavailable(): Int {
        val stored = try {
            secretStore.get(STORE_KEY)
        } catch (_: SecretStoreException) {
            return 0
        } ?: return 0
        val parsed = parseOrNull(stored) ?: return 0
        val available = parsed.filter(::hasAccess)
        val removed = parsed.size - available.size
        if (removed > 0) {
            persist(available)
        }
        return removed
    }

    /**
     * Reads the display name Android reports for a freshly picked area.
     *
     * Returns null when the provider gives no name or one the protocol refuses - a name carrying a
     * path separator, for instance. Such a pick is rejected rather than repaired, for the same
     * reason a listing skips such a row.
     */
    fun resolveDisplayName(uri: Uri, kind: FileShareKind): String? {
        val documentUri = try {
            when (kind) {
                FileShareKind.TREE -> DocumentsContract.buildDocumentUriUsingTree(
                    uri,
                    DocumentsContract.getTreeDocumentId(uri),
                )
                FileShareKind.FILE -> uri
                // A collection names itself after the selection, not after one item.
                FileShareKind.COLLECTION -> return null
            }
        } catch (_: IllegalArgumentException) {
            return null
        }

        val cursor = try {
            contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null

        val name = cursor.use { row ->
            if (!row.moveToFirst()) {
                null
            } else {
                val index = row.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (index < 0 || row.isNull(index)) null else row.getString(index)
            }
        } ?: return null

        return name.takeIf(FileNames::isAcceptableDisplayName)
    }

    /**
     * Records a document-picker share and takes the persistable read permission for it.
     *
     * Only the read flag is taken. Feedback has no write path, so asking for write access would
     * grant more than the feature needs. A document share that cannot be persisted is refused
     * outright: it would stop working after a restart without anyone noticing.
     */
    @Synchronized
    fun add(uri: Uri, kind: FileShareKind, displayName: String, nowEpochMillis: Long): StoredFileShare {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (error: SecurityException) {
            throw FileShareException("Android did not grant persistent read access", error)
        }
        return record(
            uris = listOf(uri),
            kind = kind,
            capability = Capability.FILES_READ,
            displayName = displayName,
            persistent = true,
            nowEpochMillis = nowEpochMillis,
        )
    }

    /**
     * Records a photo-picker selection as one collection.
     *
     * Whether the photo picker hands out a persistable permission is not something this code can
     * assume, so it tries and records the answer instead of failing. A selection that is only
     * granted for this process still works now; [inventory] checks such a share by probing it
     * rather than by looking at the persisted permission list, so it disappears honestly once the
     * grant is gone instead of being listed as available.
     */
    @Synchronized
    fun addCollection(
        uris: List<Uri>,
        capability: Capability,
        displayName: String,
        nowEpochMillis: Long,
    ): StoredFileShare {
        if (uris.isEmpty()) {
            throw FileShareException("A collection needs at least one item")
        }
        if (uris.size > MAX_COLLECTION_ITEMS) {
            throw FileShareException("Too many items in one selection")
        }

        var persistent = true
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Not fatal here, unlike for a document share: the picker grant is still usable
                // for this process. The share is marked so its availability is probed later.
                persistent = false
            }
        }

        return record(
            uris = uris,
            kind = FileShareKind.COLLECTION,
            capability = capability,
            displayName = displayName,
            persistent = persistent,
            nowEpochMillis = nowEpochMillis,
        )
    }

    private fun record(
        uris: List<Uri>,
        kind: FileShareKind,
        capability: Capability,
        displayName: String,
        persistent: Boolean,
        nowEpochMillis: Long,
    ): StoredFileShare {
        if (!FileNames.isAcceptableDisplayName(displayName)) {
            throw FileShareException("Share display name is not acceptable")
        }

        val current = shares().toMutableList()
        val existing = current.firstOrNull { it.uris == uris && it.capability == capability }
        if (existing != null) {
            return existing
        }
        if (current.size >= MAX_SHARES) {
            throw FileShareException("Too many shared areas")
        }

        val share = StoredFileShare(
            shareId = mintShareId(current.map(StoredFileShare::shareId).toSet()),
            uris = uris,
            displayName = displayName,
            kind = kind,
            capability = capability,
            persistent = persistent,
            addedAtEpochMillis = nowEpochMillis,
        )
        current.add(share)
        persist(current)
        return share
    }

    /** Withdraws a share and releases the Android permissions that went with it. */
    @Synchronized
    fun remove(shareId: String) {
        val current = shares()
        val target = current.firstOrNull { it.shareId == shareId } ?: return
        persist(current.filterNot { it.shareId == shareId })
        target.uris.forEach(::releasePermission)
    }

    @Synchronized
    fun clear() {
        for (share in shares()) {
            share.uris.forEach(::releasePermission)
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
            FileShareKind.TREE -> DocumentsContract.getTreeDocumentId(share.primaryUri)
            FileShareKind.FILE -> DocumentsContract.getDocumentId(share.primaryUri)
            // A collection has no document tree to descend into; its items are its listing.
            FileShareKind.COLLECTION -> null
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
                    .put(FIELD_URIS, JSONArray(share.uris.map(Uri::toString)))
                    .put(FIELD_DISPLAY_NAME, share.displayName)
                    .put(FIELD_KIND, share.kind.wireName)
                    .put(FIELD_CAPABILITY, share.capability.wireName)
                    .put(FIELD_PERSISTENT, share.persistent)
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
                val capability = Capability.fromWire(item.getString(FIELD_CAPABILITY))
                val rawUris = item.getJSONArray(FIELD_URIS)
                val uris = ArrayList<Uri>(rawUris.length())
                for (uriIndex in 0 until rawUris.length()) {
                    uris.add(Uri.parse(rawUris.getString(uriIndex)))
                }
                if (kind == null || capability == null || uris.isEmpty() ||
                    !FileNames.isAcceptableOpaqueId(shareId) ||
                    !FileNames.isAcceptableDisplayName(displayName)
                ) {
                    return null
                }
                result.add(
                    StoredFileShare(
                        shareId = shareId,
                        uris = uris,
                        displayName = displayName,
                        kind = kind,
                        capability = capability,
                        persistent = item.optBoolean(FIELD_PERSISTENT, true),
                        addedAtEpochMillis = item.getLong(FIELD_ADDED_AT),
                    ),
                )
            }
            result
        }
    } catch (_: JSONException) {
        null
    }

    /**
     * Whether Android still lets us read this share.
     *
     * A persisted grant can be checked from the permission list. A picker grant that was never
     * persistable does not appear there at all, so it is probed instead - otherwise it would be
     * reported as gone while it still works, or as available long after it stopped.
     */
    private fun hasAccess(share: StoredFileShare): Boolean {
        if (share.persistent) {
            val persisted = contentResolver.persistedUriPermissions
            return share.uris.all { uri ->
                persisted.any { permission -> permission.isReadPermission && permission.uri == uri }
            }
        }
        return share.uris.all(::canRead)
    }

    private fun canRead(uri: Uri): Boolean = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { true } ?: false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
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
        // v2 added the URI list, the governing capability and the persistent flag. A v1 blob is
        // not migrated: it is simply not readable, so the owner re-picks rather than inheriting a
        // share whose capability nobody ever chose.
        private const val STORE_KEY = "files.shares.v1"
        private const val FORMAT_VERSION = 2
        private const val FIELD_VERSION = "version"
        private const val FIELD_SHARES = "shares"
        private const val FIELD_SHARE_ID = "shareId"
        private const val FIELD_URIS = "uris"
        private const val FIELD_DISPLAY_NAME = "displayName"
        private const val FIELD_KIND = "kind"
        private const val FIELD_CAPABILITY = "capability"
        private const val FIELD_PERSISTENT = "persistent"
        private const val FIELD_ADDED_AT = "addedAt"
        private const val SHARE_ID_BYTES = 12

        /** One photo picker selection. Bounded so a listing still fits in one frame. */
        const val MAX_COLLECTION_ITEMS = 100

        /**
         * Bounded so one shares listing always fits comfortably inside a single frame, well under
         * FILE_MAX_LIST_ENTRIES.
         */
        const val MAX_SHARES = 32

        private val EMPTY_INVENTORY = FileShareInventory(available = emptyList(), unavailableCount = 0)
    }
}
