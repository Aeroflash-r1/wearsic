package com.wearsic.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearsic.app.data.WearsicMusicRepository
import com.wearsic.app.data.WearsicPreferencesRepository
import com.wearsic.app.media.crossfade.CrossfadeLengths
import com.wearsic.app.network.model.ConnectionTestState
import com.wearsic.app.util.Validation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    /** Crossfade window between queue tracks, in ms; OFF keeps the plain hand-off. */
    val crossfadeMs: StateFlow<Long> = preferencesRepository.crossfadeMsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CrossfadeLengths.DEFAULT_MS)

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
    // YouTube Cookie (bot-wall bypass)
    // ============================================================================

    data class CookieUiState(
        val hasCookie: Boolean = false,
        val isWorking: Boolean = false,
        val errorMessage: String? = null,
        val justSaved: Boolean = false
    )

    private val _cookieState = MutableStateFlow(CookieUiState())
    val cookieState: StateFlow<CookieUiState> = _cookieState.asStateFlow()

    fun refreshCookieStatus() {
        viewModelScope.launch {
            musicRepository.getCookieStatus()
                .onSuccess { has ->
                    _cookieState.update { it.copy(hasCookie = has, errorMessage = null) }
                }
                .onFailure { err ->
                    _cookieState.update { it.copy(errorMessage = err.message ?: "Could not load cookie status") }
                }
        }
    }

    fun saveCookie(cookie: String) {
        val trimmed = cookie.trim()
        if (trimmed.isEmpty()) return
        if (_cookieState.value.isWorking) return
        _cookieState.update { it.copy(isWorking = true, errorMessage = null, justSaved = false) }
        viewModelScope.launch {
            musicRepository.setCookie(trimmed)
                .onSuccess { has ->
                    _cookieState.update { it.copy(hasCookie = has, isWorking = false, justSaved = true) }
                }
                .onFailure { err ->
                    _cookieState.update {
                        it.copy(isWorking = false, errorMessage = err.message ?: "Could not save cookie")
                    }
                }
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

    /**
     * The running playback service reads this preference straight from
     * DataStore, so saving is the whole job — no command has to reach the
     * media session for the next transition to pick it up.
     */
    fun setCrossfadeMs(ms: Long) {
        viewModelScope.launch {
            preferencesRepository.saveCrossfadeMs(ms)
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
            _startupHealth.value = com.wearsic.app.StartupDiagnostics.lastStartupSummary(application.applicationContext)
        }
    }
}

