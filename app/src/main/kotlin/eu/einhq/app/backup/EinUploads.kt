package eu.einhq.app.backup

import android.os.Build
import eu.einhq.app.BuildConfig
import eu.einhq.app.Pairing
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** EiN refused what the app asked, with its status and reason: the token (401), the folder (403, 404, 409), or the spool's room (413). */
class EinRefused(val status: Int, val reason: String) : IOException("EiN answered $status: $reason")

/** An upload's state on EiN: how much of it has arrived, and where it was stored once all of it has. */
data class UploadState(val offset: Long, val stored: String?)

/**
 * What the backup asks of the paired EiN, signed in with the app's token as the account the phone
 * was paired as: which checksums it has already (`POST /checksums`), and uploads in pieces that
 * resume, tus 1.0 at `/upload/`.
 */
class EinUploads(private val pairing: Pairing) {
    /** Which of `sha256` (at most 1000, as EiN takes them) EiN has already. */
    suspend fun known(sha256: List<String>): Set<String> {
        val body = JSONObject().put("sha256", JSONArray(sha256)).toString().toRequestBody(JSON)
        call(request("/checksums").post(body)).use { response ->
            if (response.code != 200) throw refusal(response)
            return try {
                val known = JSONObject(response.body.string()).getJSONArray("known")
                (0 until known.length()).map { known.getString(it) }.toSet()
            } catch (_: JSONException) {
                throw IOException("EiN did not answer as it does")
            }
        }
    }

    /**
     * Begin an upload of `length` bytes into `dir` as `name`, written at `mtime` (seconds); answers
     * where it is, and where the file was stored when it is empty, which EiN stores at once.
     */
    suspend fun begin(dir: String, name: String, length: Long, mtime: Long): Pair<String, String?> {
        val request = request("/upload/")
            .header("Tus-Resumable", TUS)
            .header("Upload-Length", length.toString())
            .header("Upload-Metadata", metadata("dir" to dir, "filename" to name, "mtime" to mtime.toString()))
            .post(ByteArray(0).toRequestBody())
        call(request).use { response ->
            if (response.code != 201) throw refusal(response)
            val location = response.header("Location") ?: throw IOException("EiN began the upload but did not say where")
            val url = pairing.server.toHttpUrl().resolve(location)?.toString() ?: throw IOException("not an address: $location")
            return url to response.header("Content-Location")?.let(::storedPath)
        }
    }

    /** How far the upload at `url` is; null when EiN no longer has it. */
    suspend fun state(url: String): UploadState? {
        call(Request.Builder().url(url).header("Authorization", "Bearer ${pairing.token}").header("Tus-Resumable", TUS).head()).use { response ->
            if (response.code == 404 || response.code == 410) return null
            if (response.code !in 200..299) throw refusal(response)
            return uploadState(response)
        }
    }

    /**
     * Send `count` bytes of `input` as the piece of the upload at `url` that starts at `offset`,
     * telling `sent` as they go; answers the upload's state after it.
     */
    suspend fun send(url: String, offset: Long, input: InputStream, count: Long, sent: (Long) -> Unit): UploadState {
        val body = object : RequestBody() {
            override fun contentType() = OCTETS
            override fun contentLength() = count
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(1 shl 16)
                var left = count
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("the file ended before its length")
                    sink.write(buffer, 0, n)
                    left -= n
                    sent(count - left)
                }
            }
        }
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${pairing.token}")
            .header("Tus-Resumable", TUS)
            .header("Upload-Offset", offset.toString())
            .patch(body)
        call(request).use { response ->
            if (response.code != 204) throw refusal(response)
            return uploadState(response)
        }
    }

    private fun request(path: String) = Request.Builder().url(pairing.server + path).header("Authorization", "Bearer ${pairing.token}")

    private fun uploadState(response: Response): UploadState {
        val offset = response.header("Upload-Offset")?.toLongOrNull() ?: throw IOException("EiN did not say how much of the upload it has")
        return UploadState(offset, response.header("Content-Location")?.let(::storedPath))
    }

    /** The path in the tree a `Content-Location` of `/file/…` names. */
    private fun storedPath(location: String): String = java.net.URLDecoder.decode(location.removePrefix("/file").replace("+", "%2B"), "UTF-8")

    private fun refusal(response: Response): IOException {
        val reason = runCatching { response.body.string().trim() }.getOrNull()?.ifEmpty { null } ?: response.message.ifEmpty { "no reason given" }
        return if (response.code in REFUSALS) EinRefused(response.code, reason) else IOException("EiN answered ${response.code}: $reason")
    }

    /** `request` sent, and cancelled when the coroutine waiting for it is. */
    private suspend fun call(request: Request.Builder): Response {
        val call = http.newCall(request.header("User-Agent", USER_AGENT).build())
        return suspendCancellableCoroutine { waiting ->
            waiting.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onResponse(call: Call, response: Response) = waiting.resume(response) { _, _, _ -> response.close() }
                    override fun onFailure(call: Call, e: IOException) = waiting.resumeWithException(e)
                },
            )
        }
    }

    companion object {
        private const val TUS = "1.0.0"
        private val JSON = "application/json".toMediaType()
        private val OCTETS = "application/offset+octet-stream".toMediaType()
        private val REFUSALS = setOf(401, 403, 404, 409, 413)
        private val USER_AGENT = "EiN/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"

        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /** tus's `Upload-Metadata`: each key with its value in Base64. */
        fun metadata(vararg pairs: Pair<String, String>): String =
            pairs.joinToString(",") { (key, value) -> "$key ${Base64.getEncoder().encodeToString(value.toByteArray())}" }
    }
}
