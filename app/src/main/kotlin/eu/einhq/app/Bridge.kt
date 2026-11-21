package eu.einhq.app

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * `window.EinApp`, what the Web UI asks of the app. The WebView gives it only to the main frame of
 * pages of the paired EiN's origin. A call is the JSON `{ "id", "method", "params" }`, answered
 * `{ "id", "result" }` or `{ "id", "error" }`:
 *
 * - `info`: `{ version, server, device, user }`, the app's version and its pairing.
 * - `unpair`: the app takes its pairing away on EiN with its token, answers, then forgets the
 *   pairing and asks to be paired again; or answers with why it could not.
 */
class Bridge(
    private val pairing: () -> Pairing?,
    /** Unpair, and say how it went: null once done, or why not; what follows the answer is the caller's. */
    private val unpair: (answered: (String?) -> Unit) -> Unit,
) : WebViewCompat.WebMessageListener {
    override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val paired = pairing() ?: return
        if (!isMainFrame || originOf(sourceOrigin.toString()) != paired.server) return
        val call = runCatching { JSONObject(message.data ?: return) }.getOrNull() ?: return
        val answer = JSONObject().put("id", call.opt("id"))
        when (val method = call.optString("method")) {
            "info" -> answer.put(
                "result",
                JSONObject()
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("server", paired.server)
                    .put("device", paired.device)
                    .put("user", paired.user),
            )
            "unpair" -> return unpair { problem ->
                replyProxy.postMessage((if (problem == null) answer.put("result", JSONObject.NULL) else answer.put("error", problem)).toString())
            }
            else -> answer.put("error", "the app does not know $method")
        }
        replyProxy.postMessage(answer.toString())
    }

    companion object {
        /** The name it has on `window`. */
        const val NAME = "EinApp"
    }
}
