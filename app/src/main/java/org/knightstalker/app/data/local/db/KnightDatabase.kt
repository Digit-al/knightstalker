// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.knightstalker.app.data.local.entity.FavoriteEntity
import org.knightstalker.app.data.local.entity.PortalEntity

@Database(
    entities = [PortalEntity::class, FavoriteEntity::class],
    version = 1,
    exportSchema = false
)
abstract class KnightDatabase : RoomDatabase() {
    abstract fun portalDao(): PortalDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        fun create(context: Context): KnightDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                KnightDatabase::class.java,
                "knightstalker.db"
            ).build()
        }
    }
}
