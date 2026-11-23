package com.wearsic.app.model

/**
 * A local grouping of saved songs by artist, built from the
 * user's favorites and downloaded tracks. Used by the Artists
 * screen to let the wearer browse and play per-artist queues
 * without any network round-trip.
 */
data class ArtistGroup(
    val name: String,
    val songs: List<Track>
)
