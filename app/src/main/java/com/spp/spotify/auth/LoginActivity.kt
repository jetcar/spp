package com.spp.spotify.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.spp.spotify.BuildConfig
import com.spp.spotify.MainActivity
import com.spp.spotify.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    /** Guards against processing the OAuth callback more than once per launch. */
    private var callbackHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            if (TokenManager.isLoggedIn(this@LoginActivity)) {
                goToMain()
                return@launch
            }
        }

        binding.btnLogin.setOnClickListener { openSpotifyAuth() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        handleCallback(intent)
    }

    private fun handleCallback(intent: Intent?) {
        if (callbackHandled) return
        val uri = intent?.data ?: return
        if (!uri.toString().startsWith(BuildConfig.SPOTIFY_REDIRECT_URI)) return

        callbackHandled = true

        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        when {
            code != null  -> exchangeToken(code)
            error != null -> Toast.makeText(this, "Auth error: $error", Toast.LENGTH_LONG).show()
        }
    }

    private fun openSpotifyAuth() {
        callbackHandled = false
        val authUrl = SpotifyAuthManager.generateAuthUrl()
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, authUrl.toUri())
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, authUrl.toUri()))
        }
    }

    private fun exchangeToken(code: String) {
        binding.btnLogin.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val success = SpotifyAuthManager.exchangeCodeForTokens(this@LoginActivity, code)
            if (success) {
                goToMain()
            } else {
                binding.btnLogin.isEnabled = true
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@LoginActivity, "Authentication failed. Please try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
