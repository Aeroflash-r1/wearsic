package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.WearsicMusicRepository
import com.example.data.WearsicPreferencesRepository
import com.example.network.model.ConnectionTestState
import com.example.util.Validation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel responsible for application settings and configuration.
 * Handles server URL, API key, preferences, and connection testing.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository = WearsicPreferencesRepository(application.applicationContext)
    private val musicRepository = WearsicMusicRepository(application.applicationContext)

    val serverUrl: StateFlow<String> = preferencesRepository.serverUrlFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WearsicPreferencesRepository.DEFAULT_SERVER_URL)

    val apiKey: StateFlow<String> = preferencesRepository.apiKeyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val autoCacheEnabled: StateFlow<Boolean> = preferencesRepository.autoCacheEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val offlineLimit: StateFlow<Int> = preferencesRepository.offlineLimitFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WearsicPreferencesRepository.DEFAULT_OFFLINE_LIMIT)

    // ============================================================================
    // Connection Testing
    // ============================================================================

    private val _connectionTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val connectionTestState: StateFlow<ConnectionTestState> = _connectionTestState.asStateFlow()

    private var testJob: Job? = null

    fun testConnection(targetUrl: String) {
        if (_connectionTestState.value is ConnectionTestState.Testing) return
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _connectionTestState.value = ConnectionTestState.Testing
            val result = musicRepository.testServerConnection(targetUrl)
            _connectionTestState.value = result
        }
    }

    // ============================================================================
    // Server URL Management
    // ============================================================================

    fun saveServerUrl(url: String) {
        viewModelScope.launch {
            preferencesRepository.saveServerUrl(url)
            _connectionTestState.value = ConnectionTestState.Idle
        }
    }

    // ============================================================================
    // API Key Management
    // ============================================================================

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            musicRepository.saveApiKey(key)
        }
    }

    // ============================================================================
    // Auto-Cache Settings
    // ============================================================================

    fun setAutoCacheEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoCacheEnabled(enabled)
        }
    }

    fun saveOfflineLimit(limitSongs: Int) {
        val clamped = limitSongs.coerceIn(5, 200)
        viewModelScope.launch {
            preferencesRepository.saveOfflineLimit(clamped)
        }
    }

    // ============================================================================
    // Playlist Visibility
    // ============================================================================

    val hiddenPlaylists: StateFlow<Set<String>> = preferencesRepository.hiddenPlaylistsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun toggleHiddenPlaylist(id: String) {
        viewModelScope.launch {
            preferencesRepository.toggleHiddenPlaylist(id)
        }
    }

    // ============================================================================
    // Validation Helpers
    // ============================================================================

    fun validateServerUrl(url: String): Boolean {
        return Validation.hasValidScheme(url)
    }

    fun validateApiKey(key: String): Boolean {
        return Validation.validateApiKey(key).isSuccess
    }

    // ============================================================================
    // Startup Health
    // ============================================================================

    private val _startupHealth = MutableStateFlow("")
    val startupHealth: StateFlow<String> = _startupHealth.asStateFlow()

    init {
        viewModelScope.launch {
            _startupHealth.value = com.example.StartupDiagnostics.lastStartupSummary(application.applicationContext)
        }
    }
}

