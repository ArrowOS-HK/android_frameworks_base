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

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import com.android.systemui.R
import kotlin.math.floor

enum class InvControlId(
    val title: String,
    val iconRes: Int,
    val defaultWidthSpan: Int,
    val defaultHeightSpan: Int
) {
    INTERNET("Internet", R.drawable.inv_ic_internet, 2, 1),
    BLUETOOTH("Bluetooth", R.drawable.inv_ic_bluetooth, 2, 1),
    MEDIA("Media player", R.drawable.inv_ic_music, 2, 2),
    BRIGHTNESS_V("Vertical brightness", R.drawable.inv_ic_brightness, 1, 2),
    VOLUME_V("Vertical volume", R.drawable.inv_ic_volume, 1, 2),
    BRIGHTNESS_H("Horizontal brightness", R.drawable.inv_ic_brightness, 4, 1),
    VOLUME_H("Horizontal volume", R.drawable.inv_ic_volume, 4, 1),
    AUTO_BRIGHTNESS("Auto brightness", R.drawable.inv_ic_auto_brightness, 1, 1),
    MUTE("Mute volume", R.drawable.inv_ic_volume_off, 1, 1),
    RINGER("Ringer mode", R.drawable.inv_ic_notifications, 1, 1),
    DATA_USAGE("Data usage", R.drawable.inv_ic_data_usage, 2, 1),
    BATTERY_VIEW("Battery", R.drawable.inv_ic_battery_view, 2, 1),
    FPS_INFO("FPS info", R.drawable.inv_ic_fps, 1, 1),
    CUSTOM_NAME("Custom name", R.drawable.inv_ic_person, 4, 1),
    CUSTOM_IMAGE("Custom image", R.drawable.inv_ic_image, 4, 2);

    companion object {
        fun fromName(name: String): InvControlId? = values().firstOrNull { it.name == name }
    }
}

data class GridSize(val rows: Int, val cols: Int) {
    val label: String get() = "${rows}×${cols}"
}

/**
 * Persists Extended QS customization (per-tile 1x1 / 2x1 sizes, control ordering,
 * resizable card spans, and label toggle) while computing grid dimensions automatically
 * from screen width, density, and orientation.
 */
class InvPanelConfig(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var showLabels: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LABELS, false)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_LABELS, value).apply() }

    /** Number of Extended QS controls shown on 1x notification shade pulldown (2 or 4). */
    var qqsControlCount: Int
        get() = if (prefs.getInt(KEY_QQS_CONTROL_COUNT, 4) == 2) 2 else 4
        set(value) {
            val sanitized = if (value == 2) 2 else 4
            prefs.edit().putInt(KEY_QQS_CONTROL_COUNT, sanitized).apply()
        }

    var mediaSizeIndex: Int
        get() = prefs.getInt(KEY_MEDIA_SIZE, 1).coerceIn(0, MEDIA_SIZES.lastIndex)
        set(value) { prefs.edit().putInt(KEY_MEDIA_SIZE, value.coerceIn(0, MEDIA_SIZES.lastIndex)).apply() }

    var customImageSizeIndex: Int
        get() = prefs.getInt(KEY_CUSTOM_IMAGE_SIZE, 5).coerceIn(0, MEDIA_SIZES.lastIndex)
        set(value) { prefs.edit().putInt(KEY_CUSTOM_IMAGE_SIZE, value.coerceIn(0, MEDIA_SIZES.lastIndex)).apply() }

    /**
     * Automatically calculates optimal grid column count from available width (or display metrics),
     * density, and orientation so users never need manual row/column sliders.
     */
    fun computeAutoColumns(availableWidthPx: Int = 0): Int {
        val dm = context.resources.displayMetrics
        val config = context.resources.configuration
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

        val effectiveWidthPx = if (availableWidthPx > 0) {
            availableWidthPx
        } else {
            val horizontalChromePx = (20f * dm.density).toInt()
            val fullWidth = dm.widthPixels - horizontalChromePx
            if (isLandscape) (fullWidth / 2) else fullWidth
        }

        val availDp = effectiveWidthPx / dm.density.coerceAtLeast(1f)
        // Each base 1x1 cell targets ~76dp + ~12dp spacing (~86dp effective stride).
        val computed = floor((availDp + 12f) / 86f).toInt()
        return if (availDp >= 560f) {
            computed.coerceIn(4, 8)
        } else {
            computed.coerceIn(3, 4).let { if (availDp >= 320f) 4 else it }
        }
    }

    fun getMediaSize(): GridSize = MEDIA_SIZES[mediaSizeIndex]

    fun getCustomImageSize(): GridSize = MEDIA_SIZES[customImageSizeIndex]

    fun setMediaSize(size: GridSize) {
        val idx = MEDIA_SIZES.indexOf(size)
        if (idx != -1) mediaSizeIndex = idx
    }

    fun setCustomImageSize(size: GridSize) {
        val idx = MEDIA_SIZES.indexOf(size)
        if (idx != -1) customImageSizeIndex = idx
    }

    fun cycleMediaSize(): GridSize {
        mediaSizeIndex = (mediaSizeIndex + 1) % MEDIA_SIZES.size
        return getMediaSize()
    }

    fun cycleCustomImageSize(): GridSize {
        customImageSizeIndex = (customImageSizeIndex + 1) % MEDIA_SIZES.size
        return getCustomImageSize()
    }

    /** Returns allowed (rows, cols) sizes for any control block inside [maxCols] grid columns. */
    fun getAllowedControlSizes(id: InvControlId, maxCols: Int = 4): List<GridSize> {
        val safeMax = maxCols.coerceAtLeast(1)
        return when (id) {
            InvControlId.MEDIA,
            InvControlId.CUSTOM_IMAGE -> MEDIA_SIZES.map {
                GridSize(it.rows, it.cols.coerceAtMost(safeMax))
            }.distinct()
            InvControlId.INTERNET,
            InvControlId.BLUETOOTH,
            InvControlId.DATA_USAGE,
            InvControlId.BATTERY_VIEW,
            InvControlId.AUTO_BRIGHTNESS,
            InvControlId.MUTE,
            InvControlId.RINGER,
            InvControlId.FPS_INFO -> if (safeMax >= 2) {
                listOf(GridSize(1, 1), GridSize(1, 2))
            } else {
                listOf(GridSize(1, 1))
            }
            InvControlId.BRIGHTNESS_H,
            InvControlId.VOLUME_H,
            InvControlId.CUSTOM_NAME -> (2..safeMax.coerceIn(2, 6)).map { c -> GridSize(1, c) }
            InvControlId.BRIGHTNESS_V,
            InvControlId.VOLUME_V -> if (safeMax >= 2) {
                listOf(GridSize(2, 1), GridSize(2, 2))
            } else {
                listOf(GridSize(2, 1))
            }
        }
    }

    /** Current (rows, cols) size for [id], respecting per-control overrides and screen width. */
    fun getControlSize(id: InvControlId, maxCols: Int = 4): GridSize {
        if (id == InvControlId.MEDIA) {
            val sz = getMediaSize()
            return GridSize(sz.rows.coerceIn(1, 2), sz.cols.coerceAtMost(maxCols.coerceAtLeast(1)))
        }
        if (id == InvControlId.CUSTOM_IMAGE) {
            val sz = getCustomImageSize()
            return GridSize(sz.rows.coerceIn(1, 2), sz.cols.coerceAtMost(maxCols.coerceAtLeast(1)))
        }
        val allowed = getAllowedControlSizes(id, maxCols)
        val saved = getControlSpanMap()[id.name]
        if (saved != null) {
            val exact = allowed.firstOrNull { it == saved }
            if (exact != null) return exact
            return allowed.minByOrNull { kotlin.math.abs(it.cols - saved.cols) + kotlin.math.abs(it.rows - saved.rows) }
                ?: allowed.first()
        }
        val defaultCols = when (id) {
            InvControlId.BRIGHTNESS_H,
            InvControlId.VOLUME_H,
            InvControlId.CUSTOM_NAME -> maxCols
            else -> id.defaultWidthSpan
        }.coerceIn(1, maxCols.coerceAtLeast(1))
        val desired = GridSize(id.defaultHeightSpan, defaultCols)
        return allowed.firstOrNull { it == desired } ?: allowed.last()
    }

    fun setControlSize(id: InvControlId, size: GridSize) {
        if (id == InvControlId.MEDIA) {
            setMediaSize(size)
            return
        }
        if (id == InvControlId.CUSTOM_IMAGE) {
            setCustomImageSize(size)
            return
        }
        val map = getControlSpanMap().toMutableMap()
        map[id.name] = size
        saveControlSpanMap(map)
    }

    fun cycleControlSize(id: InvControlId, maxCols: Int = 4): GridSize {
        val allowed = getAllowedControlSizes(id, maxCols)
        val cur = getControlSize(id, maxCols)
        val idx = allowed.indexOf(cur)
        val next = allowed[(idx + 1) % allowed.size]
        setControlSize(id, next)
        return next
    }

    private fun getControlSpanMap(): Map<String, GridSize> {
        val raw = prefs.getString(KEY_CONTROL_SPANS, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        val result = mutableMapOf<String, GridSize>()
        raw.split(";").forEach { entry ->
            val parts = entry.split("=")
            if (parts.size == 2) {
                val dims = parts[1].split("x")
                val r = dims.getOrNull(0)?.toIntOrNull()
                val c = dims.getOrNull(1)?.toIntOrNull()
                if (parts[0].isNotBlank() && r != null && c != null) {
                    result[parts[0]] = GridSize(r.coerceIn(1, 4), c.coerceIn(1, 8))
                }
            }
        }
        return result
    }

    private fun saveControlSpanMap(map: Map<String, GridSize>) {
        val encoded = map.entries.joinToString(";") { "${it.key}=${it.value.rows}x${it.value.cols}" }
        prefs.edit().putString(KEY_CONTROL_SPANS, encoded).apply()
    }

    /** Per-tile A16/17 sizing: returns colSpan (1 or 2) for [spec]. Default is 1 (1x1). */
    fun getTileColSpan(spec: String, maxCols: Int = 4): Int {
        val saved = getTileSpanMap()[spec] ?: 1
        return saved.coerceIn(1, maxCols.coerceAtMost(2))
    }

    fun setTileColSpan(spec: String, colSpan: Int) {
        val clamped = colSpan.coerceIn(1, 2)
        val map = getTileSpanMap().toMutableMap()
        if (clamped == 1) {
            map.remove(spec)
        } else {
            map[spec] = clamped
        }
        saveTileSpanMap(map)
    }

    fun toggleTileColSpan(spec: String, maxCols: Int = 4): Int {
        val next = if (getTileColSpan(spec, maxCols) == 1 && maxCols >= 2) 2 else 1
        setTileColSpan(spec, next)
        return next
    }

    private fun getTileSpanMap(): Map<String, Int> {
        val raw = prefs.getString(KEY_TILE_SPANS, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        val result = mutableMapOf<String, Int>()
        raw.split(";").forEach { entry ->
            val parts = entry.split("=")
            if (parts.size == 2) {
                val span = parts[1].toIntOrNull()
                if (parts[0].isNotBlank() && span != null) {
                    result[parts[0]] = span.coerceIn(1, 2)
                }
            }
        }
        return result
    }

    private fun saveTileSpanMap(map: Map<String, Int>) {
        val encoded = map.entries.joinToString(";") { "${it.key}=${it.value}" }
        prefs.edit().putString(KEY_TILE_SPANS, encoded).apply()
    }

    /** Whether [spec] uses the squarer corner style (true) instead of the default round one. */
    fun isTileSquare(spec: String): Boolean = getTileSquareSpecs().contains(spec)

    fun setTileSquare(spec: String, square: Boolean) {
        if (spec.isBlank()) return
        val specs = getTileSquareSpecs().toMutableSet()
        if (square) specs.add(spec) else specs.remove(spec)
        saveTileSquareSpecs(specs)
    }

    /** Toggles the corner style of [spec] and returns the new "square" state. */
    fun toggleTileSquare(spec: String): Boolean {
        val next = !isTileSquare(spec)
        setTileSquare(spec, next)
        return next
    }

    private fun getTileSquareSpecs(): Set<String> {
        val raw = prefs.getString(KEY_TILE_SQUARES, "") ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun saveTileSquareSpecs(specs: Set<String>) {
        prefs.edit().putString(KEY_TILE_SQUARES, specs.joinToString(";")).apply()
    }

    fun getControls(): List<InvControlId> {
        val raw = prefs.getString(KEY_CONTROLS, DEFAULT_CONTROLS) ?: DEFAULT_CONTROLS
        val parsed = raw.split(",").mapNotNull { InvControlId.fromName(it.trim()) }
        return if (parsed.isEmpty()) listOf(
            InvControlId.INTERNET,
            InvControlId.MEDIA,
            InvControlId.BLUETOOTH,
            InvControlId.BRIGHTNESS_V,
            InvControlId.VOLUME_V
        ) else parsed
    }

    fun setControls(list: List<InvControlId>) {
        prefs.edit().putString(KEY_CONTROLS, list.joinToString(",") { it.name }).apply()
    }

    /**
     * Returns the unified ordered list of grid tokens (`ctrl:<ID>` and `tile:<SPEC>`),
     * automatically migrating legacy controls + [fallbackTileSpecs] on first run and
     * filtering out duplicate `internet` / `bt` / `wifi` tile specs.
     */
    fun getUnifiedItems(fallbackTileSpecs: List<String> = emptyList()): List<String> {
        if (prefs.contains(KEY_UNIFIED_ITEMS)) {
            val raw = prefs.getString(KEY_UNIFIED_ITEMS, "") ?: ""
            val parsed = parseUnifiedTokens(raw)
            if (parsed.isNotEmpty()) return parsed
        }
        val migrated = ArrayList<String>()
        getControls().forEach { id ->
            val token = controlToken(id)
            if (!migrated.contains(token)) migrated.add(token)
        }
        fallbackTileSpecs.forEach { spec ->
            val trimmed = spec.trim()
            if (trimmed.isNotEmpty() && !isDuplicateSystemTileSpec(trimmed)) {
                val token = tileToken(trimmed)
                if (!migrated.contains(token)) migrated.add(token)
            }
        }
        if (fallbackTileSpecs.isNotEmpty()) {
            setUnifiedItems(migrated)
        }
        return if (migrated.isEmpty()) {
            listOf(
                controlToken(InvControlId.INTERNET),
                controlToken(InvControlId.MEDIA),
                controlToken(InvControlId.BLUETOOTH)
            )
        } else {
            migrated
        }
    }

    fun setUnifiedItems(items: List<String>) {
        val cleaned = items.mapNotNull { normalizeUnifiedToken(it) }.distinct()
        val ctrls = cleaned.mapNotNull { parseControlId(it)?.name }
        prefs.edit()
            .putString(KEY_UNIFIED_ITEMS, cleaned.joinToString(","))
            .apply {
                if (ctrls.isNotEmpty()) putString(KEY_CONTROLS, ctrls.joinToString(","))
            }
            .apply()
    }

    fun moveUnifiedItem(fromIndex: Int, toIndex: Int) {
        val cur = getUnifiedItems().toMutableList()
        if (fromIndex !in cur.indices || toIndex !in cur.indices || fromIndex == toIndex) return
        val moved = cur.removeAt(fromIndex)
        cur.add(toIndex, moved)
        setUnifiedItems(cur)
    }

    fun removeUnifiedItem(token: String): Boolean {
        val normalized = normalizeUnifiedToken(token) ?: return false
        val cur = getUnifiedItems().toMutableList()
        if (cur.size <= 1) return false
        val removed = cur.remove(normalized)
        if (removed) {
            if (parseControlId(normalized) == InvControlId.CUSTOM_IMAGE) {
                prefs.edit().putBoolean(KEY_CUSTOM_IMAGE_HIDDEN, true).apply()
            }
            setUnifiedItems(cur)
        }
        return removed
    }

    fun addUnifiedItem(token: String, hostTilesFallback: List<String> = emptyList()): Boolean {
        val normalized = normalizeUnifiedToken(token) ?: return false
        val cur = getUnifiedItems(hostTilesFallback).toMutableList()
        if (cur.contains(normalized)) return false
        if (parseControlId(normalized) == InvControlId.CUSTOM_IMAGE) {
            prefs.edit().putBoolean(KEY_CUSTOM_IMAGE_HIDDEN, false).apply()
        }
        cur.add(normalized)
        setUnifiedItems(cur)
        return true
    }

    fun toggleUnifiedControl(id: InvControlId): Boolean {
        val token = controlToken(id)
        val cur = getUnifiedItems().toMutableList()
        val added = if (cur.contains(token)) {
            if (cur.size > 1) {
                cur.remove(token)
                if (id == InvControlId.CUSTOM_IMAGE) {
                    prefs.edit().putBoolean(KEY_CUSTOM_IMAGE_HIDDEN, true).apply()
                }
            }
            false
        } else {
            if (id == InvControlId.CUSTOM_IMAGE) {
                prefs.edit().putBoolean(KEY_CUSTOM_IMAGE_HIDDEN, false).apply()
            }
            cur.add(token)
            true
        }
        setUnifiedItems(cur)
        return added
    }

    /**
     * Merges external [QSHost] additions/removals (e.g. 3rd-party tile additions) into
     * the unified list without duplicating `internet`/`bt`/`wifi`.
     */
    fun syncExternalHostTiles(hostSpecs: List<String>): Boolean {
        if (!prefs.contains(KEY_UNIFIED_ITEMS)) {
            getUnifiedItems(hostSpecs)
            return true
        }
        val cleanHost = hostSpecs.map { it.trim() }
            .filter { it.isNotEmpty() && !isDuplicateSystemTileSpec(it) }
        val cur = getUnifiedItems().toMutableList()
        var changed = false
        val existingTileSpecs = cur.mapNotNull { parseTileSpec(it) }.toSet()
        cleanHost.forEach { spec ->
            if (!existingTileSpecs.contains(spec)) {
                cur.add(tileToken(spec))
                changed = true
            }
        }
        if (changed) {
            setUnifiedItems(cur)
        }
        return changed
    }

    /**
     * Builds the `QSHost` tile spec list matching the unified order so stock QS
     * and system listeners stay synchronized if Extended QS is toggled off.
     */
    fun buildHostTileSpecsForUnified(unifiedItems: List<String> = getUnifiedItems()): List<String> {
        val result = ArrayList<String>()
        unifiedItems.forEach { token ->
            val ctrl = parseControlId(token)
            when {
                ctrl == InvControlId.INTERNET -> if (!result.contains("internet")) result.add("internet")
                ctrl == InvControlId.BLUETOOTH -> if (!result.contains("bt")) result.add("bt")
                else -> {
                    val spec = parseTileSpec(token)
                    if (!spec.isNullOrBlank() && !isDuplicateSystemTileSpec(spec) && !result.contains(spec)) {
                        result.add(spec)
                    }
                }
            }
        }
        return result
    }

    fun getUnifiedItemSize(token: String, maxCols: Int = 4): GridSize {
        val ctrl = parseControlId(token)
        if (ctrl != null) return getControlSize(ctrl, maxCols)
        val spec = parseTileSpec(token) ?: return GridSize(1, 1)
        return GridSize(1, getTileColSpan(spec, maxCols))
    }

    fun getAllowedUnifiedSizes(token: String, maxCols: Int = 4): List<GridSize> {
        val ctrl = parseControlId(token)
        if (ctrl != null) return getAllowedControlSizes(ctrl, maxCols)
        return if (maxCols >= 2) listOf(GridSize(1, 1), GridSize(1, 2)) else listOf(GridSize(1, 1))
    }

    fun toggleControl(id: InvControlId): Boolean = toggleUnifiedControl(id)

    fun removeControl(id: InvControlId) {
        removeUnifiedItem(controlToken(id))
    }

    fun ensureControl(id: InvControlId, forceShow: Boolean = false): Boolean {
        if (id == InvControlId.CUSTOM_IMAGE) {
            if (forceShow) {
                prefs.edit().putBoolean(KEY_CUSTOM_IMAGE_HIDDEN, false).apply()
            } else if (prefs.getBoolean(KEY_CUSTOM_IMAGE_HIDDEN, false)) {
                return false
            }
        }
        val token = controlToken(id)
        val cur = getUnifiedItems().toMutableList()
        if (cur.contains(token)) return false
        cur.add(token)
        setUnifiedItems(cur)
        return true
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun moveControl(fromIndex: Int, toIndex: Int) {
        moveUnifiedItem(fromIndex, toIndex)
    }

    fun resetDefaults(defaultTileSpecs: List<String> = emptyList()) {
        val baseControls = listOf(
            controlToken(InvControlId.INTERNET),
            controlToken(InvControlId.MEDIA),
            controlToken(InvControlId.BLUETOOTH),
            controlToken(InvControlId.BRIGHTNESS_V),
            controlToken(InvControlId.VOLUME_V)
        )
        val extraTiles = defaultTileSpecs
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isDuplicateSystemTileSpec(it) }
            .map { tileToken(it) }
        val unified = (baseControls + extraTiles).distinct()
        prefs.edit()
            .putString(KEY_CONTROLS, DEFAULT_CONTROLS)
            .putString(KEY_UNIFIED_ITEMS, unified.joinToString(","))
            .remove(KEY_CONTROL_SPANS)
            .remove(KEY_TILE_SPANS)
            .putBoolean(KEY_SHOW_LABELS, false)
            .putBoolean(KEY_CUSTOM_IMAGE_HIDDEN, false)
            .putInt(KEY_QQS_CONTROL_COUNT, 4)
            .putInt(KEY_MEDIA_SIZE, 1) // 2x2
            .putInt(KEY_CUSTOM_IMAGE_SIZE, 5) // 2x4
            .apply()
    }

    private fun parseUnifiedTokens(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(",")
            .mapNotNull { normalizeUnifiedToken(it) }
            .distinct()
    }

    private fun normalizeUnifiedToken(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.startsWith(PREFIX_CTRL)) {
            val id = InvControlId.fromName(t.removePrefix(PREFIX_CTRL)) ?: return null
            return controlToken(id)
        }
        if (t.startsWith(PREFIX_TILE)) {
            val spec = t.removePrefix(PREFIX_TILE).trim()
            if (spec.isEmpty() || isDuplicateSystemTileSpec(spec)) return null
            return tileToken(spec)
        }
        val legacyCtrl = InvControlId.fromName(t)
        return if (legacyCtrl != null) controlToken(legacyCtrl) else null
    }

    companion object {
        private const val PREFS_NAME = "inv_extended_qs_prefs"
        private const val KEY_CONTROLS = "controls"
        private const val KEY_UNIFIED_ITEMS = "unified_items"
        private const val KEY_CONTROL_SPANS = "control_spans"
        private const val KEY_TILE_SPANS = "tile_spans"
        private const val KEY_TILE_SQUARES = "tile_squares"
        private const val KEY_SHOW_LABELS = "show_labels"
        private const val KEY_QQS_CONTROL_COUNT = "qqs_control_count"
        private const val KEY_CUSTOM_IMAGE_HIDDEN = "custom_image_hidden"
        private const val KEY_MEDIA_SIZE = "media_size"
        private const val KEY_CUSTOM_IMAGE_SIZE = "custom_image_size"

        const val PREFIX_CTRL = "ctrl:"
        const val PREFIX_TILE = "tile:"

        private const val DEFAULT_CONTROLS =
            "INTERNET,MEDIA,BLUETOOTH,BRIGHTNESS_V,VOLUME_V"

        val MEDIA_SIZES = listOf(
            GridSize(1, 2),
            GridSize(2, 2),
            GridSize(2, 3),
            GridSize(1, 3),
            GridSize(1, 4),
            GridSize(2, 4)
        )

        fun controlToken(id: InvControlId): String = "$PREFIX_CTRL${id.name}"

        fun tileToken(spec: String): String = "$PREFIX_TILE$spec"

        fun parseControlId(token: String): InvControlId? {
            if (!token.startsWith(PREFIX_CTRL)) return null
            return InvControlId.fromName(token.removePrefix(PREFIX_CTRL))
        }

        fun parseTileSpec(token: String): String? {
            if (!token.startsWith(PREFIX_TILE)) return null
            return token.removePrefix(PREFIX_TILE).takeIf { it.isNotBlank() }
        }

        fun isDuplicateSystemTileSpec(spec: String): Boolean {
            val clean = spec.trim()
            return clean == "internet" || clean == "bt" || clean == "wifi"
        }
    }
}
