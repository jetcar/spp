package com.spp.tuneveil

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import com.spp.tuneveil.ui.configureTuneveilWebSettings
import com.spp.tuneveil.ui.createAuthenticationWebViewClient
import com.spp.tuneveil.ui.createLoggingWebChromeClient

/** A separate in-app window so Xiaomi can render Spotify Accounts independently of DRM playback. */
class AuthenticationActivity : ComponentActivity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL) ?: run { finish(); return }
        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            configureTuneveilWebSettings()
            webViewClient = createAuthenticationWebViewClient { _ ->
                CookieManager.getInstance().flush()
                startActivity(Intent(this@AuthenticationActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_RELOAD_PLAYER, true))
                finish()
            }
            webChromeClient = createLoggingWebChromeClient()
            loadUrl(loginUrl)
        }
        setContentView(webView, ViewGroup.LayoutParams(-1, -1))
    }

    override fun onDestroy() { webView?.destroy(); webView = null; super.onDestroy() }

    companion object {
        const val EXTRA_LOGIN_URL = "login_url"
        const val EXTRA_RELOAD_PLAYER = "reload_player"
        fun intent(context: Context, url: String) = Intent(context, AuthenticationActivity::class.java)
            .putExtra(EXTRA_LOGIN_URL, url)
    }
}
