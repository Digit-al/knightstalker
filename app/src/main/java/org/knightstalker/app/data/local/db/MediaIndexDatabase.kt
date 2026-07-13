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

@Database(
    entities = [
        MovieIndexEntity::class,
        SeriesIndexEntity::class,
        MovieFtsEntity::class,
        SeriesFtsEntity::class,
        SyncStateEntity::class
    ],
    version = 2,
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
                .addMigrations(MEDIA_MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
