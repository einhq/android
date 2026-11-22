package eu.einhq.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.work.ExistingWorkPolicy
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import eu.einhq.app.backup.Backup
import eu.einhq.app.backup.BackupBridge
import eu.einhq.app.backup.MediaAccess
import eu.einhq.app.track.LocationAccess
import eu.einhq.app.track.Tracking
import eu.einhq.app.track.TrackingBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * The app: EiN's Web UI in a WebView, signed in with the token the app was paired with, never a
 * password. Unpaired, it asks for the QR code on the phone's Info tab in EiN, scanned here or with
 * the camera (`ein://pair?…`). Whenever the Web UI goes to its sign-in page, the app trades the
 * token for a new session instead; EiN refusing the token asks for a new pairing.
 *
 * Pages of the paired EiN load in the WebView, anything else in the app that opens it, and the
 * Web UI's history is what Back goes through. A screen of the app's own stands in for the page
 * while it is pairing, signing in, or cannot reach EiN. The photo backup (`backup/`) and the
 * position reports (`track/`) run without it; the Web UI's App page sets them up through the bridge,
 * and the activity asks Android for the permissions they need. A page of the paired EiN that asks
 * for the phone's position gets it once the app may know it.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var store: PairingStore
    private var pairing: Pairing? = null

    private lateinit var web: WebView
    private lateinit var screen: View
    private lateinit var fullscreen: FrameLayout
    private val chooser = FileChooser(this)
    private val bridge = Bridge(pairing = { pairing }, unpair = ::unpair, featureCall = ::featureCall)
    private val backup by lazy { BackupBridge(applicationContext) }
    private val tracking by lazy { TrackingBridge(applicationContext) }

    /** Android's answer to a permission request, for whoever waits for it. */
    private var permissionsAnswered: (() -> Unit)? = null
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsAnswered?.invoke()
        permissionsAnswered = null
    }

    /** The origin `window.EinApp` is given to now. */
    private var bridgeOrigin: String? = null

    /** When the token was last traded for a session: the Web UI asking to sign in again soon after means it did not hold. */
    private var tradedAt = 0L
    private var signingIn = false
    /** The page loading is a fresh start: what was before it is no longer the way Back goes. */
    private var freshStart = false

    /** What the page shows full screen (a video, the lightbox), and how to tell it it was closed. */
    private var custom: Pair<View, WebChromeClient.CustomViewCallback>? = null

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (custom != null) closeFullscreen(tellPage = true) else if (web.isVisible && web.canGoBack()) web.goBack()
            updateBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContentView(R.layout.activity_main)
        val content = findViewById<View>(android.R.id.content)
        web = findViewById(R.id.web)
        screen = findViewById(R.id.screen)
        fullscreen = findViewById(R.id.fullscreen)
        // The page sits between the system bars and above the keyboard; full screen, the bars are hidden and give no room.
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        onBackPressedDispatcher.addCallback(this, back)
        store = PairingStore(this)
        pairing = store.load()
        setUpWeb()

        // In line with their settings after the app was updated.
        if (pairing != null) {
            Backup.schedule(this, restart = false)
            Tracking.apply(this)
        }

        // A link that started the activity is handled once, not again when it is made anew.
        if (savedInstanceState == null && pairingLinkIn(intent)) return
        val path = savedInstanceState?.getString(STATE_PATH) ?: intent.getStringExtra(EXTRA_PATH) ?: "/"
        if (pairing != null) open(path) else askToPair(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (pairingLinkIn(intent)) return
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path != null && pairing != null) open(path)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val paired = pairing ?: return
        web.url?.takeIf { originOf(it) == paired.server }?.let { outState.putString(STATE_PATH, it.removePrefix(paired.server).ifEmpty { "/" }) }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled") // The Web UI is a JavaScript application.
    private fun setUpWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString EiNApp/${BuildConfig.VERSION_NAME}"
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ -> download(url, userAgent, contentDisposition, mimeType) }
    }

    // --- Pairing ---

    /** Pair with the link `intent` carries, if it carries one; whether it did. */
    private fun pairingLinkIn(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (!data.scheme.equals("ein", ignoreCase = true)) return false
        intent.data = null
        val link = PairingLink.parse(data.toString())
        if (link == null) askToPair(getString(R.string.not_a_pairing_code)) else confirmPairing(link)
        return true
    }

    /** The pairing screen, with what went wrong last when something did. */
    private fun askToPair(problem: String?) {
        val paired = pairing
        showScreen(
            title = getString(R.string.pair_title),
            body = listOfNotNull(problem, getString(R.string.pair_body)).joinToString("\n\n"),
            primary = getString(R.string.scan) to ::scan,
            secondary = if (paired != null) getString(R.string.back_to_ein) to { open("/") } else null,
        )
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        val scanner = GmsBarcodeScanning.getClient(this, options)
        val failed = { error: Exception -> askToPair(getString(R.string.scan_failed, error.message ?: error.javaClass.simpleName)) }
        // The scanner is a part of Google Play services, fetched the first time it is wanted.
        ModuleInstall.getClient(this).installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
            .addOnSuccessListener {
                scanner.startScan()
                    .addOnSuccessListener { code ->
                        val link = PairingLink.parse(code.rawValue.orEmpty())
                        if (link == null) askToPair(getString(R.string.not_a_pairing_code)) else confirmPairing(link)
                    }
                    .addOnFailureListener(failed)
            }
            .addOnFailureListener(failed)
    }

    /** Pair with `link`, once asked whether to when the phone is paired otherwise now. */
    private fun confirmPairing(link: PairingLink) {
        val paired = pairing
        if (paired == null || (paired.server == link.server && paired.device == link.device)) return pair(link)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.replace_title)
            .setMessage(getString(R.string.replace_body, hostOf(paired.server), paired.device, hostOf(link.server), link.device))
            .setPositiveButton(R.string.replace) { _, _ -> pair(link) }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            // Kept as it was: on the page, or back to it when the link started the app.
            .setOnCancelListener { if (!web.isVisible) open("/") }
            .show()
    }

    private fun pair(link: PairingLink) {
        showScreen(body = getString(R.string.pairing_with, hostOf(link.server)), busy = true)
        lifecycleScope.launch {
            when (val answer = withContext(Dispatchers.IO) { Endpoints.pair(link) }) {
                is Answer.Done -> {
                    val paired = answer.value
                    // The same phone paired again as the same account keeps its photo backup and position reports.
                    val same = pairing?.let { it.server == paired.server && it.device == paired.device && it.user == paired.user } == true
                    forget(keepFeatures = same)
                    store.save(paired)
                    pairing = paired
                    signIn("/")
                    if (same) {
                        Backup.schedule(this@MainActivity, restart = false)
                        Tracking.apply(this@MainActivity)
                        Tracking.send(this@MainActivity, ExistingWorkPolicy.REPLACE)
                    }
                }
                is Answer.Refused -> askToPair(getString(R.string.pairing_refused, hostOf(link.server), answer.reason))
                is Answer.Unreachable -> askToPair(getString(R.string.unreachable_body, hostOf(link.server), answer.reason))
            }
        }
    }

    /**
     * Take the pairing away on EiN, as the Web UI's App page asks, then forget it and ask for a new
     * one; `answered` gets null first, or EiN's reason when it would not, and nothing changes then.
     */
    private fun unpair(answered: (String?) -> Unit) {
        val paired = pairing ?: return answered(null)
        lifecycleScope.launch {
            when (val answer = withContext(Dispatchers.IO) { Endpoints.unpair(paired) }) {
                is Answer.Done -> {
                    answered(null)
                    forget()
                    askToPair(null)
                }
                is Answer.Refused -> answered(answer.reason)
                is Answer.Unreachable -> answered(getString(R.string.unreachable_body, hostOf(paired.server), answer.reason))
            }
        }
    }

    /**
     * Forget the pairing and everything signed in with it: its cookies, the page and its history, and,
     * unless `keepFeatures`, the photo backup and the position reports.
     */
    private fun forget(keepFeatures: Boolean = false) {
        if (!keepFeatures) {
            Backup.forget(this)
            Tracking.forget(this)
        }
        store.clear()
        pairing = null
        CookieManager.getInstance().removeAllCookies(null)
        web.loadUrl("about:blank")
        web.clearHistory()
        updateBridge()
    }

    // --- The photo backup and the position reports ---

    /** Do a method of the bridge's for the photo backup or the position reports off the main thread, and answer on it. */
    private fun featureCall(method: String, params: JSONObject, answered: (Result<JSONObject>) -> Unit) {
        lifecycleScope.launch {
            answered(
                runCatching {
                    withContext(Dispatchers.IO) {
                        if (method in TrackingBridge.METHODS) tracking.call(method, params, ::askTrackingPermissions) else backup.call(method, params, ::askBackupPermissions)
                    }
                },
            )
        }
    }

    /** Ask Android for `permissions`, and return once the phone's owner has answered; at once while another such question is open. */
    private suspend fun ask(permissions: Array<String>) = withContext(Dispatchers.Main) {
        if (permissionsAnswered != null) return@withContext
        suspendCancellableCoroutine { done ->
            permissionsAnswered = { done.resume(Unit) }
            askPermissions.launch(permissions)
        }
    }

    /** Whether `permission` was asked for before and Android will not ask again: only the phone's settings can grant it now. */
    private fun refusedForGood(permission: String): Boolean =
        getSharedPreferences("asked", MODE_PRIVATE).getBoolean(permission, false) && !shouldShowRequestPermissionRationale(permission)

    private fun markAsked(permission: String) = getSharedPreferences("asked", MODE_PRIVATE).edit { putBoolean(permission, true) }

    private fun openAppSettings() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    /**
     * Ask for what the backup needs, and return once Android has answered. Once the photos have been
     * refused for good, Android asks no more, so the app's page in the phone's settings opens instead.
     */
    private suspend fun askBackupPermissions() = withContext(Dispatchers.Main) {
        val photos = Backup.photosPermission()
        if (Backup.mediaAccess(this@MainActivity) == MediaAccess.NONE && refusedForGood(photos)) return@withContext openAppSettings()
        markAsked(photos)
        ask(Backup.wantedPermissions())
    }

    /**
     * Ask for what the position reports need, in Android's two steps: the position while the app is
     * in use, with how the phone moves and a notification; then, once the app has said why, the
     * position all the time, which Android asks on a page of the phone's settings. Once one of them
     * was refused for good, the app's page in the phone's settings opens instead.
     */
    private suspend fun askTrackingPermissions() = withContext(Dispatchers.Main) {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        if (Tracking.access(this@MainActivity) == LocationAccess.NONE && refusedForGood(fine)) return@withContext openAppSettings()
        val wanted = Tracking.foregroundPermissions()
        if (wanted.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            markAsked(fine)
            ask(wanted)
        }
        if (Tracking.access(this@MainActivity) != LocationAccess.IN_USE || !discloseBackgroundLocation()) return@withContext
        val background = Manifest.permission.ACCESS_BACKGROUND_LOCATION
        if (refusedForGood(background)) return@withContext openAppSettings()
        markAsked(background)
        ask(arrayOf(background))
    }

    /**
     * Say what the app does with the position all the time before Android asks for it, as Google
     * Play requires; whether the phone's owner chose to go on.
     */
    private suspend fun discloseBackgroundLocation(): Boolean = suspendCancellableCoroutine { done ->
        val option = if (Build.VERSION.SDK_INT >= 30) packageManager.backgroundPermissionOptionLabel else getString(R.string.allow_all_the_time)
        var answered = false
        val answer = { yes: Boolean ->
            if (!answered) {
                answered = true
                done.resume(yes)
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tracking_disclosure_title)
            .setMessage(getString(R.string.tracking_disclosure_body, hostOf(pairing?.server.orEmpty()), option))
            .setPositiveButton(R.string.go_on) { _, _ -> answer(true) }
            .setNegativeButton(R.string.not_now) { _, _ -> answer(false) }
            .setOnDismissListener { answer(false) }
            .show()
    }

    // --- Signing in ---

    /** Open `path` of the paired EiN, signed in: with the session the WebView has, or a new one. */
    private fun open(path: String) {
        val paired = pairing ?: return askToPair(null)
        showScreen(busy = true)
        val cookie = CookieManager.getInstance().getCookie(paired.server)
        lifecycleScope.launch {
            when (val answer = withContext(Dispatchers.IO) { Endpoints.check(paired.server, cookie) }) {
                is Answer.Done -> if (answer.value.signedIn) {
                    keepCookies(paired.server, answer.value.cookies)
                    load(paired, path)
                } else {
                    signIn(path)
                }
                is Answer.Refused -> signIn(path)
                is Answer.Unreachable -> cannotReach(paired, answer.reason) { open(path) }
            }
        }
    }

    /** Trade the token for a new Web UI session and go on to `next`, a path. */
    private fun signIn(next: String) {
        val paired = pairing ?: return askToPair(null)
        if (signingIn) return
        if (SystemClock.elapsedRealtime() - tradedAt < RETRADE_MS) {
            // A session that did not hold would be asked for again and again.
            tradedAt = 0
            return showScreen(
                title = getString(R.string.sign_in_failed_title),
                body = getString(R.string.sign_in_failed, hostOf(paired.server)),
                primary = getString(R.string.try_again) to { signIn(next) },
            )
        }
        signingIn = true
        showScreen(busy = true)
        lifecycleScope.launch {
            val answer = withContext(Dispatchers.IO) { Endpoints.session(paired, next) }
            signingIn = false
            when (answer) {
                is Answer.Done -> {
                    tradedAt = SystemClock.elapsedRealtime()
                    keepCookies(paired.server, answer.value.cookies)
                    load(paired, answer.value.location)
                }
                is Answer.Refused -> showScreen(
                    title = getString(R.string.refused_title),
                    body = getString(R.string.refused_body, hostOf(paired.server), answer.reason),
                    primary = getString(R.string.pair_again) to { askToPair(null) },
                    secondary = getString(R.string.try_again) to { signIn(next) },
                )
                is Answer.Unreachable -> cannotReach(paired, answer.reason) { signIn(next) }
            }
        }
    }

    /** Hand the WebView the cookies an answer set, and wait until it has them. */
    private suspend fun keepCookies(server: String, cookies: List<String>) {
        val manager = CookieManager.getInstance()
        for (cookie in cookies) suspendCancellableCoroutine { done -> manager.setCookie(server, cookie) { done.resume(Unit) } }
        manager.flush()
    }

    /** Show the page at `location`, a path or a URL of the paired EiN, as a fresh start. */
    private fun load(paired: Pairing, location: String) {
        updateBridge()
        freshStart = true
        showWeb()
        web.loadUrl(if (location.startsWith("/")) paired.server + location else location)
    }

    private fun cannotReach(paired: Pairing, reason: String, retry: () -> Unit) = showScreen(
        title = getString(R.string.unreachable_title),
        body = getString(R.string.unreachable_body, hostOf(paired.server), reason),
        primary = getString(R.string.try_again) to retry,
    )

    /** `window.EinApp` for the paired EiN's pages, and no other's. */
    private fun updateBridge() {
        val origin = pairing?.server
        if (origin == bridgeOrigin || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        if (bridgeOrigin != null) WebViewCompat.removeWebMessageListener(web, Bridge.NAME)
        if (origin != null) WebViewCompat.addWebMessageListener(web, Bridge.NAME, setOf(origin), bridge)
        bridgeOrigin = origin
    }

    // --- What is shown ---

    private fun showWeb() {
        screen.isVisible = false
        web.isVisible = true
        updateBack()
    }

    /** A screen of the app's own over the page: a title, a text, a spinner while `busy`, and up to two buttons. */
    private fun showScreen(
        title: String? = null,
        body: String? = null,
        busy: Boolean = false,
        primary: Pair<String, () -> Unit>? = null,
        secondary: Pair<String, () -> Unit>? = null,
    ) {
        findViewById<TextView>(R.id.title).apply {
            text = title
            isVisible = title != null
        }
        findViewById<TextView>(R.id.body).apply {
            text = body
            isVisible = body != null
        }
        findViewById<View>(R.id.progress).isVisible = busy
        for ((id, action) in listOf(R.id.primary to primary, R.id.secondary to secondary)) {
            findViewById<Button>(id).apply {
                isVisible = action != null
                text = action?.first
                setOnClickListener { action?.second?.invoke() }
            }
        }
        web.visibility = View.INVISIBLE
        screen.isVisible = true
        updateBack()
    }

    private fun updateBack() {
        back.isEnabled = custom != null || (web.isVisible && web.canGoBack())
    }

    /** Leave what the page shows full screen; `tellPage` when the page did not ask to. */
    private fun closeFullscreen(tellPage: Boolean) {
        val (view, callback) = custom ?: return
        custom = null
        fullscreen.removeView(view)
        fullscreen.isVisible = false
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        if (tellPage) callback.onCustomViewHidden()
    }

    // --- Leaving the page ---

    /** Open `uri` in the app on the phone that takes it: a link to anywhere but the paired EiN. */
    private fun openOutside(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_app_for_link, uri.toString()), Toast.LENGTH_LONG).show()
        }
    }

    /** Save a file the page downloads into Downloads, with the session's cookie. */
    private fun download(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val uri = url.toUri()
        if (uri.scheme != "http" && uri.scheme != "https") {
            Toast.makeText(this, R.string.no_download, Toast.LENGTH_LONG).show()
            return
        }
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(uri)
            .setMimeType(mimeType)
            .addRequestHeader("User-Agent", userAgent)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        getSystemService<DownloadManager>()?.enqueue(request)
        Toast.makeText(this, getString(R.string.downloading, name), Toast.LENGTH_SHORT).show()
    }

    private inner class Client : WebViewClientCompat() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val paired = pairing ?: return true
            val url = request.url.toString()
            if (originOf(url) == paired.server) {
                if (!isSignInPage(url, paired.server)) return false
                signIn(signInNext(url))
                return true
            }
            if (!request.isForMainFrame) return false
            openOutside(request.url)
            return true
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            updateBack()
            // The Web UI moving to its sign-in page in the page, without loading it.
            val paired = pairing ?: return
            if (isSignInPage(url, paired.server)) signIn(signInNext(url))
        }

        override fun onPageFinished(view: WebView, url: String) {
            // What was before a fresh start goes once its page is in.
            if (freshStart && url != "about:blank") {
                freshStart = false
                view.clearHistory()
            }
            updateBack()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceErrorCompat) {
            val paired = pairing ?: return
            if (!request.isForMainFrame) return
            val reason = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION)) error.description.toString() else ""
            val path = request.url.toString().removePrefix(paired.server).ifEmpty { "/" }
            cannotReach(paired, reason) { open(path) }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            val paired = pairing ?: return
            // A proxy in front of EiN answers so while EiN is down or starting.
            if (!request.isForMainFrame || response.statusCode !in GATEWAY_ERRORS) return
            val path = request.url.toString().removePrefix(paired.server).ifEmpty { "/" }
            cannotReach(paired, getString(R.string.unreachable_status, hostOf(paired.server), response.statusCode)) { open(path) }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's process ended, and the WebView with it: start again with a new one.
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        /** A page of the paired EiN may know where the phone is, once the app may; any other page may not. */
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            val paired = pairing
            if (paired == null || originOf(origin) != paired.server) return callback.invoke(origin, false, false)
            if (Tracking.access(this@MainActivity) != LocationAccess.NONE) return callback.invoke(origin, true, false)
            lifecycleScope.launch {
                ask(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                callback.invoke(origin, Tracking.access(this@MainActivity) != LocationAccess.NONE, false)
            }
        }

        override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
            chooser.open(callback, params)

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (custom != null) return callback.onCustomViewHidden()
            custom = view to callback
            fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fullscreen.isVisible = true
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            updateBack()
        }

        override fun onHideCustomView() {
            closeFullscreen(tellPage = false)
            updateBack()
        }
    }

    companion object {
        /** A path of the paired EiN to open, for an intent from the app's own notification. */
        const val EXTRA_PATH = "eu.einhq.app.PATH"

        private const val STATE_PATH = "path"
        /** How soon after a new session the Web UI asking to sign in again means the session does not hold. */
        private const val RETRADE_MS = 15_000L
        private val GATEWAY_ERRORS = setOf(502, 503, 504)

        private fun hostOf(server: String): String = server.toUri().authority ?: server
    }
}
