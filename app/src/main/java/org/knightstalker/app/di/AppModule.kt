// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.di

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.knightstalker.app.data.local.db.KnightDatabase
import org.knightstalker.app.data.local.db.MediaIndexDatabase
import org.knightstalker.app.data.local.db.MovieIndexDao
import org.knightstalker.app.data.local.db.SeriesIndexDao
import org.knightstalker.app.data.local.db.SyncStateDao
import org.knightstalker.app.data.local.prefs.UserPreferences
import org.knightstalker.app.data.local.sync.SyncEngine
import org.knightstalker.app.data.remote.StalkerApi
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.remote.interceptor.MacCookieInterceptor
import org.knightstalker.app.data.remote.interceptor.StalkerAuthInterceptor
import org.knightstalker.app.data.repository.*
import java.util.concurrent.TimeUnit

class AppModule(private val context: Context) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    val authManager: StalkerAuthManager by lazy {
        StalkerAuthManager(stalkerApi)
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(MacCookieInterceptor(
                macProvider = { authManager.currentPortal?.macAddress ?: "" },
                baseUrlProvider = { authManager.currentPortal?.url ?: "" }
            ))
            .addInterceptor(StalkerAuthInterceptor { authManager.currentToken })
            .build()
    }

    val stalkerApi: StalkerApi by lazy {
        StalkerApi(okHttpClient, json)
    }

    private val database: KnightDatabase by lazy {
        KnightDatabase.create(context)
    }

    private val mediaIndexDatabase: MediaIndexDatabase by lazy {
        MediaIndexDatabase.create(context)
    }
    val movieIndexDao: MovieIndexDao by lazy { mediaIndexDatabase.movieIndexDao() }
    val seriesIndexDao: SeriesIndexDao by lazy { mediaIndexDatabase.seriesIndexDao() }
    val syncStateDao: SyncStateDao by lazy { mediaIndexDatabase.syncStateDao() }

    val mediaIndexRepository: MediaIndexRepository by lazy {
        MediaIndexRepository(movieIndexDao, seriesIndexDao, syncStateDao)
    }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(context, stalkerApi, authManager, movieIndexDao, seriesIndexDao, syncStateDao, userPreferences)
    }

    val userPreferences: UserPreferences by lazy {
        UserPreferences(context)
    }

    val portalRepository: PortalRepository by lazy {
        PortalRepository(database.portalDao())
    }

    val channelRepository: ChannelRepository by lazy {
        ChannelRepository(stalkerApi, database.favoriteDao())
    }

    val epgRepository: EpgRepository by lazy {
        EpgRepository(stalkerApi)
    }

    val vodRepository: VodRepository by lazy {
        VodRepository(stalkerApi, database.favoriteDao())
    }

    val favoriteRepository: FavoriteRepository by lazy {
        FavoriteRepository(database.favoriteDao())
    }

    val watchHistoryRepository: WatchHistoryRepository by lazy {
        WatchHistoryRepository(database.watchHistoryDao())
    }

    val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(context).apply {
            // Re-mint a fresh play link before each download attempt so resume /
            // retry survives the portal's expiring single-use play tokens.
            linkResolver = { portalUrl, cmd -> vodRepository.createLink(portalUrl, cmd) }
        }
    }
}
