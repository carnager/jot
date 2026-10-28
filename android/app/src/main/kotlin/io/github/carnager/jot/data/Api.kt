package io.github.carnager.jot.data

import io.github.carnager.jot.model.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

/** The sync API (SPEC §2). */
object Api {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** SSE needs a read timeout longer than the server's 25 s ping. */
    private val streamClient: OkHttpClient = client.newBuilder().readTimeout(70, TimeUnit.SECONDS).build()

    fun normalize(server: String): String {
        var s = server.trim().trimEnd('/')
        if (s.isNotEmpty() && !s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return s
    }

    private suspend fun post(server: String, path: String, token: String?, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(server + path)
            .post(body.toString().toRequestBody(JSON))
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw ApiException(resp.code, msg.ifEmpty { "HTTP ${resp.code}" })
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    suspend fun login(server: String, username: String, password: String, device: String): String {
        val r = post(server, "/api/login", null, JSONObject().put("username", username).put("password", password).put("device", device))
        return r.getString("token")
    }

    suspend fun logout(server: String, token: String) {
        runCatching { post(server, "/api/logout", token, JSONObject()) }
    }

    data class SyncResponse(val rev: Long, val items: List<Item>)

    suspend fun sync(server: String, token: String, since: Long, changes: List<Item>): SyncResponse {
        val body = JSONObject().put("since", since).put("changes", JSONArray().apply { changes.forEach { put(it.toJson()) } })
        val r = post(server, "/api/sync", token, body)
        val arr = r.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) runCatching { items += Item.fromJson(arr.getJSONObject(i)) }
        return SyncResponse(r.getLong("rev"), items)
    }

    /** PUT /api/attachments/{id} (idempotent). */
    suspend fun upload(server: String, token: String, id: String, file: java.io.File, mime: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$server/api/attachments/$id")
            .header("Authorization", "Bearer $token")
            .put(file.asRequestBody(mime.toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}")
        }
    }

    fun attachmentUrl(server: String, id: String, width: Int? = null) =
        "$server/api/attachments/$id" + (width?.let { "?w=$it" } ?: "")

    /** Reads /api/events until the connection drops, calling [onRev] for every rev event. */
    suspend fun events(server: String, token: String, onRev: suspend (Long) -> Unit) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$server/api/events")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()
        val call = streamClient.newCall(req)
        val ctx = currentCoroutineContext()
        val job = ctx[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}")
                val source = resp.body.source()
                var event = ""
                while (true) {
                    ctx.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> {
                            val data = line.substring(5).trim()
                            if (event == "rev" || event.isEmpty()) data.toLongOrNull()?.let { onRev(it) }
                        }
                        line.isEmpty() -> event = ""
                    }
                }
            }
        } finally {
            handle?.dispose()
        }
    }
}
