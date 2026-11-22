package eu.einhq.app.track

import android.content.Context
import android.os.Build
import eu.einhq.app.BuildConfig
import eu.einhq.app.Pairing
import eu.einhq.app.PairingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Sends the waiting positions to the phone's `position` track on EiN, `POST /file/dev/<phone>/position`,
 * oldest first, `BATCH` at a time, with the app's token. Each batch EiN took is forgotten; one it
 * refused stays, with why.
 */
object FixSender {
    /** Positions one request sends: a few hundred kilobytes. */
    private const val BATCH = 1000

    private val JSON = "application/json".toMediaType()
    private val userAgent = "EiN/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** How a send ended: all sent, EiN could not be reached (try later), or EiN refused them. */
    enum class Sent { DONE, AGAIN, REFUSED }

    suspend fun sendAll(context: Context): Sent = withContext(Dispatchers.IO) {
        val pairing = PairingStore(context).load() ?: return@withContext Sent.DONE
        val store = TrackingStore(context)
        FixQueue(context).use { queue ->
            var batch = queue.oldest(BATCH)
            while (batch.isNotEmpty()) {
                val (status, reason) = try {
                    post(pairing, Fix.body(batch.map { it.second }))
                } catch (error: IOException) {
                    store.saveError("EiN could not be reached: ${error.message ?: error.javaClass.simpleName}")
                    return@withContext Sent.AGAIN
                }
                when (status) {
                    in 200..299 -> {
                        queue.removeUpTo(batch.last().first)
                        store.saveSent(System.currentTimeMillis())
                    }
                    in 500..599 -> {
                        store.saveError("EiN answered $status: $reason")
                        return@withContext Sent.AGAIN
                    }
                    else -> {
                        store.saveError(refusal(pairing, status, reason))
                        return@withContext Sent.REFUSED
                    }
                }
                batch = queue.oldest(BATCH)
            }
            Sent.DONE
        }
    }

    /** Post `body`; answers EiN's status and its reason. */
    private fun post(pairing: Pairing, body: String): Pair<Int, String> {
        val request = Request.Builder()
            .url("${pairing.server}/file/dev/${URLEncoder.encode(pairing.device, "UTF-8")}/position")
            .header("Authorization", "Bearer ${pairing.token}")
            .header("User-Agent", userAgent)
            .post(body.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val reason = runCatching { response.body.string().trim() }.getOrNull()?.ifEmpty { null } ?: response.message.ifEmpty { "no reason given" }
            return response.code to reason
        }
    }

    private fun refusal(pairing: Pairing, status: Int, reason: String): String = when (status) {
        401, 403 -> "EiN refused the positions: the phone is no longer paired, or ${pairing.device} has no Position track."
        else -> "EiN would not take the positions ($status): $reason"
    }
}
