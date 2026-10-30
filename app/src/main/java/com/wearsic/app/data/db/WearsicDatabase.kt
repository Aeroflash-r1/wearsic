package com.wearsic.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WearsicDownloadEntity::class,
        WearsicRecentTrackEntity::class,
        WearsicFavoriteTrackEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class WearsicDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao

    abstract fun recentTrackDao(): RecentTrackDao

    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile
        private var INSTANCE: WearsicDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS recent_tracks (" +
                        "trackId TEXT NOT NULL PRIMARY KEY, " +
                        "title TEXT NOT NULL, " +
                        "artist TEXT NOT NULL, " +
                        "album TEXT, " +
                        "artworkUrl TEXT, " +
                        "durationMs INTEGER NOT NULL, " +
                        "mediaUri TEXT NOT NULL, " +
                        "playedAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN autoCached INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Persisted deferred-deletion intent for COMPLETED AUTO rows
                // whose file was in use when deletion was requested.
                db.execSQL("ALTER TABLE downloads ADD COLUMN pendingDeletion INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Local-first favorites (1.9.39): favorites live on the watch. The
        // table is created empty; existing server favorites are merged in the
        // first time the app connects (see WearsicFavoriteRepository).
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS favorite_tracks (" +
                        "trackId TEXT NOT NULL PRIMARY KEY, " +
                        "title TEXT NOT NULL, " +
                        "artist TEXT NOT NULL, " +
                        "album TEXT, " +
                        "artworkUrl TEXT, " +
                        "durationMs INTEGER NOT NULL, " +
                        "mediaUri TEXT NOT NULL, " +
                        "addedAt INTEGER NOT NULL)"
                )
            }
        }

        fun getInstance(context: Context): WearsicDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WearsicDatabase::class.java,
                    "wearsic_database.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                    )
                    // Safety net: a future schema change that ships without a
                    // migration (or a DB created by a package-rename build)
                    // must not crash on open — Room rebuilds empty and the
                    // app re-downloads/re-creates content.
                    .fallbackToDestructiveMigration(true)
                    .build().also { INSTANCE = it }
            }
        }
    }
}