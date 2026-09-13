package com.redurbabat.feedback.files

import com.redurbabat.feedback.protocol.Capability
import com.redurbabat.feedback.protocol.Iso8601
import com.redurbabat.feedback.protocol.ProtocolConstants
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** `kind` of a [FileEntry] (protocol/PROTOCOL.md section 8.3.3). */
enum class FileEntryKind(val wireName: String) {
    FILE("file"),
    DIRECTORY("directory"),
    ;

    companion object {
        fun fromWire(value: String?): FileEntryKind? = entries.firstOrNull { it.wireName == value }
    }
}

/** `kind` of a [FileShare] (protocol/PROTOCOL.md section 8.3.1). */
enum class FileShareKind(val wireName: String) {
    TREE("tree"),
    FILE("file"),

    /**
     * Several individually picked items, from Android's photo picker. Twelve pictures are one
     * share with twelve entries rather than twelve shares - otherwise a single selection would
     * exhaust MAX_SHARES and the list would be unusable.
     */
    COLLECTION("collection"),
    ;

    companion object {
        fun fromWire(value: String?): FileShareKind? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * One area the owner shared. `shareId` is opaque: it must never carry or reconstruct a content URI
 * or a filesystem path.
 */
data class FileShare(
    val shareId: String,
    val displayName: String,
    val kind: FileShareKind,
    /**
     * The one capability that governs this area (section 8.3.1). There is no hierarchy and no
     * multiple assignment: a share is reachable exactly when this capability is effective.
     */
    val capability: Capability,
    val addedAtEpochMillis: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put(FIELD_SHARE_ID, shareId)
        .put(FIELD_DISPLAY_NAME, displayName)
        .put(FIELD_KIND, kind.wireName)
        .put(FIELD_CAPABILITY, capability.wireName)
        .put(FIELD_ADDED_AT, Iso8601.format(addedAtEpochMillis))

    companion object {
        const val FIELD_SHARE_ID = "shareId"
        const val FIELD_DISPLAY_NAME = "displayName"
        const val FIELD_KIND = "kind"
        const val FIELD_CAPABILITY = "capability"
        const val FIELD_ADDED_AT = "addedAt"

        fun fromJsonOrNull(json: JSONObject): FileShare? = try {
            val shareId = json.getString(FIELD_SHARE_ID)
            val displayName = json.getString(FIELD_DISPLAY_NAME)
            val kind = FileShareKind.fromWire(json.getString(FIELD_KIND))
            val capability = Capability.fromWire(json.getString(FIELD_CAPABILITY))
            val addedAt = Iso8601.parse(json.getString(FIELD_ADDED_AT))
            if (kind == null || addedAt == null || capability == null ||
                !FileNames.isAcceptableOpaqueId(shareId) ||
                !FileNames.isAcceptableDisplayName(displayName)
            ) {
                null
            } else {
                FileShare(shareId, displayName, kind, capability, addedAt)
            }
        } catch (_: JSONException) {
            null
        }
    }
}

/**
 * One listed entry (protocol/PROTOCOL.md section 8.3.3).
 *
 * `id` is opaque and session bound. A directory always has a `null` size.
 */
data class FileEntry(
    val id: String,
    val name: String,
    val mimeType: String?,
    val size: Long?,
    val modifiedAtEpochMillis: Long?,
    val kind: FileEntryKind,
) {
    init {
        require(FileNames.isAcceptableOpaqueId(id)) { "file id is not an opaque id" }
        require(FileNames.isAcceptableEntryName(name)) { "file name is not acceptable" }
        require(kind != FileEntryKind.DIRECTORY || size == null) {
            "a directory must not carry a size"
        }
        require(size == null || size >= 0L) { "size must not be negative" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put(FIELD_ID, id)
        .put(FIELD_NAME, name)
        .put(FIELD_MIME_TYPE, mimeType ?: JSONObject.NULL)
        .put(FIELD_SIZE, size ?: JSONObject.NULL)
        .put(
            FIELD_MODIFIED_AT,
            modifiedAtEpochMillis?.let(Iso8601::format) ?: JSONObject.NULL,
        )
        .put(FIELD_KIND, kind.wireName)

    companion object {
        const val FIELD_ID = "id"
        const val FIELD_NAME = "name"
        const val FIELD_MIME_TYPE = "mimeType"
        const val FIELD_SIZE = "size"
        const val FIELD_MODIFIED_AT = "modifiedAt"
        const val FIELD_KIND = "kind"

        /**
         * Strict parser. Returns null for anything the protocol does not allow, so a peer cannot
         * push a path component, a negative size or an unknown kind through a listing.
         */
        fun fromJsonOrNull(json: JSONObject): FileEntry? = try {
            val id = json.getString(FIELD_ID)
            val name = json.getString(FIELD_NAME)
            val kind = FileEntryKind.fromWire(json.getString(FIELD_KIND))
            val mimeType = json.optNullableString(FIELD_MIME_TYPE)
            val size = json.optNullableLong(FIELD_SIZE)
            val modifiedAtText = json.optNullableString(FIELD_MODIFIED_AT)
            val modifiedAt = modifiedAtText?.let(Iso8601::parse)

            when {
                kind == null -> null
                modifiedAtText != null && modifiedAt == null -> null
                !FileNames.isAcceptableOpaqueId(id) -> null
                !FileNames.isAcceptableEntryName(name) -> null
                size != null && size < 0L -> null
                kind == FileEntryKind.DIRECTORY && size != null -> null
                else -> FileEntry(id, name, mimeType, size, modifiedAt, kind)
            }
        } catch (_: JSONException) {
            null
        }

        /** Parses a listing, rejecting the whole array when any entry is unacceptable. */
        fun parseArrayOrNull(array: JSONArray): List<FileEntry>? {
            if (array.length() > ProtocolConstants.FILE_MAX_LIST_ENTRIES) {
                return null
            }
            val entries = ArrayList<FileEntry>(array.length())
            for (index in 0 until array.length()) {
                val item = array.opt(index) as? JSONObject ?: return null
                entries.add(fromJsonOrNull(item) ?: return null)
            }
            return entries
        }

        private fun JSONObject.optNullableString(field: String): String? =
            when (val raw = opt(field)) {
                is String -> raw
                else -> null
            }

        private fun JSONObject.optNullableLong(field: String): Long? =
            when (val raw = opt(field)) {
                is Int -> raw.toLong()
                is Long -> raw
                else -> null
            }
    }
}

/**
 * Name and id rules for `files.read`.
 *
 * A display name carrying a path separator is the usual route to a traversal on the receiving
 * side, so it is rejected at the protocol boundary rather than sanitised: a name that needs
 * repairing is a name that should not have been sent.
 */
object FileNames {

    fun isAcceptableEntryName(name: String): Boolean {
        if (name.isEmpty() || name.length > ProtocolConstants.FILE_NAME_MAX) {
            return false
        }
        if (name == "." || name == "..") {
            return false
        }
        // Spaces are perfectly normal in a file name; separators and control characters are not.
        return name.none { character ->
            character == '/' || character == '\\' || character.isISOControl()
        }
    }

    fun isAcceptableDisplayName(name: String): Boolean = isAcceptableEntryName(name)

    /** Opaque ids are base64url without padding and never look like a path. */
    fun isAcceptableOpaqueId(id: String): Boolean {
        if (id.isEmpty() || id.length > MAX_OPAQUE_ID_LENGTH) {
            return false
        }
        return id.all { character ->
            character in 'A'..'Z' ||
                character in 'a'..'z' ||
                character in '0'..'9' ||
                character == '-' ||
                character == '_'
        }
    }

    private const val MAX_OPAQUE_ID_LENGTH = 128
}
