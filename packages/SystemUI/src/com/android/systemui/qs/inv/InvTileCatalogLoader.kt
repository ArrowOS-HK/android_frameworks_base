/*
 * Copyright (C) 2026 ArrowOS-T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.inv

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.android.systemui.R
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.external.CustomTile

/**
 * Loads available stock & third-party Quick Settings tiles asynchronously by registering
 * temporary tile listeners + refreshState() (matching AOSP TileQueryHelper) so icons
 * and labels are always populated instead of returning uninitialized states.
 */
class InvTileCatalogLoader(private val context: Context) {

    data class CatalogTile(
        val spec: String,
        val label: String,
        val appLabel: String?,
        val icon: Drawable
    )

    private val handler = Handler(Looper.getMainLooper())
    private val pendingTiles = mutableListOf<PendingTile>()
    private var generation = 0

    private class PendingTile(
        val spec: String,
        val tile: QSTile,
        var callback: QSTile.Callback? = null
    )

    fun loadAvailableTiles(
        host: QSHost,
        activeSpecs: Set<String>,
        onLoaded: (List<CatalogTile>) -> Unit
    ) {
        cancel()
        val currentGen = ++generation
        val results = linkedMapOf<String, CatalogTile>()

        val stockSpecs = context.getString(R.string.quick_settings_tiles_stock)
            .split(",")
            .map { it.trim() }
            .filter {
                it.isNotEmpty()
                    && !activeSpecs.contains(it)
                    && !InvPanelConfig.isDuplicateSystemTileSpec(it)
                    && !it.startsWith(CustomTile.PREFIX)
            }
            .toMutableList()

        if (Build.IS_DEBUGGABLE && !activeSpecs.contains("dbg:mem") && !stockSpecs.contains("dbg:mem")) {
            stockSpecs.add("dbg:mem")
        }

        val created = mutableListOf<PendingTile>()
        for (spec in stockSpecs) {
            val tile = try {
                host.createTile(spec)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (!tile.isAvailable) {
                tile.destroy()
                continue
            }
            tile.setTileSpec(spec)
            val entry = PendingTile(spec, tile)
            created.add(entry)
            pendingTiles.add(entry)
        }

        // Also query third-party TileService custom tiles synchronously via PackageManager
        addPackageTiles(activeSpecs, results)

        if (created.isEmpty()) {
            onLoaded(results.values.toList())
            return
        }

        fun extractIfReady(entry: PendingTile, allowFallbackIcon: Boolean): Boolean {
            val state = entry.tile.state
            val label = entry.tile.tileLabel?.toString()?.takeIf { it.isNotBlank() }
                ?: state.label?.toString()?.takeIf { it.isNotBlank() }
                ?: fallbackLabel(entry.spec)
            val drawable = state.icon?.getDrawable(context)
                ?: state.iconSupplier?.get()?.getDrawable(context)
                ?: (if (allowFallbackIcon) ContextCompat.getDrawable(context, R.drawable.inv_ic_add) else null)
                ?: return false
            results[entry.spec] = CatalogTile(
                spec = entry.spec,
                label = label,
                appLabel = null,
                icon = drawable.mutate()
            )
            return true
        }

        created.forEach { entry ->
            val cb = QSTile.Callback {
                if (currentGen != generation) return@Callback
                handler.post {
                    if (currentGen != generation) return@post
                    extractIfReady(entry, allowFallbackIcon = false)
                }
            }
            entry.callback = cb
            entry.tile.addCallback(cb)
            entry.tile.setListening(this, true)
            entry.tile.refreshState()
        }

        // Wait briefly for tile handlers to push refreshed states, then finalize & destroy temp tiles.
        handler.postDelayed({
            if (currentGen != generation) return@postDelayed
            created.forEach { entry ->
                extractIfReady(entry, allowFallbackIcon = true)
                cleanupEntry(entry)
            }
            pendingTiles.clear()
            onLoaded(results.values.toList())
        }, 250L)
    }

    fun cancel() {
        generation++
        handler.removeCallbacksAndMessages(null)
        pendingTiles.forEach { cleanupEntry(it) }
        pendingTiles.clear()
    }

    private fun cleanupEntry(entry: PendingTile) {
        try {
            entry.callback?.let { entry.tile.removeCallback(it) }
            entry.tile.setListening(this, false)
            entry.tile.destroy()
        } catch (_: Throwable) {
        }
    }

    private fun addPackageTiles(
        activeSpecs: Set<String>,
        results: MutableMap<String, CatalogTile>
    ) {
        try {
            val pm = context.packageManager
            val stockCsv = context.getString(R.string.quick_settings_tiles_stock)
            val services = pm.queryIntentServicesAsUser(
                Intent(TileService.ACTION_QS_TILE),
                0,
                UserHandle.myUserId()
            )
            for (info in services) {
                val si = info.serviceInfo ?: continue
                if (Manifest.permission.BIND_QUICK_SETTINGS_TILE != si.permission) continue
                val cn = ComponentName(si.packageName, si.name)
                if (stockCsv.contains(cn.flattenToString())) continue
                val spec = CustomTile.toSpec(cn)
                if (activeSpecs.contains(spec) || results.containsKey(spec)) continue
                val icon = si.loadIcon(pm) ?: continue
                val label = si.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: si.name
                val appLabel = si.applicationInfo?.loadLabel(pm)?.toString()
                results[spec] = CatalogTile(
                    spec = spec,
                    label = label,
                    appLabel = if (appLabel == label) null else appLabel,
                    icon = icon.mutate()
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun fallbackLabel(spec: String): String {
        return spec.replaceFirstChar { it.uppercase() }
    }
}
