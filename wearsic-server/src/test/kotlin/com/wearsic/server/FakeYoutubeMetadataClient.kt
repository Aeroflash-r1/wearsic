package com.wearsic.server

/** Minimal in-memory [YoutubeMetadataClient] for orchestrator/integration tests. */
class FakeYoutubeMetadataClient : YoutubeMetadataClient {
    var results: List<TrackDto> = emptyList()
    var streamTargets: MutableMap<String, StreamTarget> = mutableMapOf()
    var lastQuery: String? = null
    val searchCalls: MutableList<String> = mutableListOf()
    val streamTargetCalls: MutableList<String> = mutableListOf()

    override suspend fun search(query: String): List<TrackDto> {
        lastQuery = query
        searchCalls.add(query)
        return results
    }

    override suspend fun searchGeneral(query: String, limit: Int): List<TrackDto> {
        lastQuery = query
        searchCalls.add(query)
        return results.take(limit)
    }

    override suspend fun streamTarget(videoId: String): StreamTarget? {
        streamTargetCalls.add(videoId)
        return streamTargets[videoId]
    }

    override suspend fun suggestions(prefix: String): List<String> = emptyList()

    override suspend fun related(videoId: String): List<TrackDto> = emptyList()

    override suspend fun searchAlbums(query: String): List<AlbumDto> = emptyList()

    override suspend fun playlistByUrl(url: String): PlaylistTracksResponse? = null
}
