package com.wearsic.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wearsic.app.media.crossfade.CrossfadeLengths
import com.wearsic.app.util.Validation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.catch
import java.io.IOException
import java.net.URI

// Forensics only (2026-10-18 CI wedge): the scope is a pass-through tap that
// logs every dispatch of the DataStore's internal coroutines; revert to the
// plain default delegate (no scope argument) once the wedge is fixed.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "wearsic_settings",
    scope = tappedDataStoreScope(),
)

class WearsicPreferencesRepository(private val context: Context) {

    companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_AUTO_CACHE_ENABLED = booleanPreferencesKey("auto_cache_enabled")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_HIDDEN_PLAYLISTS = stringSetPreferencesKey("hidden_playlists")
        val KEY_OFFLINE_LIMIT = intPreferencesKey("offline_song_limit")
        /**
         * Crossfade window between queue tracks, in milliseconds. Defaults to
         * [CrossfadeLengths.OFF_MS] (the feature off) so installing an update
         * never changes how playback sounds or what it costs on battery.
         */
        val KEY_CROSSFADE_MS = intPreferencesKey("crossfade_ms")
        /** No server is preconfigured; users enter their own URL in Settings. */
        const val DEFAULT_SERVER_URL = ""
        const val DEFAULT_OFFLINE_LIMIT = 15
    }

    val serverUrlFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            emit(emptyPreferences())
        }
        .map { preferences ->
            preferences[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL
        }

    /**
     * Persists the server URL.
     *
     * Blank input CLEARS the setting (the user emptied the field on purpose —
     * that is how the app returns to "no server configured"). Anything else
     * must validate: an invalid value is dropped instead of written.
     *
     * Why the strictness: the previous code stored `""` for any URL that did
     * not validate. Combined with saving on every keystroke that meant typing
     * `http://192.168...` went through invalid intermediate states, each one
     * WIPING the stored server URL — silently breaking playback mid-edit and
     * (via the settings screen's two-way binding) erasing characters under
     * the user's fingers.
     */
    suspend fun saveServerUrl(url: String) {
        if (url.isBlank()) {
            context.dataStore.edit { preferences ->
                preferences[KEY_SERVER_URL] = ""
            }
            return
        }
        val cleanUrl = Validation.validateServerUrl(url).getOrNull() ?: return
        println("WEDGE-PROBE [${Thread.currentThread().name}] saveServerUrl edit() entered")
        System.out.flush()
        context.dataStore.edit { preferences ->
            // CI forensics (2026-10-18 wedge; remove after root cause is fixed):
            // marks whether the DataStore actor reached the transform it runs
            // via withContext(callerContext) inside transformAndWrite.
            println("WEDGE-PROBE [${Thread.currentThread().name}] transform executing saveServerUrl")
            System.out.flush()
            preferences[KEY_SERVER_URL] = cleanUrl.trimEnd('/')
            println("WEDGE-PROBE [${Thread.currentThread().name}] transform completed saveServerUrl")
            System.out.flush()
        }
        println("WEDGE-PROBE [${Thread.currentThread().name}] saveServerUrl edit() returned")
        System.out.flush()
    }

    val autoCacheEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            emit(emptyPreferences())
        }
        .map { preferences ->
            preferences[KEY_AUTO_CACHE_ENABLED] ?: true
        }

    suspend fun setAutoCacheEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_CACHE_ENABLED] = enabled
        }
    }

    val offlineLimitFlow: Flow<Int> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            preferences[KEY_OFFLINE_LIMIT] ?: DEFAULT_OFFLINE_LIMIT
        }

    suspend fun saveOfflineLimit(limitSongs: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_OFFLINE_LIMIT] = limitSongs.coerceIn(5, 200)
        }
    }

    /**
     * Reads are sanitised on the way out, not on the way in: a value this build
     * does not offer (written by another version, or a corrupted entry) must
     * never reach the player as an arbitrary fade window.
     */
    val crossfadeMsFlow: Flow<Long> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            CrossfadeLengths.sanitize(
                (preferences[KEY_CROSSFADE_MS] ?: CrossfadeLengths.DEFAULT_MS.toInt()).toLong()
            )
        }

    suspend fun saveCrossfadeMs(ms: Long) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CROSSFADE_MS] = CrossfadeLengths.sanitize(ms).toInt()
        }
    }

    val apiKeyFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            emit(emptyPreferences())
        }
        .map { preferences ->
            preferences[KEY_API_KEY] ?: ""
        }

    suspend fun getApiKey(): String {
        return context.dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { preferences -> preferences[KEY_API_KEY] ?: "" }
            .first()
    }

    /**
     * Persists the API key and returns the value that was actually applied
     * (`""` when cleared, `null` when the input was rejected).
     *
     * Blank clears the key. A non-blank key must pass [Validation.validateApiKey];
     * an invalid key is dropped rather than stored, so a half-typed key can
     * never replace a working one. Callers that mirror the key into the HTTP
     * layer must use the returned value — not their input — or they would
     * push a rejected key into every outgoing request.
     */
    suspend fun saveApiKey(key: String): String? {
        if (key.isBlank()) {
            context.dataStore.edit { preferences ->
                preferences[KEY_API_KEY] = ""
            }
            return ""
        }
        val cleanKey = Validation.validateApiKey(key).getOrNull() ?: return null
        context.dataStore.edit { preferences ->
            preferences[KEY_API_KEY] = cleanKey
        }
        return cleanKey
    }

    fun isValidServerUrl(url: String): Boolean {
        return Validation.hasValidScheme(url)
    }

    val hiddenPlaylistsFlow: Flow<Set<String>> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            preferences[KEY_HIDDEN_PLAYLISTS] ?: emptySet()
        }

    suspend fun toggleHiddenPlaylist(id: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[KEY_HIDDEN_PLAYLISTS] ?: emptySet()
            preferences[KEY_HIDDEN_PLAYLISTS] =
                if (id in current) current - id else current + id
        }
    }
}
