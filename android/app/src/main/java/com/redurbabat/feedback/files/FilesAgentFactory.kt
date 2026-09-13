package com.redurbabat.feedback.files

import android.content.Context
import com.redurbabat.feedback.security.SecretStore

/**
 * Builds the `files.read` handler for one agent connection.
 *
 * Each connection gets its own [FileIdRegistry], so the opaque ids minted while browsing die with
 * that connection rather than outliving it.
 */
object FilesAgentFactory {

    fun create(context: Context, secretStore: SecretStore): FilesRequestHandler {
        val shareStore = FileShareStore(context, secretStore)
        val registry = FileIdRegistry()
        return FilesRequestHandler(AndroidFileReader(context, shareStore, registry))
    }
}
