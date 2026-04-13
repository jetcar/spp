package com.spp.spotify

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.spp.spotify.auth.LoginActivity
import com.spp.spotify.auth.TokenManager
import com.spp.spotify.databinding.ActivityMainBinding
import com.spp.spotify.ui.player.PlayerViewModel
import com.spp.spotify.update.UpdateChecker
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private val playerViewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            if (!TokenManager.isLoggedIn(this@MainActivity)) {
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                finish()
                return@launch
            }
            initUI()
            checkForUpdate()
        }
    }

    private fun initUI() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        binding.bottomNavigationView.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.nowPlayingFragment -> {
                    binding.bottomNavigationView.visibility = View.GONE
                    binding.miniPlayerLayout.root.visibility = View.GONE
                }
                else -> {
                    binding.bottomNavigationView.visibility = View.VISIBLE
                }
            }
        }

        playerViewModel.currentTrack.observe(this) { track ->
            val isPlayerScreen = navController.currentDestination?.id == R.id.nowPlayingFragment
            if (track != null && !isPlayerScreen) {
                binding.miniPlayerLayout.root.visibility = View.VISIBLE
                binding.miniPlayerLayout.tvMiniTrackName.text = track.name
                binding.miniPlayerLayout.tvMiniArtistName.text =
                    track.artists.joinToString(", ") { it.name }
                val imageUrl = track.album?.images?.firstOrNull()?.url
                Glide.with(this).load(imageUrl)
                    .placeholder(R.drawable.placeholder_music)
                    .into(binding.miniPlayerLayout.ivMiniAlbumArt)
            } else if (track == null) {
                binding.miniPlayerLayout.root.visibility = View.GONE
            }
        }

        playerViewModel.isPlaying.observe(this) { isPlaying ->
            binding.miniPlayerLayout.btnMiniPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_arrow_24
            )
        }

        binding.miniPlayerLayout.root.setOnClickListener {
            navController.navigate(R.id.nowPlayingFragment)
        }
        binding.miniPlayerLayout.btnMiniPlayPause.setOnClickListener {
            playerViewModel.togglePlayPause()
        }
        binding.miniPlayerLayout.btnMiniNext.setOnClickListener {
            playerViewModel.skipToNext()
        }

        playerViewModel.startPolling()
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
                .onSuccess { info ->
                    if (info.isUpdateAvailable && !isFinishing) {
                        showUpdateDialog(info.latestVersion, info.apkDownloadUrl ?: info.releaseUrl)
                    }
                }
        }
    }

    private fun showUpdateDialog(newVersion: String, downloadUrl: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available_title))
            .setMessage(getString(R.string.update_available_message, newVersion))
            .setPositiveButton(getString(R.string.update_download)) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
            }
            .setNegativeButton(getString(R.string.update_dismiss), null)
            .show()
    }
}
