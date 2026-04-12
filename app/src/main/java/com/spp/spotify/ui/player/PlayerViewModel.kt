package com.spp.spotify.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.spp.spotify.data.model.Track
import com.spp.spotify.data.repository.SpotifyRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SpotifyRepository(application)

    companion object {
        private const val POLLING_INTERVAL_MS = 3_000L
        private const val SKIP_FETCH_DELAY_MS = 600L

        const val REPEAT_OFF = "off"
        const val REPEAT_CONTEXT = "context"
        const val REPEAT_TRACK = "track"
    }

    private val _currentTrack = MutableLiveData<Track?>()
    val currentTrack: LiveData<Track?> = _currentTrack

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _progressMs = MutableLiveData(0L)
    val progressMs: LiveData<Long> = _progressMs

    private val _durationMs = MutableLiveData(0L)
    val durationMs: LiveData<Long> = _durationMs

    private val _shuffleState = MutableLiveData(false)
    val shuffleState: LiveData<Boolean> = _shuffleState

    private val _repeatState = MutableLiveData("off")
    val repeatState: LiveData<String> = _repeatState

    private val _isSaved = MutableLiveData(false)
    val isSaved: LiveData<Boolean> = _isSaved

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var pollJob: kotlinx.coroutines.Job? = null

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                fetchState()
                delay(POLLING_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() { pollJob?.cancel() }

    private suspend fun fetchState() {
        repo.getPlayerState().onSuccess { state ->
            state?.let {
                if (_currentTrack.value?.id != it.track?.id) {
                    _currentTrack.value = it.track
                    it.track?.let { t -> checkSaved(t.id) }
                }
                _isPlaying.value = it.isPlaying
                _progressMs.value = it.progressMs ?: 0L
                _durationMs.value = it.track?.durationMs ?: 0L
                _shuffleState.value = it.shuffleState
                _repeatState.value = it.repeatState
            }
        }
    }

    fun playTrack(track: Track) {
        viewModelScope.launch {
            repo.play(uris = listOf(track.uri)).onSuccess {
                _currentTrack.value = track
                _isPlaying.value = true
                _durationMs.value = track.durationMs
            }.onFailure { _error.value = it.message }
        }
    }

    fun playContext(contextUri: String) {
        viewModelScope.launch {
            repo.play(contextUri = contextUri).onSuccess {
                _isPlaying.value = true
            }.onFailure { _error.value = it.message }
        }
    }

    fun togglePlayPause() {
        viewModelScope.launch {
            if (_isPlaying.value == true) {
                repo.pause().onSuccess { _isPlaying.value = false }
            } else {
                repo.play().onSuccess { _isPlaying.value = true }
            }
        }
    }

    fun skipToNext() {
        viewModelScope.launch { repo.skipToNext().onSuccess { delay(SKIP_FETCH_DELAY_MS); fetchState() } }
    }

    fun skipToPrevious() {
        viewModelScope.launch { repo.skipToPrevious().onSuccess { delay(SKIP_FETCH_DELAY_MS); fetchState() } }
    }

    fun seekToPosition(ms: Long) {
        viewModelScope.launch {
            repo.seekToPosition(ms).onSuccess { _progressMs.value = ms }
        }
    }

    fun toggleShuffle() {
        val new = !(_shuffleState.value ?: false)
        viewModelScope.launch { repo.setShuffle(new).onSuccess { _shuffleState.value = new } }
    }

    fun cycleRepeat() {
        val next = when (_repeatState.value) {
            REPEAT_OFF     -> REPEAT_CONTEXT
            REPEAT_CONTEXT -> REPEAT_TRACK
            else           -> REPEAT_OFF
        }
        viewModelScope.launch { repo.setRepeat(next).onSuccess { _repeatState.value = next } }
    }

    fun toggleSave() {
        val id = _currentTrack.value?.id ?: return
        viewModelScope.launch {
            if (_isSaved.value == true) {
                repo.removeTracks(listOf(id)).onSuccess { _isSaved.value = false }
            } else {
                repo.saveTracks(listOf(id)).onSuccess { _isSaved.value = true }
            }
        }
    }

    private fun checkSaved(id: String) {
        viewModelScope.launch {
            repo.checkSavedTracks(listOf(id)).onSuccess { _isSaved.value = it.firstOrNull() ?: false }
        }
    }

    override fun onCleared() { super.onCleared(); stopPolling() }
}
