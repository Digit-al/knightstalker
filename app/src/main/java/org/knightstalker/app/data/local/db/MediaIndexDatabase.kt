// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.knightstalker.app.data.local.entity.MovieFtsEntity
import org.knightstalker.app.data.local.entity.MovieIndexEntity
import org.knightstalker.app.data.local.entity.SeriesFtsEntity
import org.knightstalker.app.data.local.entity.SeriesIndexEntity
import org.knightstalker.app.data.local.entity.SyncStateEntity

/**
 * Adds series rating enrichment: a `ratingCheckedAt` guard column, and a one-time clear
 * of the existing series ratings (they were a bogus portal ★10.0). Cleared ratings are
 * re-filled from TVmaze on demand and from the fixed portal mapping on the next re-index.
 */
val MEDIA_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE series_index ADD COLUMN ratingCheckedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE series_index SET rating = NULL")
    }
}

/** Add the `ext` column (container extension) for building correct Xtream playback URLs. */
val MEDIA_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE movie_index ADD COLUMN ext TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE series_index ADD COLUMN ext TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * Track the crawl format generation per kind. When the code's [crawlFormatVersion]
 * (see SyncEngine.CRAWL_FORMAT_VERSION) is higher than the stored value, one full
 * re-crawl is forced so newly mapped fields (TMDB id, year, poster) get populated.
 */
val MEDIA_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_sync_state ADD COLUMN crawlFormatVersion INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [
        MovieIndexEntity::class,
        SeriesIndexEntity::class,
        MovieFtsEntity::class,
        SeriesFtsEntity::class,
        SyncStateEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class MediaIndexDatabase : RoomDatabase() {
    abstract fun movieIndexDao(): MovieIndexDao
    abstract fun seriesIndexDao(): SeriesIndexDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        fun create(context: Context): MediaIndexDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                MediaIndexDatabase::class.java,
                "media_index.db"
            )
                .addMigrations(MEDIA_MIGRATION_1_2, MEDIA_MIGRATION_2_3, MEDIA_MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
