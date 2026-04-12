package com.spp.spotify.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.spp.spotify.data.model.*
import com.spp.spotify.data.repository.SpotifyRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SpotifyRepository(application)

    private val _categories = MutableLiveData<List<Category>>()
    val categories: LiveData<List<Category>> = _categories

    private val _results = MutableLiveData<SearchResponse?>()
    val results: LiveData<SearchResponse?> = _results

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private var searchJob: Job? = null

    init { loadCategories() }

    private fun loadCategories() {
        viewModelScope.launch {
            repo.getCategories().onSuccess { _categories.value = it.categories.items }
        }
    }

    fun search(query: String) {
        if (query.isBlank()) { clearSearch(); return }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            _isLoading.value = true
            repo.search(query)
                .onSuccess { _results.value = it }
                .onFailure { _results.value = null }
            _isLoading.value = false
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _results.value = null
    }
}
