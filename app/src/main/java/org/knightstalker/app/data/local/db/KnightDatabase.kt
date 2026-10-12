// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.knightstalker.app.data.local.entity.FavoriteEntity
import org.knightstalker.app.data.local.entity.PortalEntity
import org.knightstalker.app.data.local.entity.WatchHistoryEntity

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `watch_history` (
                `itemId` TEXT NOT NULL,
                `portalId` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `logo` TEXT,
                `cmd` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `positionMs` INTEGER NOT NULL DEFAULT 0,
                `durationMs` INTEGER NOT NULL DEFAULT 0,
                `progressPercent` REAL NOT NULL DEFAULT 0,
                `isFinished` INTEGER NOT NULL DEFAULT 0,
                `lastWatched` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`itemId`)
            )
        """.trimIndent())
    }
}

/**
 * v3: portals gain `type`, `username`, `password` and `epgUrl` columns so that
 * M3U playlists and Xtream Codes panels can be configured alongside Stalker
 * portals. Existing rows default to a plain Stalker portal.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE portals ADD COLUMN type TEXT NOT NULL DEFAULT 'STALKER'")
        db.execSQL("ALTER TABLE portals ADD COLUMN username TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE portals ADD COLUMN password TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE portals ADD COLUMN epgUrl TEXT NOT NULL DEFAULT ''")
    }
}

@Database(
    entities = [PortalEntity::class, FavoriteEntity::class, WatchHistoryEntity::class],
    version = 3,
    exportSchema = false
)
abstract class KnightDatabase : RoomDatabase() {
    abstract fun portalDao(): PortalDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao

    companion object {
        fun create(context: Context): KnightDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                KnightDatabase::class.java,
                "knightstalker.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
