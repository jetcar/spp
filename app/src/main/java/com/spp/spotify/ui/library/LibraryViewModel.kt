package com.spp.spotify.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.spp.spotify.data.model.*
import com.spp.spotify.data.repository.SpotifyRepository
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SpotifyRepository(application)

    enum class Tab { PLAYLISTS, ALBUMS, LIKED }

    private val _playlists = MutableLiveData<List<Playlist>>()
    val playlists: LiveData<List<Playlist>> = _playlists

    private val _albums = MutableLiveData<List<Album>>()
    val albums: LiveData<List<Album>> = _albums

    private val _likedTracks = MutableLiveData<List<Track>>()
    val likedTracks: LiveData<List<Track>> = _likedTracks

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _currentTab = MutableLiveData(Tab.PLAYLISTS)
    val currentTab: LiveData<Tab> = _currentTab

    init { loadPlaylists() }

    fun selectTab(tab: Tab) {
        _currentTab.value = tab
        when (tab) {
            Tab.PLAYLISTS -> loadPlaylists()
            Tab.ALBUMS    -> loadAlbums()
            Tab.LIKED     -> loadLikedTracks()
        }
    }

    private fun loadPlaylists() = viewModelScope.launch {
        _isLoading.value = true
        repo.getUserPlaylists().onSuccess { _playlists.value = it.items }
        _isLoading.value = false
    }

    private fun loadAlbums() = viewModelScope.launch {
        _isLoading.value = true
        repo.getSavedAlbums().onSuccess { _albums.value = it.items.map { s -> s.album } }
        _isLoading.value = false
    }

    private fun loadLikedTracks() = viewModelScope.launch {
        _isLoading.value = true
        repo.getLikedTracks().onSuccess { _likedTracks.value = it.items.map { s -> s.track } }
        _isLoading.value = false
    }
}
