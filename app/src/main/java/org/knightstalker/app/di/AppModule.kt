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
import org.knightstalker.app.data.remote.TmdbApi
import org.knightstalker.app.data.remote.XtreamCodesApi
import org.knightstalker.app.data.remote.interceptor.MacCookieInterceptor
import org.knightstalker.app.data.remote.interceptor.StalkerAuthInterceptor
import org.knightstalker.app.data.repository.*
import org.knightstalker.app.BuildConfig
import java.util.concurrent.TimeUnit

class AppModule(private val context: Context) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
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

    val xtreamApi: XtreamCodesApi by lazy {
        XtreamCodesApi(plainHttpClient, json)
    }

    val authManager: StalkerAuthManager by lazy {
        StalkerAuthManager(stalkerApi, xtreamApi)
    }

    // A plain client for third-party lookups (TVmaze) — deliberately WITHOUT the Stalker
    // MAC-cookie / auth interceptors so we never leak portal credentials to other hosts.
    private val plainHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
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
        SyncEngine(context, stalkerApi, authManager, xtreamApi, movieIndexDao, seriesIndexDao, syncStateDao, userPreferences, portalRepository)
    }

    val userPreferences: UserPreferences by lazy {
        UserPreferences(context)
    }

    val portalRepository: PortalRepository by lazy {
        PortalRepository(database.portalDao())
    }

    val channelRepository: ChannelRepository by lazy {
        ChannelRepository(stalkerApi, database.favoriteDao(), authManager, xtreamApi)
    }

    val epgService: EpgService by lazy {
        EpgService(plainHttpClient, database.portalDao())
    }

    val epgRepository: EpgRepository by lazy {
        EpgRepository(stalkerApi, authManager, epgService)
    }

    val vodRepository: VodRepository by lazy {
        VodRepository(stalkerApi, database.favoriteDao(), authManager, xtreamApi)
    }

    val favoriteRepository: FavoriteRepository by lazy {
        FavoriteRepository(database.favoriteDao())
    }

    val watchHistoryRepository: WatchHistoryRepository by lazy {
        WatchHistoryRepository(database.watchHistoryDao())
    }

    val seriesRatingRepository: SeriesRatingRepository by lazy {
        SeriesRatingRepository(plainHttpClient, json, seriesIndexDao)
    }

    val tmdbApi: TmdbApi by lazy {
        // The TMDB API key is optional: when absent the enrichment is simply skipped.
        TmdbApi(plainHttpClient, json, BuildConfig.TMDB_API_KEY)
    }

    val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(context).apply {
            // Re-mint a fresh play link before each download attempt so resume /
            // retry survives the portal's expiring single-use play tokens.
            linkResolver = { portalUrl, cmd, name, ext -> vodRepository.createLink(portalUrl, cmd, name, ext) }
        }
    }
}
