package eu.einhq.app

import java.net.URI
import java.net.URLDecoder

/** `url`'s web origin, `scheme://host[:port]` without a default port, or null for anything but an http or https URL with a host. */
fun originOf(url: String): String? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.lowercase() ?: return null
    val port = uri.port.takeIf { it != -1 && it != (if (scheme == "https") 443 else 80) }
    return "$scheme://$host${if (port != null) ":$port" else ""}"
}

/**
 * The origin of the EiN a person typed the address of: `ein.example` is `https://ein.example`, and
 * a typed `http://` is kept; null when it names no web server.
 */
fun serverOf(typed: String): String? {
    val text = typed.trim()
    if (text.isEmpty()) return null
    return originOf(if (Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(text)) text else "https://$text")
}

/** Whether `url` is the Web UI's sign-in page on `server`, an origin. */
fun isSignInPage(url: String, server: String): Boolean =
    originOf(url) == server && runCatching { URI(url).path }.getOrNull() == "/login"

/**
 * Where the sign-in page at `url` would have gone once signed in: its `next`, when that is a path
 * of the same server and not the sign-in page itself, else `/`.
 */
fun signInNext(url: String): String {
    val next = runCatching { URI(url).rawQuery }.getOrNull()?.let(::queryOf)?.get("next")
    val samePath = next != null && next.startsWith("/") && !next.startsWith("//") && !next.startsWith("/\\")
    return if (samePath && !Regex("^/login(?:[/?#]|$)").containsMatchIn(next!!)) next else "/"
}

/** A URL's query, decoded; the first of a name given twice. */
fun queryOf(raw: String): Map<String, String> = raw.split('&').mapNotNull { pair ->
    val at = pair.indexOf('=')
    if (at <= 0) null else decode(pair.substring(0, at)) to decode(pair.substring(at + 1))
}.reversed().toMap()

private fun decode(text: String): String = URLDecoder.decode(text, "UTF-8")
