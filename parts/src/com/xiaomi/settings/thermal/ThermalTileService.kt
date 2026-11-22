/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.xiaomi.settings.thermal.ThermalController.ThermalProfile

class ThermalTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!ThermalController.isSupported()) return
        val current = ThermalController.getSelectedProfile(this)
        val next = ThermalProfile.nextGlobal(current)
        ThermalController.setSelectedProfile(this, next)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        if (!ThermalController.isSupported()) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.updateTile()
            return
        }
        val profile = ThermalController.getSelectedProfile(this)
        tile.state =
            when (profile) {
                ThermalProfile.GAMING,
                ThermalProfile.PERFORMANCE -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
        tile.subtitle = getString(profile.labelRes)
        tile.updateTile()
    }
}
