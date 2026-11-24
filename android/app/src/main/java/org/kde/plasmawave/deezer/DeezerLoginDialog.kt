package org.kde.plasmawave.deezer

import android.content.Intent
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.kde.plasmawave.theme.LocalPlasmaColors

private const val CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

private val EXACT_OAUTH_HOSTS = setOf(
    "accounts.google.com",
    "accounts.youtube.com",
    "myaccount.google.com",
    "apis.google.com",
    "www.google.com",
    "ssl.gstatic.com",
    "www.gstatic.com",
    "gstatic.com",
    "facebook.com",
    "www.facebook.com",
    "m.facebook.com",
    "web.facebook.com",
    "appleid.apple.com",
    "idmsa.apple.com",
    "login.live.com",
    "login.microsoftonline.com"
)

internal fun isAllowedAuthHost(host: String): Boolean {
    val h = host.trim().lowercase()
    if (h.isEmpty()) return false
    return h == "deezer.com" || h.endsWith(".deezer.com") ||
        h == "dzcdn.net" || h.endsWith(".dzcdn.net") ||
        h in EXACT_OAUTH_HOSTS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeezerLoginDialog(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onLogin: (String) -> Unit
) {
    val context = LocalContext.current
    val colors = LocalPlasmaColors.current
    val currentBusy by rememberUpdatedState(busy)
    val currentOnLogin by rememberUpdatedState(onLogin)
    val lastSubmitted = remember { mutableStateOf<String?>(null) }

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Web (Google/Deezer), 1 = ARL token
    var arlInput by remember { mutableStateOf("") }
    val progressState = remember { mutableIntStateOf(0) }
    val hasPageError = remember { mutableStateOf(false) }

    val checkCookie = {
        if (!currentBusy) {
            val arl = DeezerWebCookies.readArl()
            if (arl != null && arl != lastSubmitted.value) {
                lastSubmitted.value = arl
                currentOnLogin(arl)
            }
        }
    }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Configure clean modern Chrome user agent to prevent Google 403 disallowed_useragent
            // and Deezer "cannotconnectwithsocials" restrictions
            settings.userAgentString = CHROME_USER_AGENT
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressState.intValue = newProgress
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    if (resultMsg == null || view == null) return false
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                    // Intercept popup (e.g. Deezer's "Google ile devam et" window.open call)
                    // and route verified HTTPS OAuth URL into the main WebView
                    val popupWebView = WebView(context).apply {
                        this.settings.javaScriptEnabled = true
                        this.settings.domStorageEnabled = true
                        this.settings.allowFileAccess = false
                        this.settings.allowContentAccess = false
                        this.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        this.settings.userAgentString = CHROME_USER_AGENT
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        this.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view2: WebView, request: WebResourceRequest): Boolean {
                                val targetUri = request.url
                                val targetHost = targetUri.host?.lowercase().orEmpty()
                                if (targetUri.scheme == "https" && isAllowedAuthHost(targetHost)) {
                                    view.loadUrl(targetUri.toString())
                                }
                                view2.stopLoading()
                                view2.destroy()
                                return true
                            }
                        }
                    }
                    transport.webView = popupWebView
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView?) {
                    super.onCloseWindow(window)
                    window?.stopLoading()
                    window?.destroy()
                    checkCookie()
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val host = uri.host?.lowercase().orEmpty()

                    // Keep Deezer and OAuth providers (Google, Facebook, Apple) inside this WebView
                    if (uri.scheme == "https" && isAllowedAuthHost(host)) {
                        checkCookie()
                        return false
                    }

                    // For external marketing/help links, open in system browser
                    if (uri.scheme == "https") {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { }
                    }
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    hasPageError.value = false
                    checkCookie()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    checkCookie()
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        hasPageError.value = true
                    }
                }
            }

            loadUrl("https://www.deezer.com/login")
        }
    }

    LaunchedEffect(webView) {
        while (isActive) {
            checkCookie()
            delay(600)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.clearHistory()
            webView.clearFormData()
            webView.destroy()
        }
    }

    BackHandler {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // Top Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (webView.canGoBack()) webView.goBack() else onDismiss()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Deezer Giriş",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { webView.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Yenile")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Kapat")
                    }
                }

                // Progress Bar
                val progress = progressState.intValue
                if (selectedTab == 0 && progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                } else {
                    Spacer(Modifier.height(3.dp))
                }

                // Mode Tabs (Web Giriş / ARL Token)
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = {}
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Web ile Giriş (Google / Deezer)") },
                        icon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("ARL Kodu ile Giriş") },
                        icon = { Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                // Busy Status
                AnimatedVisibility(visible = busy) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Deezer oturumu bağlanıyor ve doğrulanıyor…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // Error Banner
                if (error != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { lastSubmitted.value = null; checkCookie() }) {
                                Text("Tekrar Dene")
                            }
                        }
                    }
                }

                // Content: Tab 0 (WebView) or Tab 1 (ARL Direct Input)
                if (selectedTab == 0) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            factory = { webView },
                            modifier = Modifier.fillMaxSize()
                        )

                        // If Page Failed to load (offline or network error)
                        if (hasPageError.value) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = colors.card,
                                tonalElevation = 3.dp
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        "Sayfa Yüklenemedi",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "İnternet bağlantını kontrol edip tekrar dene veya ARL ile giriş sekmesini kullan.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(onClick = { webView.reload() }) {
                                            Text("Yeniden Yükle")
                                        }
                                        OutlinedButton(onClick = { selectedTab = 1 }) {
                                            Text("ARL ile Giriş Yap")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Direct ARL Input Tab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Deezer ARL Kodu",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Deezer web tarayıcınızdaki (Chrome / Firefox) 'arl' çerezini buraya yapıştırarak doğrudan giriş yapabilirsiniz.\n\n" +
                            "Nasıl bulunur:\n" +
                            "1. Bilgisayarda veya telefonda tarayıcıdan deezer.com'a girin ve giriş yapın.\n" +
                            "2. Tarayıcı Geliştirici Araçları (F12) > Application/Depolama > Cookies > deezer.com sekmesini açın.\n" +
                            "3. 'arl' çerezinin değerini kopyalayıp aşağıdaki kutucuğa yapıştırın.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            lineHeight = 19.sp
                        )

                        OutlinedTextField(
                            value = arlInput,
                            onValueChange = { arlInput = it },
                            label = { Text("ARL Token (192 karakter)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                val token = arlInput.trim()
                                if (token.isNotBlank()) {
                                    currentOnLogin(token)
                                }
                            },
                            enabled = arlInput.isNotBlank() && !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("ARL ile Bağlan")
                        }
                    }
                }
            }
        }
    }
}
