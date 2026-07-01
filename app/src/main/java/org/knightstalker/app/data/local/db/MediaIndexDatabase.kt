// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.knightstalker.app.data.local.entity.MovieFtsEntity
import org.knightstalker.app.data.local.entity.MovieIndexEntity
import org.knightstalker.app.data.local.entity.SeriesFtsEntity
import org.knightstalker.app.data.local.entity.SeriesIndexEntity
import org.knightstalker.app.data.local.entity.SyncStateEntity

@Database(
    entities = [
        MovieIndexEntity::class,
        SeriesIndexEntity::class,
        MovieFtsEntity::class,
        SeriesFtsEntity::class,
        SyncStateEntity::class
    ],
    version = 1,
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
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
