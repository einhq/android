package eu.einhq.app

import java.net.URI

/**
 * What the QR code on a phone's Info tab in EiN says, `ein://pair?server=<origin>&device=<phone>&code=<code>`:
 * the EiN to pair with, the phone to pair as and the one-time code that does it.
 */
data class PairingLink(val server: String, val device: String, val code: String) {
    companion object {
        /** The pairing link `text` is, or null when it is none or names no web server. */
        fun parse(text: String): PairingLink? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("ein", ignoreCase = true) || !uri.host.equals("pair", ignoreCase = true)) return null
            val params = queryOf(uri.rawQuery ?: return null)
            val server = params["server"]?.let(::originOf) ?: return null
            val device = params["device"]?.takeIf { it.isNotBlank() } ?: return null
            val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null
            return PairingLink(server, device, code)
        }
    }
}
