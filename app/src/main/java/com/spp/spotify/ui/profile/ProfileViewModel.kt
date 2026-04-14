package com.spp.spotify.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.spp.spotify.auth.TokenManager
import com.spp.spotify.data.model.User
import com.spp.spotify.data.repository.SpotifyRepository
import kotlinx.coroutines.launch

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SpotifyRepository(application)

    private val _user = MutableLiveData<User?>()
    val user: LiveData<User?> = _user

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _logoutComplete = MutableLiveData(false)
    val logoutComplete: LiveData<Boolean> = _logoutComplete

    init { loadUser() }

    fun loadUser() {
        viewModelScope.launch {
            _isLoading.value = true
            repo.getCurrentUser().onSuccess { _user.value = it }
            _isLoading.value = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            TokenManager.clearTokens(getApplication())
            _logoutComplete.value = true
        }
    }
}
