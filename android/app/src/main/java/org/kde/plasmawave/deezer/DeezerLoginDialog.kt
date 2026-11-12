package org.kde.plasmawave.deezer

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun DeezerLoginDialog(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onLogin: (String) -> Unit
) {
    val context = LocalContext.current
    val currentBusy by rememberUpdatedState(busy)
    val currentOnLogin by rememberUpdatedState(onLogin)
    // Keep a failed candidate from being retried continuously while the page remains open.
    val lastSubmitted = remember { mutableStateOf<String?>(null) }
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
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val uri = request.url
                    val host = uri.host?.lowercase().orEmpty()
                    if (uri.scheme == "https" && (host == "deezer.com" || host.endsWith(".deezer.com"))) return false
                    if (uri.scheme == "https") {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { }
                    }
                    return true
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    checkCookie()
                }
            }
            // Preserve an existing web session so a successful profile selection can finish.
            loadUrl("https://www.deezer.com/login")
        }
    }
    // The profile selector is a single-page app: its cookie can change without a page load.
    LaunchedEffect(webView) {
        while (isActive) {
            checkCookie()
            delay(750)
        }
    }
    DisposableEffect(webView) { onDispose { webView.stopLoading(); webView.destroy() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                    Text("Deezer'a giriş yap", style = MaterialTheme.typography.titleLarge)
                }
                if (busy) Text("Deezer oturumu doğrulanıyor…", style = MaterialTheme.typography.bodyMedium)
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { lastSubmitted.value = null; checkCookie() }, enabled = !busy) {
                        Text("Tekrar dene")
                    }
                }
                AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
