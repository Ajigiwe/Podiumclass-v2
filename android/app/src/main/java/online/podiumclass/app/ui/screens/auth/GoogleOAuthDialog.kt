package online.podiumclass.app.ui.screens.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val GOOGLE_CLIENT_ID = "148808386481-qp65l50jmuabfte437aafmh85esth944.apps.googleusercontent.com"
private const val REDIRECT_URI = "https://lite-class.firebaseapp.com/__/auth/handler"
private const val CHROME_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GoogleOAuthDialog(
    onDismiss: () -> Unit,
    onTokenReceived: (String) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }

    val authUrl = remember {
        val base = "https://accounts.google.com/o/oauth2/v2/auth"
        Uri.parse(base).buildUpon()
            .appendQueryParameter("client_id", GOOGLE_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("scope", "email profile openid")
            .appendQueryParameter("prompt", "select_account")
            .build()
            .toString()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111522)),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top header bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D0D1A))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column {
                        Text(
                            text = "Sign in with Google",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Choose your Google Account",
                            color = Color(0xFF8888A8),
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF1845D4),
                        trackColor = Color(0xFF1E293B)
                    )
                }

                // In-App WebView for OAuth
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = CHROME_USER_AGENT

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        isLoading = true
                                        url?.let { checkRedirect(it) }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isLoading = false
                                        url?.let { checkRedirect(it) }
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val url = request?.url?.toString() ?: ""
                                        return checkRedirect(url)
                                    }

                                    private fun checkRedirect(url: String): Boolean {
                                        if (url.startsWith(REDIRECT_URI)) {
                                            // Extract access_token from fragment #access_token=ya29...
                                            val fragment = Uri.parse(url).fragment
                                            if (!fragment.isNullOrBlank()) {
                                                val params = fragment.split("&").associate {
                                                    val parts = it.split("=")
                                                    if (parts.size >= 2) parts[0] to parts[1] else parts[0] to ""
                                                }
                                                val accessToken = params["access_token"]
                                                if (!accessToken.isNullOrBlank()) {
                                                    onTokenReceived(accessToken)
                                                    return true
                                                }
                                            }
                                        }
                                        return false
                                    }
                                }
                                loadUrl(authUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
