package com.redurbabat.feedback.files

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.redurbabat.feedback.protocol.Capability
import com.redurbabat.feedback.protocol.ProtocolConstants
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Reads the owner's shared areas through the Storage Access Framework.
 *
 * Traversal is prevented by construction rather than by path checking: a caller can only name a
 * `fileId` that this device minted while listing inside a share, and [FileIdRegistry.resolveWithin]
 * refuses an id that belongs to a different share. There is no code path that turns a caller
 * supplied string into a document id, so there is nothing to escape from.
 *
 * File content is never buffered whole: [openStream] hands back a stream and the agent reads it one
 * chunk at a time.
 */
class AndroidFileReader(
    context: Context,
    private val shareStore: FileShareStore,
    private val idRegistry: FileIdRegistry,
) : FileSource {
    private val contentResolver = context.applicationContext.contentResolver

    /** The areas the owner shared, in wire form. */
    override fun shares(): List<FileShare> = shareStore.shares().map(StoredFileShare::toWire)

    override fun findShare(shareId: String): FileShare? = shareStore.find(shareId)?.toWire()

    /**
     * Lists one page. [directoryId] must be null (the share root) or an id previously handed out
     * inside the same share.
     */
    override fun list(
        shareId: String,
        directoryId: String?,
        cursor: String?,
        limit: Int?,
    ): FileListPage? {
        val share = shareStore.find(shareId) ?: return null
        val pageSize = (limit ?: ProtocolConstants.FILE_MAX_LIST_ENTRIES)
            .coerceIn(1, ProtocolConstants.FILE_MAX_LIST_ENTRIES)
        val offset = if (cursor == null) 0 else FileListCursor.decodeOrNull(cursor) ?: return null

        if (share.kind == FileShareKind.COLLECTION) {
            // Section 8.4.3: a collection has no folders, so a directoryId inside one cannot
            // resolve to anything and is answered as unknown rather than as forbidden.
            if (directoryId != null) {
                return null
            }
            val items = collectionEntries(share)
            val page = items.drop(offset).take(pageSize)
            val more = items.size > offset + page.size
            return FileListPage(page, if (more) FileListCursor.encode(offset + page.size) else null)
        }

        val parentDocumentId = if (directoryId == null) {
            shareStore.rootDocumentId(share) ?: return null
        } else {
            idRegistry.resolveWithin(shareId, directoryId)?.documentId ?: return null
        }

        // A single picked file has no children: its root listing is the file itself.
        if (share.kind == FileShareKind.FILE) {
            if (directoryId != null || offset > 0) {
                return FileListPage(emptyList(), null)
            }
            val entry = entryOf(share, parentDocumentId, share.primaryUri) ?: return null
            return FileListPage(listOf(entry), null)
        }

        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(share.primaryUri, parentDocumentId)
        } catch (error: IllegalArgumentException) {
            throw FileReadException("Share document id is not usable", error)
        }

        val entries = ArrayList<FileEntry>(pageSize)
        var scanned = 0
        var more = false

        query(childrenUri)?.use { row ->
            if (!row.moveToPosition(offset)) {
                return@use
            }
            do {
                if (entries.size == pageSize) {
                    more = true
                    break
                }
                val entry = entryOfRow(share, row)
                if (entry != null) {
                    entries.add(entry)
                }
                scanned += 1
            } while (row.moveToNext())
        } ?: return null

        val nextCursor = if (more) FileListCursor.encode(offset + scanned) else null
        return FileListPage(entries, nextCursor)
    }

    /** Metadata for one previously listed entry. */
    override fun metadata(shareId: String, fileId: String): FileEntry? {
        val share = shareStore.find(shareId) ?: return null
        val documentId = idRegistry.resolveWithin(shareId, fileId)?.documentId ?: return null
        return entryOf(share, documentId, documentUri(share, documentId))
    }

    /**
     * Opens a stream for a previously listed file. The caller closes it. Returns null when the id
     * is unknown, belongs to another share, or names a directory.
     */
    override fun openStream(shareId: String, fileId: String): InputStream? {
        val share = shareStore.find(shareId) ?: return null
        val documentId = idRegistry.resolveWithin(shareId, fileId)?.documentId ?: return null
        val uri = documentUri(share, documentId)
        val entry = entryOf(share, documentId, uri) ?: return null
        if (entry.kind != FileEntryKind.FILE) {
            return null
        }
        return try {
            contentResolver.openInputStream(uri)
        } catch (_: FileNotFoundException) {
            null
        } catch (error: SecurityException) {
            throw FileReadException("Android withdrew access to this share", error)
        } catch (error: IOException) {
            throw FileReadException("Shared file could not be opened", error)
        }
    }

    /**
     * The items of a photo-picker collection.
     *
     * Every item is checked against the capability that governs the share: a video in a photo
     * collection is dropped rather than delivered. The picker should not return one, but "should
     * not" is not a guarantee, and `media.photos.read` must not become a way to read videos.
     *
     * Items Android no longer lets us read are skipped, so a collection shrinks honestly instead
     * of answering with entries that fail on download.
     */
    private fun collectionEntries(share: StoredFileShare): List<FileEntry> {
        val prefix = mimePrefixFor(share.capability) ?: return emptyList()
        val entries = ArrayList<FileEntry>(share.uris.size)
        for (uri in share.uris) {
            val mimeType = try {
                contentResolver.getType(uri)
            } catch (_: SecurityException) {
                null
            } ?: continue
            if (!mimeType.startsWith(prefix)) {
                continue
            }
            val entry = openableEntry(share, uri, mimeType) ?: continue
            entries.add(entry)
        }
        return entries
    }

    private fun mimePrefixFor(capability: Capability): String? = when (capability) {
        Capability.MEDIA_PHOTOS_READ -> "image/"
        Capability.MEDIA_VIDEOS_READ -> "video/"
        else -> null
    }

    /** Reads one picked item through OpenableColumns, which is what the photo picker supports. */
    private fun openableEntry(
        share: StoredFileShare,
        uri: Uri,
        mimeType: String,
    ): FileEntry? {
        val cursor = try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null

        return cursor.use { row ->
            if (!row.moveToFirst()) {
                return@use null
            }
            val name = row.stringOrNull(OpenableColumns.DISPLAY_NAME) ?: return@use null
            if (!FileNames.isAcceptableEntryName(name)) {
                return@use null
            }
            val size = row.longOrNull(OpenableColumns.SIZE)?.takeIf { it >= 0L }
            try {
                FileEntry(
                    id = idRegistry.idFor(share.shareId, uri.toString()),
                    name = name,
                    mimeType = mimeType,
                    size = size,
                    // The photo picker exposes no reliable modification time, and inventing one
                    // would be worse than admitting there is none.
                    modifiedAtEpochMillis = null,
                    kind = FileEntryKind.FILE,
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    private fun documentUri(share: StoredFileShare, documentId: String): Uri =
        when (share.kind) {
            FileShareKind.FILE -> share.primaryUri
            FileShareKind.TREE ->
                DocumentsContract.buildDocumentUriUsingTree(share.primaryUri, documentId)
            // For a collection the document id IS the item URI, minted while listing.
            FileShareKind.COLLECTION -> Uri.parse(documentId)
        }

    private fun entryOf(share: StoredFileShare, documentId: String, uri: Uri): FileEntry? =
        query(uri)?.use { row ->
            if (row.moveToFirst()) entryOfRow(share, row, fallbackDocumentId = documentId) else null
        }

    /**
     * Builds one entry, or null when the provider returns something the protocol does not allow -
     * most importantly a display name containing a path separator. Such a row is skipped rather
     * than repaired: a name that needs repairing should not be forwarded.
     */
    private fun entryOfRow(
        share: StoredFileShare,
        row: Cursor,
        fallbackDocumentId: String? = null,
    ): FileEntry? {
        val documentId = row.stringOrNull(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            ?: fallbackDocumentId
            ?: return null
        val displayName = row.stringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            ?: return null
        if (!FileNames.isAcceptableEntryName(displayName)) {
            return null
        }

        val mimeType = row.stringOrNull(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        val size = if (isDirectory) null else row.longOrNull(DocumentsContract.Document.COLUMN_SIZE)
        val modifiedAt = row.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            ?.takeIf { it > 0L }

        return try {
            FileEntry(
                id = idRegistry.idFor(share.shareId, documentId),
                name = displayName,
                mimeType = if (isDirectory) null else mimeType,
                size = size?.takeIf { it >= 0L },
                modifiedAtEpochMillis = modifiedAt,
                kind = if (isDirectory) FileEntryKind.DIRECTORY else FileEntryKind.FILE,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun query(uri: Uri): Cursor? = try {
        contentResolver.query(uri, PROJECTION, null, null, null)
    } catch (_: SecurityException) {
        // The owner revoked the grant in system settings; treat the share as gone.
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
