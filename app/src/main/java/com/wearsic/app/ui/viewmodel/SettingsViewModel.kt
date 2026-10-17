package com.wearsic.app.ui.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearsic.app.data.WearsicMusicRepository
import com.wearsic.app.data.WearsicPreferencesRepository
import com.wearsic.app.media.crossfade.CrossfadeLengths
import com.wearsic.app.model.WearsicError
import com.wearsic.app.network.model.ConnectionTestState
import com.wearsic.app.network.model.ReadyResponseDto
import com.wearsic.app.network.model.ServerHealthDto
import com.wearsic.app.util.Validation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One probe of the server's live state, rendered by the Engine Dashboard:
 * `/health` (status, engine version, extraction health, update staging) and
 * `/ready` (can it serve music right now).
 */
data class ServerDashboardState(
    val loading: Boolean = false,
    val health: ServerHealthDto? = null,
    val ready: ReadyResponseDto? = null,
    val error: String? = null,
    val latencyMs: Long? = null,
    val checkedAtMillis: Long = 0L
)

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
        testJob?.cancel()
        // Set synchronously: the UI reacts on the tap itself, and a URL
        // commit landing while the test is in flight (see saveServerUrl)
        // deliberately leaves this state alone.
        _connectionTestState.value = ConnectionTestState.Testing
        testJob = viewModelScope.launch {
            val result = musicRepository.testServerConnection(targetUrl)
            // A newer tap replaced this job — never publish a stale result.
            if (isActive) _connectionTestState.value = result
        }
    }

    // ============================================================================
    // Server URL Management
    // ============================================================================

    fun saveServerUrl(url: String) {
        viewModelScope.launch {
            preferencesRepository.saveServerUrl(url)
            // A changed URL invalidates a PREVIOUS test result — but never
            // stomp a test that is running right now: the user may have
            // pressed "Test Connection" at the same moment the field
            // committed, and the result is still on its way.
            if (_connectionTestState.value !is ConnectionTestState.Testing) {
                _connectionTestState.value = ConnectionTestState.Idle
            }
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
    // Engine Dashboard (live /health + /ready)
    // ============================================================================

    private val _serverDashboard = MutableStateFlow(ServerDashboardState())
    val serverDashboard: StateFlow<ServerDashboardState> = _serverDashboard.asStateFlow()

    private var dashboardJob: Job? = null

    /**
     * Probes /health, then /ready, and publishes one snapshot. Cancels any
     * in-flight probe so a rapid tap on Refresh cannot interleave two results.
     */
    fun refreshServerDashboard() {
        dashboardJob?.cancel()
        dashboardJob = viewModelScope.launch {
            _serverDashboard.value = _serverDashboard.value.copy(loading = true, error = null)
            val url = musicRepository.getServerUrl()
            if (Validation.validateServerUrl(url).isFailure) {
                _serverDashboard.value = ServerDashboardState(
                    error = "Server URL not set — add it in Settings first"
                )
                return@launch
            }

            val startedAt = SystemClock.elapsedRealtime()
            val healthResult = musicRepository.probeServerHealth(url)
            val latency = SystemClock.elapsedRealtime() - startedAt

            healthResult.fold(
                onSuccess = { health ->
                    // /ready is best-effort: a server that predates the
                    // endpoint (or hiccups on it) still gets a useful card.
                    val ready = musicRepository.probeServerReadiness(url).getOrNull()
                    _serverDashboard.value = ServerDashboardState(
                        loading = false,
                        health = health,
                        ready = ready,
                        latencyMs = latency,
                        checkedAtMillis = System.currentTimeMillis()
                    )
                },
                onFailure = { error ->
                    val message = (error as? WearsicError)?.getDisplayMessage()
                        ?: error.message ?: "Could not reach server"
                    _serverDashboard.value = ServerDashboardState(
                        loading = false,
                        error = message,
                        latencyMs = latency,
                        checkedAtMillis = System.currentTimeMillis()
                    )
                }
            )
        }
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

