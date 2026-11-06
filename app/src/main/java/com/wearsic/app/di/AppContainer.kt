package com.wearsic.app.di

import android.content.Context
import com.wearsic.app.data.ServerFailoverCoordinator
import com.wearsic.app.data.WearsicDownloadRepository
import com.wearsic.app.data.WearsicFavoriteRepository
import com.wearsic.app.data.WearsicMusicRepository
import com.wearsic.app.data.WearsicPreferencesRepository
import com.wearsic.app.data.WearsicRecentRepository
import com.wearsic.app.data.lyrics.LyricsStore
import com.wearsic.app.network.WearsicApiClient
import com.wearsic.app.network.WearsicHttpApiClient
import kotlinx.coroutines.flow.first

class AppContainer(private val context: Context) {

    /**
     * The single preferences instance for the whole app. DataStore is
     * process-singleton per file, but sharing one repository (instead of each
     * consumer constructing its own) keeps one source of truth and one set of
     * flow subscriptions.
     */
    val preferencesRepository: WearsicPreferencesRepository by lazy { WearsicPreferencesRepository(context) }

    private val apiClient: WearsicApiClient by lazy { WearsicHttpApiClient() }

    val musicRepository by lazy { WearsicMusicRepository(context, preferencesRepository, apiClient) }
    val downloadRepository by lazy { WearsicDownloadRepository(context) }
    val recentRepository by lazy { WearsicRecentRepository(context) }
    // Local-first favorites: Room-backed on the watch, with a best-effort
    // server backup push. Shared process-wide like the other repositories.
    val favoriteRepository by lazy { WearsicFavoriteRepository(context, musicRepository) }
    // Offline lyrics cache (Room `lyrics_cache`): filled per the "Lyrics
    // Downloads" setting, read before every network fetch so downloaded
    // songs show lyrics with no server in reach.
    val lyricsStore by lazy { LyricsStore(context) }

    /**
     * Automatic server-profile failover: when the active server is confirmed
     * unreachable, every other saved profile is probed and the first healthy
     * one is activated — the same switch Settings performs by hand.
     *
     * The coordinator holds no scope of its own: LibraryViewModel launches
     * each round on its viewModelScope (which spans screens), and the
     * DataStore/HTTP work inside the lambdas already lands on IO dispatchers.
     */
    val serverFailover: ServerFailoverCoordinator by lazy {
        ServerFailoverCoordinator(
            profilesProvider = { preferencesRepository.serverProfilesFlow.first() },
            currentUrlProvider = { musicRepository.serverUrlFlow.first() },
            // /health is public and secret-free: the probe needs no key, so
            // even an unkeyed profile can be recognized as reachable.
            probe = { url -> musicRepository.probeServerHealth(url).isSuccess },
            // Mirror SettingsViewModel.applyProfile exactly: save only what
            // changed, and push the key through the musicRepository so the
            // shared OkHttp client picks it up in the same validated way.
            activate = { profile ->
                val currentUrl = preferencesRepository.serverUrlFlow.first()
                if (profile.url != currentUrl) {
                    preferencesRepository.saveServerUrl(profile.url)
                }
                val currentKey = preferencesRepository.apiKeyFlow.first()
                if (profile.apiKey != currentKey) {
                    musicRepository.saveApiKey(profile.apiKey)
                }
            },
        )
    }
}
