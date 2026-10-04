package com.wearsic.app.network

import com.wearsic.app.model.HostUnreachableError
import com.wearsic.app.model.HttpError
import com.wearsic.app.model.InvalidUrlError
import com.wearsic.app.model.NetworkError
import com.wearsic.app.model.ServerError
import com.wearsic.app.model.TimeoutError
import com.wearsic.app.model.UnknownError
import com.wearsic.app.model.WearsicError
import com.wearsic.app.network.model.AlbumDto
import com.wearsic.app.network.model.FavoritesResponseDto
import com.wearsic.app.network.model.PlaylistDto
import com.wearsic.app.network.model.PlaylistWithTracksDto
import com.wearsic.app.network.model.SearchResponseDto
import com.wearsic.app.network.model.SearchResultsResponseDto
import com.wearsic.app.network.model.ServerHealthDto
import com.wearsic.app.network.model.SuggestionsResponseDto
import com.wearsic.app.network.model.TrackDto
import com.wearsic.app.util.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class WearsicHttpApiClient(
    // Derived from the shared pool; only the faster connect timeout differs.
    // Auth (X-Wearsic-Key) is injected centrally by WearsicHttp.
    private val client: OkHttpClient = WearsicHttp.client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()
) : WearsicApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override suspend fun checkHealth(baseUrl: String): Result<ServerHealthDto> = withContext(Dispatchers.IO) {
        val urlValidation = validateBaseUrl(baseUrl)
        if (urlValidation.isFailure) {
            val error = urlValidation.exceptionOrNull() as? WearsicError
            return@withContext Result.failure(error ?: InvalidUrlError(baseUrl))
        }
        val sanitizedUrl = urlValidation.getOrThrow()
        val healthUrl = "$sanitizedUrl/health"

        try {
            val request = Request.Builder()
                .url(healthUrl)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        HttpError(healthUrl, response.code, "Server returned HTTP ${response.code}")
                    )
                }

                val bodyString = response.body?.string() ?: ""
                val dto = try {
                    json.decodeFromString<ServerHealthDto>(bodyString)
                } catch (_: Exception) {
                    ServerHealthDto()
                }

                Result.success(dto)
            }
        } catch (e: UnknownHostException) {
            Result.failure(HostUnreachableError(e.message ?: "unknown"))
        } catch (e: SocketTimeoutException) {
            Result.failure(TimeoutError())
        } catch (e: Exception) {
            Result.failure(UnknownError(e.message ?: "Could not connect to server", e))
        }
    }

    override suspend fun searchTracks(baseUrl: String, query: String): Result<SearchResponseDto> = withContext(Dispatchers.IO) {
        val urlValidation = validateBaseUrl(baseUrl)
        if (urlValidation.isFailure) {
            val error = urlValidation.exceptionOrNull() as? WearsicError
            return@withContext Result.failure(error ?: InvalidUrlError(baseUrl))
        }
        val sanitizedUrl = urlValidation.getOrThrow()
        val queryValidation = Validation.validateQuery(query)
        if (queryValidation.isFailure) {
            val error = queryValidation.exceptionOrNull() as? WearsicError
            return@withContext Result.failure(error ?: com.wearsic.app.model.EmptyInputError("query"))
        }
        val encodedQuery = URLEncoder.encode(queryValidation.getOrThrow(), "UTF-8")
        val searchUrl = "$sanitizedUrl/api/search?q=$encodedQuery"

        try {
            val request = Request.Builder()
                .url(searchUrl)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        HttpError(searchUrl, response.code, "Server error: HTTP ${response.code}")
                    )
                }

                val bodyString = response.body?.string() ?: ""
                val dto = try {
                    json.decodeFromString<SearchResultsResponseDto>(bodyString)
                } catch (_: Exception) {
                    SearchResultsResponseDto()
                }

                Result.success(
                    SearchResponseDto(
                        query = query,
                        tracks = dto.results.withStreamUrls(sanitizedUrl)
                    )
                )
            }
        } catch (e: UnknownHostException) {
            Result.failure(HostUnreachableError(e.message ?: "unknown"))
        } catch (e: SocketTimeoutException) {
            Result.failure(TimeoutError())
        } catch (e: Exception) {
            Result.failure(UnknownError(e.message ?: "Search failed", e))
        }
    }

    override suspend fun getFavorites(baseUrl: String): Result<List<TrackDto>> = withContext(Dispatchers.IO) {
        val urlValidation = validateBaseUrl(baseUrl)
        if (urlValidation.isFailure) {
            val error = urlValidation.exceptionOrNull() as? WearsicError
            return@withContext Result.failure(error ?: InvalidUrlError(baseUrl))
        }
        val sanitizedUrl = urlValidation.getOrThrow()

        try {
            val request = Request.Builder()
                .url("$sanitizedUrl/api/favorites")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        HttpError("$sanitizedUrl/api/favorites", response.code, "Server error: HTTP ${response.code}")
                    )
                }
                val bodyString = response.body?.string() ?: "[]"
                val dto = try {
                    json.decodeFromString<List<TrackDto>>(bodyString)
                } catch (_: Exception) {
                    try {
                        json.decodeFromString<FavoritesResponseDto>(bodyString).favorites
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                Result.success(dto.withStreamUrls(sanitizedUrl))
            }
        } catch (e: Exception) {
            Result.failure(UnknownError(e.message ?: "Could not load favorites", e))
        }
    }

    override suspend fun addFavorite(baseUrl: String, track: TrackDto): Result<Unit> = withContext(Dispatchers.IO) {
        val urlValidation = validateBaseUrl(baseUrl)
        if (urlValidation.isFailure) {
            val error = urlValidation.exceptionOrNull() as? WearsicError
            return@withContext Result.failure(error ?: InvalidUrlError(baseUrl))
        }
        val sanitizedUrl = urlValidation.getOrThrow()

        try {
            val request = Request.Builder()
                .url("$sanitizedUrl/api/favorites")
                .post(track.toRequestBodyJson().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        HttpError("$sanitizedUrl/api/favorites", response.code, "Server error: HTTP ${response.code}")
                    )
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(UnknownError(e.message ?: "Could not add favorite", e))
        }
    }

    override suspend fun removeFavorite(baseUrl: String, videoId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val urlValidation = validateBaseUrl(baseUrl)
        if (urlValidation.isFailure) {
            val error = urlValidation.exceptionOrNull() as? WearsicError
            return@withContext Result.failure(error ?: InvalidUrlError(baseUrl))
        }
        val sanitizedUrl = urlValidation.getOrThrow()
        val trackIdValidation = Validation.validateTrackId(videoId)
        if (trackIdValidation.isFailure) {
            val error = trackIdValidation.exceptionOrNull() as? WearsicError
            return@withContext Result.failure(error ?: com.wearsic.app.model.InvalidTrackIdError(videoId))
        }

        try {
            val request = Request.Builder()
                .url("$sanitizedUrl/api/favorites/${URLEncoder.encode(trackIdValidation.getOrThrow(), "UTF-8")}")
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        HttpError("$sanitizedUrl/api/favorites", response.code, "Server error: HTTP ${response.code}")
                    )
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(UnknownError(e.message ?: "Could not remove favorite", e))
        }
    }

    override suspend fun getPlaylists(baseUrl: String): Result<List<PlaylistDto>> = withContext(Dispatchers.IO) {
        val urlValidation = validateBaseUrl(baseUrl)
        if (urlValidation.isFailure) {
            val error = urlValidation.exceptionOrNull() as? WearsicError
            return@withContext Result.failure(error ?: InvalidUrlError(baseUrl))
        }
        val sanitizedUrl = urlValidation.getOrThrow()

        try {
            val request = Request.Builder()
                .url("$sanitizedUrl/api/playlists")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        HttpError("$sanitizedUrl/api/playlists", response.code, "Server error: HTTP ${response.code}")
                    )
                }
                val bodyString = response.body?.string() ?: "[]"
                val playlists = try {
                    json.decodeFromString<List<PlaylistDto>>(bodyString)
                } catch (_: Exception) {
                    emptyList()
                }
                Result.success(playlists)
            }
        } catch (e: Exception) {
            Result.failure(UnknownError(e.message ?: "Could not load playlists", e))
        }
    }

    override suspend fun getPlaylistTracks(baseUrl: String, playlistId: String): Result<PlaylistWithTracksDto> = withContext(Dispatchers.IO) {
        val urlValidation = validateBaseUrl(baseUrl)
        if (urlValidation.isFailure) {
            val error = urlValidation.exceptionOrNull() as? WearsicError
            return@withContext Result.failure(error ?: InvalidUrlError(baseUrl))
        }
        val sanitizedUrl = urlValidation.getOrThrow()

        try {
            val request = Request.Builder()
                .url("$sanitizedUrl/api/playlists/${URLEncoder.encode(playlistId, "UTF-8")}")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        HttpError("$sanitizedUrl/api/playlists", response.code, "Server error: HTTP ${response.code}")
                    )
                }
                val bodyString = response.body?.string() ?: "{}"
                val dto = try {
                    json.decodeFromString<PlaylistWithTracksDto>(bodyString)
                } catch (_: Exception) {
                    PlaylistWithTracksDto(id = playlistId, name = "Playlist")
                }
                Result.success(dto.copy(tracks = dto.tracks.withStreamUrls(sanitizedUrl)))
            }
        } catch (e: Exception) {
            Result.failure(UnknownError(e.message ?: "Could not load playlist", e))
        }
    }

    override suspend fun removeTrackFromPlaylist(baseUrl: String, playlistId: String, videoId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val urlValidation = validateBaseUrl(baseUrl)
        if (urlValidation.isFailure) {
            val error = urlValidation.exceptionOrNull() as? WearsicError
            return@withContext Result.failure(error ?: InvalidUrlError(baseUrl))
        }
        val sanitizedUrl = urlValidation.getOrThrow()
        val trackIdValidation = Validation.validateTrackId(videoId)
        if (trackIdValidation.isFailure) {
            val error = trackIdValidation.exceptionOrNull() as? WearsicError
            return@withContext Result.failure(error ?: com.wearsic.app.model.InvalidTrackIdError(videoId))
        }

        try {
            val request = Request.Builder()
                .url("$sanitizedUrl/api/playlists/${URLEncoder.encode(playlistId, "UTF-8")}/tracks/${URLEncoder.encode(trackIdValidation.getOrThrow(), "UTF-8")}")
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        HttpError("$sanitizedUrl/api/playlists", response.code, "Server error: HTTP ${response.code}")
                    )
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(UnknownError(e.message ?: "Could not remove track", e))
        }
    }


    override suspend fun getSuggestions(baseUrl: String, query: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val urlValidation = validateBaseUrl(baseUrl)
            if (urlValidation.isFailure) {
                val error = urlValidation.exceptionOrNull() as? WearsicError
                return@withContext Result.failure(error ?: InvalidUrlError(baseUrl))
            }
            val sanitizedUrl = urlValidation.getOrThrow()
            val queryValidation = Validation.validateQuery(query)
            if (queryValidation.isFailure) {
                val error = queryValidation.exceptionOrNull() as? WearsicError
                return@withContext Result.failure(error ?: com.wearsic.app.model.EmptyInputError("query"))
            }
            try {
                val request = Request.Builder()
                    .url("$sanitizedUrl/api/suggestions?q=${URLEncoder.encode(queryValidation.getOrThrow(), "UTF-8")}")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use Result.failure(
                            HttpError("$sanitizedUrl/api/suggestions", response.code, "Server error: HTTP ${response.code}")
                        )
                    }
                    val bodyString = response.body?.string() ?: "{}"
                    val dto = try {
                        json.decodeFromString<SuggestionsResponseDto>(bodyString)
                    } catch (_: Exception) {
                        SuggestionsResponseDto()
                    }
                    Result.success(dto.suggestions)
                }
            } catch (e: Exception) {
                Result.failure(UnknownError(e.message ?: "Could not load suggestions", e))
            }
        }

    override suspend fun getRelated(baseUrl: String, videoId: String): Result<List<TrackDto>> =
        withContext(Dispatchers.IO) {
            val urlValidation = validateBaseUrl(baseUrl)
            if (urlValidation.isFailure) {
                val error = urlValidation.exceptionOrNull() as? WearsicError
                return@withContext Result.failure(error ?: InvalidUrlError(baseUrl))
            }
            val sanitizedUrl = urlValidation.getOrThrow()
            val trackIdValidation = Validation.validateTrackId(videoId)
            if (trackIdValidation.isFailure) {
                val error = trackIdValidation.exceptionOrNull() as? WearsicError
                return@withContext Result.failure(error ?: com.wearsic.app.model.InvalidTrackIdError(videoId))
            }
            try {
                val request = Request.Builder()
                    .url("$sanitizedUrl/api/related/${URLEncoder.encode(trackIdValidation.getOrThrow(), "UTF-8")}")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use Result.failure(
                            HttpError("$sanitizedUrl/api/related", response.code, "Server error: HTTP ${response.code}")
                        )
                    }
                    val bodyString = response.body?.string() ?: "{}"
                    val dto = try {
                        json.decodeFromString<com.wearsic.app.network.model.RelatedResponseDto>(bodyString)
                    } catch (_: Exception) {
                        com.wearsic.app.network.model.RelatedResponseDto()
                    }
                    Result.success(dto.results.withStreamUrls(sanitizedUrl))
                }
            } catch (e: Exception) {
                Result.failure(UnknownError(e.message ?: "Could not load related songs", e))
            }
        }

    override suspend fun searchAlbums(baseUrl: String, query: String): Result<List<AlbumDto>> =
        withContext(Dispatchers.IO) {
            val urlValidation = validateBaseUrl(baseUrl)
            if (urlValidation.isFailure) {
                val error = urlValidation.exceptionOrNull() as? WearsicError
                return@withContext Result.failure(error ?: InvalidUrlError(baseUrl))
            }
            val sanitizedUrl = urlValidation.getOrThrow()
            val queryValidation = Validation.validateQuery(query)
            if (queryValidation.isFailure) {
                val error = queryValidation.exceptionOrNull() as? WearsicError
                return@withContext Result.failure(error ?: com.wearsic.app.model.EmptyInputError("query"))
            }
            try {
                val request = Request.Builder()
                    .url("$sanitizedUrl/api/search/albums?q=${URLEncoder.encode(queryValidation.getOrThrow(), "UTF-8")}")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use Result.failure(
                            HttpError("$sanitizedUrl/api/search/albums", response.code, "Server error: HTTP ${response.code}")
                        )
                    }
                    val bodyString = response.body?.string() ?: "[]"
                    val albums = try {
                        json.decodeFromString<List<AlbumDto>>(bodyString)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    Result.success(albums)
                }
            } catch (e: Exception) {
                Result.failure(UnknownError(e.message ?: "Could not load albums", e))
            }
        }

    override suspend fun getPlaylistByUrl(baseUrl: String, url: String): Result<PlaylistWithTracksDto> =
        withContext(Dispatchers.IO) {
            val urlValidation = validateBaseUrl(baseUrl)
            if (urlValidation.isFailure) {
                val error = urlValidation.exceptionOrNull() as? WearsicError
                return@withContext Result.failure(error ?: InvalidUrlError(baseUrl))
            }
            val sanitizedUrl = urlValidation.getOrThrow()
            try {
                val request = Request.Builder()
                    .url("$sanitizedUrl/api/playlist?url=${URLEncoder.encode(url, "UTF-8")}")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use Result.failure(
                            HttpError("$sanitizedUrl/api/playlist", response.code, "Server error: HTTP ${response.code}")
                        )
                    }
                    val bodyString = response.body?.string() ?: "{}"
                    val dto = try {
                        json.decodeFromString<PlaylistWithTracksDto>(bodyString)
                    } catch (_: Exception) {
                        PlaylistWithTracksDto(id = url, name = "Album")
                    }
                    Result.success(dto.copy(tracks = dto.tracks.withStreamUrls(sanitizedUrl)))
                }
            } catch (e: Exception) {
                Result.failure(UnknownError(e.message ?: "Could not load album", e))
            }
        }

    override suspend fun createPlaylist(baseUrl: String, name: String): Result<PlaylistDto> =
        withContext(Dispatchers.IO) {
            val urlValidation = validateBaseUrl(baseUrl)
            if (urlValidation.isFailure) {
                val error = urlValidation.exceptionOrNull() as? WearsicError
                return@withContext Result.failure(error ?: InvalidUrlError(baseUrl))
            }
            val sanitizedUrl = urlValidation.getOrThrow()
            val nameValidation = Validation.validatePlaylistName(name)
            if (nameValidation.isFailure) {
                val error = nameValidation.exceptionOrNull() as? WearsicError
                return@withContext Result.failure(error ?: com.wearsic.app.model.EmptyInputError("playlist name"))
            }
            try {
                val body = kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.serializer<PlaylistDto>(),
                    PlaylistDto(id = "", name = nameValidation.getOrThrow())
                ).toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder()
                    .url("$sanitizedUrl/api/playlists")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use Result.failure(
                            HttpError("$sanitizedUrl/api/playlists", response.code, "Server error: HTTP ${response.code}")
                        )
                    }
                    val bodyString = response.body?.string() ?: "{}"
                    val dto = try {
                        json.decodeFromString<PlaylistDto>(bodyString)
                    } catch (_: Exception) {
                        PlaylistDto(id = "", name = nameValidation.getOrThrow())
                    }
                    Result.success(dto)
                }
            } catch (e: Exception) {
                Result.failure(UnknownError(e.message ?: "Could not create playlist", e))
            }
        }

    override suspend fun addTrackToPlaylist(baseUrl: String, playlistId: String, track: TrackDto): Result<Unit> =
        withContext(Dispatchers.IO) {
            val urlValidation = validateBaseUrl(baseUrl)
            if (urlValidation.isFailure) {
                val error = urlValidation.exceptionOrNull() as? WearsicError
                return@withContext Result.failure(error ?: InvalidUrlError(baseUrl))
            }
            val sanitizedUrl = urlValidation.getOrThrow()
            try {
                val request = Request.Builder()
                    .url("$sanitizedUrl/api/playlists/${URLEncoder.encode(playlistId, "UTF-8")}/tracks")
                    .post(track.toRequestBodyJson().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use Result.failure(
                            HttpError("$sanitizedUrl/api/playlists", response.code, "Server error: HTTP ${response.code}")
                        )
                    }
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(UnknownError(e.message ?: "Could not add to playlist", e))
            }
        }

    /**
     * Validates a base URL and normalizes it (trims trailing slashes).
     *
     * A URL whose scheme is not http/https fails with a clear
     * "Invalid URL scheme" message so callers and the connection-test UI can
     * report the real reason instead of a generic invalid-URL error.
     */
    private fun validateBaseUrl(baseUrl: String): Result<String> {
        val result = Validation.validateServerUrl(baseUrl)
        return if (result.exceptionOrNull() is InvalidUrlError) {
            Result.failure(InvalidUrlError("Invalid URL scheme: $baseUrl"))
        } else {
            result
        }
    }

    /**
     * Servers never send a streamUrl — it's derived from the base URL and the
     * track's videoId (this is how the pre-serialization client built it).
     * Without it, played tracks have an empty media URI and can't start.
     */
    private fun List<TrackDto>.withStreamUrls(baseUrl: String): List<TrackDto> =
        map { it.copy(streamUrl = "$baseUrl/api/stream/${it.id}") }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
