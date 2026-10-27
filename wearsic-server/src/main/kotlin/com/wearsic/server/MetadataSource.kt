package com.wearsic.server

/**
 * Abstraction over the music-metadata source used by /api/search. Exists so
 * the search orchestration can be unit-tested without network access.
 *
 * The production source is YouTube Music (InnerTube), which returns real
 * YouTube videoIds directly - no surrogate ids, no second matching step.
 */
interface MetadataSource {
    /** Search YouTube Music for songs matching [query]. Empty when nothing usable. */
    suspend fun searchSongs(query: String, limit: Int = 10): List<YtmTrack>

    /**
     * Recover one track's metadata by videoId. YTM ids are already playable
     * so the production source has nothing to recover (default null); kept
     * for interface symmetry and fakes.
     */
    suspend fun lookupTrack(videoId: String): YtmTrack? = null

    /** Convert a YTM track to the wire DTO (videoId is the real YouTube id). */
    fun toTrackDto(track: YtmTrack): TrackDto
}

/**
 * Abstraction over the YouTube extraction layer (NewPipeExtractor). Exists so
 * AudioProxy, the routes and tests can work against canned
 * results without any network access.
 */
interface YoutubeMetadataClient {
    /** YouTube search with the same dedup/caching semantics as production. */
    suspend fun search(query: String): List<TrackDto>

    /**
     * Same search with a page-size wish ( Show more ). Default keeps every
     * existing caller on first-page behavior.
     */
    suspend fun search(query: String, limit: Int): List<TrackDto> = search(query)

    /** Resolve a playable stream target for [videoId] (URL + MIME type). */
    suspend fun streamTarget(videoId: String): StreamTarget?

    /**
     * Resolve full metadata for a known video id — used when the user pastes
     * a YouTube URL / video id into search. Default null means "not
     * supported" and callers fall back to normal search.
     */
    suspend fun trackByVideoId(videoId: String): TrackDto? = null

    /** Autocomplete suggestions for a prefix. */
    suspend fun suggestions(prefix: String): List<String>

    /** Songs related to [videoId] (radio), filtered to <=10-minute tracks. */
    suspend fun related(videoId: String): List<TrackDto>

    /** Album search (returns playlist URLs as ids, per the API contract). */
    suspend fun searchAlbums(query: String): List<AlbumDto>

    /** Tracks of a playlist/album URL (max 10, per the API contract). */
    suspend fun playlistByUrl(url: String): PlaylistTracksResponse?
}
