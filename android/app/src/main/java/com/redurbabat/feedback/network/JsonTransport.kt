package com.redurbabat.feedback.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class FeedbackHttpException(
    val statusCode: Int,
    val protocolCode: String?,
    message: String,
) : Exception(message)

/** Small JSON transport boundary so protocol logic remains testable without a network. */
interface JsonTransport {
    suspend fun post(path: String, body: JSONObject): JSONObject
    suspend fun get(path: String, bearerToken: String? = null): JSONObject
}

class OkHttpJsonTransport(
    private val endpoint: ServerEndpoint,
    private val client: OkHttpClient = OkHttpClient(),
) : JsonTransport {
    override suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        execute(
            Request.Builder()
                .url(endpoint.api(path))
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
    }

    override suspend fun get(path: String, bearerToken: String?): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(endpoint.api(path)).get()
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        execute(builder.build())
    }

    private fun execute(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(if (text.isBlank()) "{}" else text) }
                .getOrElse {
                    throw FeedbackHttpException(
                        statusCode = response.code,
                        protocolCode = null,
                        message = "Server returned malformed JSON",
                    )
                }
            if (!response.isSuccessful) {
                val error = json.optJSONObject("error")
                throw FeedbackHttpException(
                    statusCode = response.code,
                    protocolCode = error?.optString("code")?.takeIf { it.isNotBlank() },
                    message = error?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "Feedback server request failed (${response.code})",
                )
            }
            return json
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
