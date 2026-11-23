package com.wearsic.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LyricsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WearsicLyricsEntity)

    @Query("SELECT * FROM lyrics_cache WHERE trackId = :trackId LIMIT 1")
    suspend fun getByTrackId(trackId: String): WearsicLyricsEntity?

    @Query("SELECT COUNT(*) FROM lyrics_cache")
    suspend fun count(): Int

    /**
     * Bounded cache: evicts the OLDEST saved rows whenever a save pushes the
     * row count past the cap. Lyrics are small text, 250 songs is a rounding
     * error on watch storage — the cap only exists so the cache can never
     * grow unboundedly.
     */
    @Query(
        "DELETE FROM lyrics_cache WHERE trackId IN " +
            "(SELECT trackId FROM lyrics_cache ORDER BY fetchedAt ASC LIMIT :count)"
    )
    suspend fun deleteOldest(count: Int)

    @Query("DELETE FROM lyrics_cache WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: String)

    @Query("DELETE FROM lyrics_cache")
    suspend fun clearAll()
}
