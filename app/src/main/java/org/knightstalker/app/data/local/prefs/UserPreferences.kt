// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class UserPreferences(private val context: Context) {

    companion object {
        val LAST_PORTAL_ID = longPreferencesKey("last_portal_id")
        val BUFFER_SIZE_MS = intPreferencesKey("buffer_size_ms")
        val AUTO_PLAY = booleanPreferencesKey("auto_play")
        val FAVORITE_CATEGORIES = stringSetPreferencesKey("favorite_categories")
    }

    val lastPortalId: Flow<Long?> = context.dataStore.data.map { it[LAST_PORTAL_ID] }
    val bufferSizeMs: Flow<Int> = context.dataStore.data.map { it[BUFFER_SIZE_MS] ?: 5000 }
    val autoPlay: Flow<Boolean> = context.dataStore.data.map { it[AUTO_PLAY] ?: true }
    val favoriteCategories: Flow<Set<String>> = context.dataStore.data.map { it[FAVORITE_CATEGORIES] ?: emptySet() }

    suspend fun setLastPortalId(id: Long) {
        context.dataStore.edit { it[LAST_PORTAL_ID] = id }
    }

    suspend fun setBufferSizeMs(ms: Int) {
        context.dataStore.edit { it[BUFFER_SIZE_MS] = ms }
    }

    suspend fun setAutoPlay(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_PLAY] = enabled }
    }

    suspend fun toggleFavoriteCategory(categoryId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[FAVORITE_CATEGORIES] ?: emptySet()
            prefs[FAVORITE_CATEGORIES] = if (categoryId in current) current - categoryId else current + categoryId
        }
    }
}
