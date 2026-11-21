package eu.einhq.app

import android.os.Build
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** EiN's answer: what was asked for, a refusal with EiN's reason, or no answer and why. */
sealed interface Answer<out T> {
    data class Done<T>(val value: T) : Answer<T>
    data class Refused(val reason: String) : Answer<Nothing>
    data class Unreachable(val reason: String) : Answer<Nothing>
}

/** A Web UI session: where to go now, and the `Set-Cookie` headers that carry it, for the WebView. */
data class Session(val location: String, val cookies: List<String>)

/** Whether a cookie still signs in, and the `Set-Cookie` headers that renew it. */
data class SessionCheck(val signedIn: Boolean, val cookies: List<String>)

/**
 * EiN's endpoints for the app. Each blocks, so it is called off the main thread; the cookies are
 * the WebView's, read and written by the caller.
 */
object Endpoints {
    private const val TIMEOUT_MS = 15_000
    private val userAgent = "EiN/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"

    /** Trade a pairing link's one-time code for the app's token (`POST /__app/pair`). */
    fun pair(link: PairingLink): Answer<Pairing> = exchange("${link.server}/__app/pair") { connection ->
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val request = JSONObject().put("device", link.device).put("code", link.code).toString()
        connection.outputStream.use { it.write(request.toByteArray()) }
        when (val status = connection.responseCode) {
            HttpURLConnection.HTTP_OK -> {
                val answer = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                Answer.Done(Pairing(link.server, answer.getString("device"), answer.optString("user"), answer.getString("token")))
            }
            HttpURLConnection.HTTP_BAD_REQUEST, HttpURLConnection.HTTP_FORBIDDEN -> Answer.Refused(reason(connection))
            else -> Answer.Unreachable(answered(status, connection))
        }
    }

    /** Whether `cookie` still signs in at `server` (`GET /__session`). */
    fun check(server: String, cookie: String?): Answer<SessionCheck> = exchange("$server/__session") { connection ->
        if (cookie != null) connection.setRequestProperty("Cookie", cookie)
        when (val status = connection.responseCode) {
            in 200..299 -> Answer.Done(SessionCheck(true, cookiesOf(connection)))
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> Answer.Done(SessionCheck(false, emptyList()))
            else -> Answer.Unreachable(answered(status, connection))
        }
    }

    /** A Web UI session for the app's token (`GET /__app/session`), going on to `next`, a path. */
    fun session(pairing: Pairing, next: String): Answer<Session> =
        exchange("${pairing.server}/__app/session?next=${URLEncoder.encode(next, "UTF-8")}") { connection ->
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${pairing.token}")
            when (val status = connection.responseCode) {
                in 300..399 -> Answer.Done(Session(connection.getHeaderField("Location") ?: next, cookiesOf(connection)))
                HttpURLConnection.HTTP_FORBIDDEN -> Answer.Refused(reason(connection))
                else -> Answer.Unreachable(answered(status, connection))
            }
        }

    /** Take the app's own pairing away (`POST /__app/unpair`), as `device-app --off` does. */
    fun unpair(pairing: Pairing): Answer<Unit> = exchange("${pairing.server}/__app/unpair") { connection ->
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer ${pairing.token}")
        when (val status = connection.responseCode) {
            in 200..299 -> Answer.Done(Unit)
            HttpURLConnection.HTTP_FORBIDDEN -> Answer.Refused(reason(connection))
            else -> Answer.Unreachable(answered(status, connection))
        }
    }

    private fun <T> exchange(url: String, talk: (HttpURLConnection) -> Answer<T>): Answer<T> {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: IOException) {
            return Answer.Unreachable(error.message ?: error.javaClass.simpleName)
        }
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", userAgent)
        return try {
            talk(connection)
        } catch (error: IOException) {
            Answer.Unreachable(error.message ?: error.javaClass.simpleName)
        } catch (_: JSONException) {
            Answer.Unreachable("it did not answer as EiN does")
        } finally {
            connection.disconnect()
        }
    }

    private fun cookiesOf(connection: HttpURLConnection): List<String> =
        connection.headerFields.entries.filter { (name, _) -> name.equals("Set-Cookie", ignoreCase = true) }.flatMap { it.value }

    /** EiN's reason for a refusal, its answer's text. */
    private fun reason(connection: HttpURLConnection): String =
        connection.errorStream?.bufferedReader()?.use { it.readText() }?.trim()?.ifEmpty { null } ?: "no reason given"

    private fun answered(status: Int, connection: HttpURLConnection): String =
        listOfNotNull("it answered $status", connection.responseMessage?.ifBlank { null }).joinToString(" ")
}
