package com.redurbabat.feedback.files

import com.redurbabat.feedback.protocol.MessageType
import com.redurbabat.feedback.protocol.ProtocolConstants
import com.redurbabat.feedback.protocol.ProtocolError
import java.io.IOException
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/** One page of a listing (protocol/PROTOCOL.md section 8.3.5). */
data class FileListPage(
    val entries: List<FileEntry>,
    val nextCursor: String?,
)

/** A shared area could not be read, for example because Android withdrew the grant. */
class FileReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What the handler reads from. [AndroidFileReader] is the real implementation. */
interface FileSource {
    fun shares(): List<FileShare>

    fun list(shareId: String, directoryId: String?, cursor: String?, limit: Int?): FileListPage?

    fun metadata(shareId: String, fileId: String): FileEntry?

    /** Null when the id is unknown, belongs to another share, or names a directory. */
    fun openStream(shareId: String, fileId: String): InputStream?
}

/** One frame the agent should put on the wire. */
data class OutgoingFileMessage(
    val type: MessageType,
    val payload: JSONObject,
)

/** Result of handling one `files.*` request. */
sealed interface FilesOutcome {
    data class Reply(val message: OutgoingFileMessage) : FilesOutcome

    data class Failure(val error: ProtocolError, val message: String) : FilesOutcome

    /** Handled, nothing to answer immediately - a download now streams instead. */
    data object Accepted : FilesOutcome
}

/**
 * Answers the `files.*` request types for one agent connection.
 *
 * Deliberately separate from the WebSocket client: it takes a [FileSource] and returns frames, so
 * the whole contract - limits, sequencing, backpressure, refusals - is unit tested against a fake
 * source instead of being inferred from a live connection.
 *
 * Every refusal maps to a protocol error code, and an id that does not resolve answers `NOT_FOUND`
 * rather than `FORBIDDEN`, so a reply never confirms that a resource exists (section 8.3.5).
 */
class FilesRequestHandler(
    private val source: FileSource,
    private val maxDownloadBytes: Long = ProtocolConstants.FILE_MAX_DOWNLOAD_BYTES,
    private val maxConcurrentTransfers: Int = ProtocolConstants.FILE_MAX_CONCURRENT_TRANSFERS,
    private val chunkSize: Int = ProtocolConstants.FILE_CHUNK_BYTES,
) {
    private val transfers = LinkedHashMap<String, ActiveTransfer>()

    val activeTransferCount: Int get() = transfers.size

    fun handleSharesRequest(): FilesOutcome {
        val array = JSONArray()
        for (share in source.shares()) {
            array.put(share.toJson())
        }
        return reply(MessageType.FILES_SHARES_RESPONSE, JSONObject().put(FIELD_SHARES, array))
    }

    fun handleListRequest(payload: JSONObject): FilesOutcome {
        val shareId = payload.opaqueId(FIELD_SHARE_ID)
            ?: return invalid("shareId fehlt oder ist unzulaessig")
        val directoryId = when (val supplied = payload.optionalOpaqueId(FIELD_DIRECTORY_ID)) {
            OptionalId.Absent -> null
            is OptionalId.Present -> supplied.value
            OptionalId.Invalid -> return invalid("directoryId ist unzulaessig")
        }
        val cursor = payload.nullableString(FIELD_CURSOR)
        val limit = payload.nullableInt(FIELD_LIMIT)
        if (limit != null && limit <= 0) {
            return invalid("limit muss positiv sein")
        }

        val page = try {
            source.list(shareId, directoryId, cursor, limit)
        } catch (_: FileReadException) {
            return FilesOutcome.Failure(ProtocolError.INTERNAL, "Bereich konnte nicht gelesen werden")
        } ?: return notFound()

        val entries = JSONArray()
        for (entry in page.entries) {
            entries.put(entry.toJson())
        }
        val response = JSONObject()
            .put(FIELD_SHARE_ID, shareId)
            .put(FIELD_ENTRIES, entries)
        if (page.nextCursor != null) {
            response.put(FIELD_NEXT_CURSOR, page.nextCursor)
        }
        return reply(MessageType.FILES_LIST_RESPONSE, response)
    }

    fun handleMetadataRequest(payload: JSONObject): FilesOutcome {
        val shareId = payload.opaqueId(FIELD_SHARE_ID)
            ?: return invalid("shareId fehlt oder ist unzulaessig")
        val fileId = payload.opaqueId(FIELD_FILE_ID)
            ?: return invalid("fileId fehlt oder ist unzulaessig")

        val entry = try {
            source.metadata(shareId, fileId)
        } catch (_: FileReadException) {
            return FilesOutcome.Failure(ProtocolError.INTERNAL, "Datei konnte nicht gelesen werden")
        } ?: return notFound()

        return reply(
            MessageType.FILES_METADATA_RESPONSE,
            JSONObject().put(FIELD_ENTRY, entry.toJson()),
        )
    }

    /** Starts a transfer. Chunks then come from [drainReadyMessages]. */
    fun handleDownloadStart(payload: JSONObject): FilesOutcome {
        val transferId = payload.opaqueId(FIELD_TRANSFER_ID)
            ?: return invalid("transferId fehlt oder ist unzulaessig")
        val shareId = payload.opaqueId(FIELD_SHARE_ID)
            ?: return invalid("shareId fehlt oder ist unzulaessig")
        val fileId = payload.opaqueId(FIELD_FILE_ID)
            ?: return invalid("fileId fehlt oder ist unzulaessig")

        if (transfers.containsKey(transferId)) {
            return invalid("transferId ist bereits aktiv")
        }
        if (transfers.size >= maxConcurrentTransfers) {
            return FilesOutcome.Failure(
                ProtocolError.RATE_LIMITED,
                "Zu viele gleichzeitige Uebertragungen",
            )
        }

        val entry = try {
            source.metadata(shareId, fileId)
        } catch (_: FileReadException) {
            return FilesOutcome.Failure(ProtocolError.INTERNAL, "Datei konnte nicht gelesen werden")
        } ?: return notFound()

        if (entry.kind != FileEntryKind.FILE) {
            return notFound()
        }
        if (!FileTransferPolicy.isDownloadable(entry.size, maxDownloadBytes)) {
            return FilesOutcome.Failure(
                ProtocolError.UNSUPPORTED,
                "Datei ueberschreitet das Uebertragungslimit",
            )
        }

        val stream = try {
            source.openStream(shareId, fileId)
        } catch (_: FileReadException) {
            return FilesOutcome.Failure(ProtocolError.INTERNAL, "Datei konnte nicht geoeffnet werden")
        } ?: return notFound()

        transfers[transferId] = ActiveTransfer(
            transferId = transferId,
            reader = FileChunkReader(stream, chunkSize),
            window = FileTransferWindow(),
        )
        return FilesOutcome.Accepted
    }

    fun handleDownloadAck(payload: JSONObject): FilesOutcome {
        val transferId = payload.opaqueId(FIELD_TRANSFER_ID)
            ?: return invalid("transferId fehlt oder ist unzulaessig")
        val sequence = payload.nullableLong(FIELD_SEQUENCE)
            ?: return invalid("sequence fehlt")

        val transfer = transfers[transferId] ?: return notFound()
        if (!transfer.window.recordAck(sequence)) {
            // An ack for something never sent, or a stale one, is a protocol violation.
            close(transferId)
            return invalid("ack passt nicht zum Sendefenster")
        }
        return FilesOutcome.Accepted
    }

    fun handleCancel(payload: JSONObject): FilesOutcome {
        val transferId = payload.opaqueId(FIELD_TRANSFER_ID)
            ?: return invalid("transferId fehlt oder ist unzulaessig")
        close(transferId)
        return FilesOutcome.Accepted
    }

    /**
     * Every frame that may be sent right now: chunks while the window allows, and the completion
     * once the last chunk went out.
     */
    fun drainReadyMessages(): List<OutgoingFileMessage> {
        val messages = ArrayList<OutgoingFileMessage>()
        for (transfer in transfers.values.toList()) {
            drainTransfer(transfer, messages)
        }
        return messages
    }

    /** Ends everything, for example on revoke, capability loss or a dropped connection. */
    fun cancelAll(reason: FileTransferCancelReason): List<OutgoingFileMessage> {
        val messages = transfers.keys.toList().map { transferId ->
            OutgoingFileMessage(
                MessageType.FILES_DOWNLOAD_CANCEL,
                JSONObject()
                    .put(FIELD_TRANSFER_ID, transferId)
                    .put(FIELD_REASON, reason.wireName),
            )
        }
        for (transferId in transfers.keys.toList()) {
            close(transferId)
        }
        return messages
    }

    private fun drainTransfer(transfer: ActiveTransfer, messages: MutableList<OutgoingFileMessage>) {
        while (transfer.window.canSend() && !transfer.finished) {
            val chunk = try {
                transfer.reader.next()
            } catch (_: IOException) {
                messages.add(cancelMessage(transfer.transferId, FileTransferCancelReason.READ_ERROR))
                close(transfer.transferId)
                return
            }

            if (chunk == null) {
                transfer.finished = true
                break
            }

            transfer.window.recordSent()
            messages.add(
                OutgoingFileMessage(
                    MessageType.FILES_DOWNLOAD_CHUNK,
                    JSONObject()
                        .put(FIELD_TRANSFER_ID, transfer.transferId)
                        .put(FIELD_SEQUENCE, chunk.sequence)
                        .put(FIELD_DATA, chunk.dataBase64)
                        .put(FIELD_LAST, chunk.last),
                ),
            )
            if (chunk.last) {
                transfer.finished = true
            }
        }

        if (transfer.finished && !transfer.completed) {
            transfer.completed = true
            messages.add(
                OutgoingFileMessage(
                    MessageType.FILES_DOWNLOAD_COMPLETE,
                    JSONObject()
                        .put(FIELD_TRANSFER_ID, transfer.transferId)
                        .put(FIELD_TOTAL_BYTES, transfer.reader.totalBytes)
                        .put(FIELD_SHA256, transfer.reader.sha256Hex()),
                ),
            )
            close(transfer.transferId)
        }
    }

    private fun cancelMessage(
        transferId: String,
        reason: FileTransferCancelReason,
    ): OutgoingFileMessage = OutgoingFileMessage(
        MessageType.FILES_DOWNLOAD_CANCEL,
        JSONObject()
            .put(FIELD_TRANSFER_ID, transferId)
            .put(FIELD_REASON, reason.wireName),
    )

    private fun close(transferId: String) {
        val transfer = transfers.remove(transferId) ?: return
        try {
            transfer.reader.close()
        } catch (_: IOException) {
            // Closing a stream that is already gone is not an error worth reporting.
        }
    }

    private fun reply(type: MessageType, payload: JSONObject): FilesOutcome =
        FilesOutcome.Reply(OutgoingFileMessage(type, payload))

    private fun invalid(message: String): FilesOutcome =
        FilesOutcome.Failure(ProtocolError.INVALID_MESSAGE, message)

    private fun notFound(): FilesOutcome =
        FilesOutcome.Failure(ProtocolError.NOT_FOUND, "Nicht gefunden")

    private class ActiveTransfer(
        val transferId: String,
        val reader: FileChunkReader,
        val window: FileTransferWindow,
    ) {
        var finished = false
        var completed = false
    }

    private companion object {
        const val FIELD_SHARES = "shares"
        const val FIELD_SHARE_ID = "shareId"
        const val FIELD_DIRECTORY_ID = "directoryId"
        const val FIELD_FILE_ID = "fileId"
        const val FIELD_CURSOR = "cursor"
        const val FIELD_LIMIT = "limit"
        const val FIELD_ENTRIES = "entries"
        const val FIELD_ENTRY = "entry"
        const val FIELD_NEXT_CURSOR = "nextCursor"
        const val FIELD_TRANSFER_ID = "transferId"
        const val FIELD_SEQUENCE = "sequence"
        const val FIELD_DATA = "data"
        const val FIELD_LAST = "last"
        const val FIELD_TOTAL_BYTES = "totalBytes"
        const val FIELD_SHA256 = "sha256"
        const val FIELD_REASON = "reason"

        fun JSONObject.nullableString(field: String): String? = when (val raw = opt(field)) {
            is String -> raw
            else -> null
        }

        fun JSONObject.nullableInt(field: String): Int? = when (val raw = opt(field)) {
            is Int -> raw
            is Long -> raw.toInt()
            else -> null
        }

        fun JSONObject.nullableLong(field: String): Long? = when (val raw = opt(field)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> null
        }

        /** Required opaque id; null when missing or not shaped like one of ours. */
        fun JSONObject.opaqueId(field: String): String? =
            nullableString(field)?.takeIf(FileNames::isAcceptableOpaqueId)

        /**
         * Optional opaque id. Absent and present-but-unacceptable are different answers: the first
         * means "list the share root", the second is a protocol violation.
         */
        fun JSONObject.optionalOpaqueId(field: String): OptionalId {
            if (!has(field) || isNull(field)) {
                return OptionalId.Absent
            }
            val value = nullableString(field)?.takeIf(FileNames::isAcceptableOpaqueId)
            return if (value == null) OptionalId.Invalid else OptionalId.Present(value)
        }
    }

    /** Outcome of reading an optional opaque id from a payload. */
    private sealed interface OptionalId {
        data object Absent : OptionalId
        data object Invalid : OptionalId
        data class Present(val value: String) : OptionalId
    }
}
