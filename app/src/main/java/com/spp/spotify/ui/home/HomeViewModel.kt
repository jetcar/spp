package com.spp.spotify.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.spp.spotify.data.model.*
import com.spp.spotify.data.repository.SpotifyRepository
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SpotifyRepository(application)

    private val _user = MutableLiveData<User?>()
    val user: LiveData<User?> = _user

    private val _featuredPlaylists = MutableLiveData<List<Playlist>>()
    val featuredPlaylists: LiveData<List<Playlist>> = _featuredPlaylists

    private val _newReleases = MutableLiveData<List<Album>>()
    val newReleases: LiveData<List<Album>> = _newReleases

    private val _recentlyPlayed = MutableLiveData<List<Track>>()
    val recentlyPlayed: LiveData<List<Track>> = _recentlyPlayed

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    init { loadAll() }

    fun loadAll() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val userJob = launch { repo.getCurrentUser().onSuccess { _user.value = it } }
            val featuredJob = launch {
                repo.getFeaturedPlaylists()
                    .onSuccess { _featuredPlaylists.value = it.playlists.items }
                    .onFailure { _error.value = it.message }
            }
            val newJob = launch {
                repo.getNewReleases().onSuccess { _newReleases.value = it.albums.items }
            }
            val recentJob = launch {
                repo.getRecentlyPlayed()
                    .onSuccess { _recentlyPlayed.value = it.items.map { h -> h.track } }
            }
            userJob.join(); featuredJob.join(); newJob.join(); recentJob.join()
            _isLoading.value = false
        }
    }
}
