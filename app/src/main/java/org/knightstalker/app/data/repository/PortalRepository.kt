// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.knightstalker.app.data.local.db.PortalDao
import org.knightstalker.app.data.local.entity.PortalEntity
import org.knightstalker.app.domain.model.Portal

class PortalRepository(private val portalDao: PortalDao) {

    val portals: Flow<List<Portal>> = portalDao.getAllPortals().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getById(id: Long): Portal? {
        return portalDao.getPortalById(id)?.toDomain()
    }

    suspend fun save(portal: Portal): Long {
        val entity = PortalEntity(
            id = portal.id,
            name = portal.name,
            url = portal.url,
            macAddress = portal.macAddress
        )
        return if (portal.id == 0L) {
            portalDao.insertPortal(entity)
        } else {
            portalDao.updatePortal(entity)
            portal.id
        }
    }

    suspend fun delete(portal: Portal) {
        portalDao.deletePortal(
            PortalEntity(id = portal.id, name = portal.name, url = portal.url, macAddress = portal.macAddress)
        )
    }

    suspend fun markConnected(id: Long) {
        portalDao.updateLastConnected(id, System.currentTimeMillis())
    }

    private fun PortalEntity.toDomain() = Portal(
        id = id,
        name = name,
        url = url,
        macAddress = macAddress
    )
}
