package com.wearsic.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [WearsicDownloadEntity::class, WearsicRecentTrackEntity::class], version = 4, exportSchema = true)
abstract class WearsicDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao

    abstract fun recentTrackDao(): RecentTrackDao

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

        fun getInstance(context: Context): WearsicDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WearsicDatabase::class.java,
                    "wearsic_database.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { INSTANCE = it }
            }
        }
    }
}