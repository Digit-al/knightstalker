// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.knightstalker.app.data.local.db.FavoriteDao
import org.knightstalker.app.data.local.entity.FavoriteEntity
import org.knightstalker.app.domain.model.Channel
import org.knightstalker.app.domain.model.VodItem

class FavoriteRepository(private val favoriteDao: FavoriteDao) {

    fun getFavoriteChannels(portalId: Long): Flow<List<FavoriteEntity>> {
        return favoriteDao.getFavoritesByType(portalId, "channel")
    }

    fun getFavoriteVod(portalId: Long): Flow<List<FavoriteEntity>> {
        return favoriteDao.getFavoritesByType(portalId, "vod")
    }

    fun getAllFavorites(portalId: Long): Flow<List<FavoriteEntity>> {
        return favoriteDao.getFavorites(portalId)
    }

    suspend fun toggleChannelFavorite(channel: Channel, portalId: Long) {
        if (favoriteDao.isFavorite(channel.id, portalId)) {
            favoriteDao.removeFavorite(channel.id, portalId)
        } else {
            favoriteDao.addFavorite(
                FavoriteEntity(
                    channelId = channel.id,
                    portalId = portalId,
                    name = channel.name,
                    logo = channel.logo,
                    cmd = channel.cmd,
                    type = "channel"
                )
            )
        }
    }

    suspend fun toggleVodFavorite(vod: VodItem, portalId: Long) {
        if (favoriteDao.isFavorite(vod.id, portalId)) {
            favoriteDao.removeFavorite(vod.id, portalId)
        } else {
            favoriteDao.addFavorite(
                FavoriteEntity(
                    channelId = vod.id,
                    portalId = portalId,
                    name = vod.name,
                    logo = vod.logo,
                    cmd = vod.cmd,
                    type = "vod"
                )
            )
        }
    }

    suspend fun isFavorite(itemId: String, portalId: Long): Boolean {
        return favoriteDao.isFavorite(itemId, portalId)
    }
}
