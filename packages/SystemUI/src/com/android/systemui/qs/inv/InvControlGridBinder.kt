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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.telephony.TelephonyManager
import android.text.format.Formatter
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.android.settingslib.net.DataUsageController
import com.android.systemui.Dependency
import com.android.systemui.R
import com.android.systemui.animation.LaunchableFrameLayout
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.tiles.dialog.BluetoothDialogFactory
import com.android.systemui.qs.tiles.dialog.InternetDialogFactory
import com.android.systemui.statusbar.policy.BluetoothController
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dynamically lays out and binds all InvControlId blocks inside a reusable 2D GridLayout,
 * supporting live system sync, per-control resizing (1x1 / 2x1 / slider / card spans),
 * drag-and-drop reordering, and Android 17 springy transitions.
 */
class InvControlGridBinder(
    private val context: Context,
    private val config: InvPanelConfig
) {

    private val inflater = LayoutInflater.from(context)
    private val handler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val sliders = InvSlidersController(context)
    private val media = InvMediaController(context)

    private val bluetoothController = Dependency.get(BluetoothController::class.java)
    private val internetDialogFactory = Dependency.get(InternetDialogFactory::class.java)
    private val bluetoothDialogFactory = Dependency.get(BluetoothDialogFactory::class.java)
    private val activityStarter = Dependency.get(ActivityStarter::class.java)

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val dataUsageController = DataUsageController(context)

    private var qsHost: QSHost? = null
    private val controlBindings = mutableMapOf<InvControlId, ControlSurfaceBinding>()
    private data class TileHolder(val tile: QSTile, val callback: QSTile.Callback)
    private data class UnifiedDragPayload(val token: String)
    private val tileHolders = mutableListOf<TileHolder>()
    private var customImageView: InvGifImageView? = null
    private var customNameTitle: TextView? = null
    private var customNameSubtitle: TextView? = null

    private var listening = false
    private var lastBuiltCols = 0
    private var bluetoothTransientPending: Boolean? = null
    private var lastResizedToken: String? = null
    @Volatile
    private var cachedDataUsageSubtitle: String = "Unavailable"
    @Volatile
    private var cachedDataUsageActive: Boolean = false
    private var lastDataUsageQueryMs: Long = 0L

    private data class PillBinding(
        val root: LaunchableFrameLayout,
        val body: View,
        val icon: ImageView,
        val label: TextView,
        val summary: TextView
    )

    private data class CircleBinding(
        val launchRoot: LaunchableFrameLayout,
        val icon: ImageView
    )

    private inner class ControlSurfaceBinding(
        val pill: PillBinding? = null,
        val circle: CircleBinding? = null,
        private val squareKey: String? = null
    ) {
        val actionView: View? get() = pill?.root ?: circle?.launchRoot

        fun apply(
            active: Boolean,
            iconRes: Int,
            title: String,
            subtitle: String,
            showSubtitle: Boolean = true
        ) {
            val square = squareKey != null && config.isTileSquare(squareKey)
            if (pill != null) {
                val bgRes = if (active) {
                    if (square) R.drawable.inv_bg_pill_on_square else R.drawable.inv_bg_pill_on
                } else {
                    if (square) R.drawable.inv_bg_pill_square else R.drawable.inv_bg_pill
                }
                val fg = ContextCompat.getColor(
                    context,
                    if (active) R.color.inv_text_on_accent else R.color.inv_text_primary
                )
                val sub = ContextCompat.getColor(
                    context,
                    if (active) R.color.inv_text_on_accent else R.color.inv_text_secondary
                )
                pill.body.setBackgroundResource(bgRes)
                pill.icon.setImageResource(iconRes)
                pill.icon.imageTintList = ColorStateList.valueOf(fg)
                pill.label.text = title
                pill.label.setTextColor(fg)
                pill.summary.text = subtitle
                pill.summary.setTextColor(sub)
                pill.summary.visibility = if (showSubtitle) View.VISIBLE else View.GONE
            } else if (circle != null) {
                circle.launchRoot.setBackgroundResource(
                    if (active) {
                        if (square) R.drawable.inv_bg_tile_on_square else R.drawable.inv_bg_tile_on
                    } else {
                        if (square) R.drawable.inv_bg_tile_off_square else R.drawable.inv_bg_tile_off
                    }
                )
                val tint = ContextCompat.getColor(
                    context,
                    if (active) R.color.inv_icon_on_accent else R.color.inv_icon_primary
                )
                circle.icon.setImageResource(iconRes)
                circle.icon.imageTintList = ColorStateList.valueOf(tint)
            }
        }
    }

    private val bluetoothCallback = object : BluetoothController.Callback {
        override fun onBluetoothStateChange(enabled: Boolean) {
            bluetoothTransientPending = null
            handler.post { refreshBluetooth() }
        }

        override fun onBluetoothDevicesChanged() {
            handler.post { refreshBluetooth() }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                refreshInternet()
                refreshDataUsage()
            }
        }

        override fun onLost(network: Network) {
            handler.post {
                refreshInternet()
                refreshDataUsage()
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            handler.post { refreshInternet() }
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    bluetoothTransientPending = null
                    refreshBluetooth()
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION,
                AudioManager.VOLUME_CHANGED_ACTION -> refreshExtraControls()
                WifiManager.WIFI_STATE_CHANGED_ACTION,
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    refreshInternet()
                    refreshDataUsage()
                }
                Intent.ACTION_BATTERY_CHANGED -> refreshBatteryControl(intent)
            }
        }
    }

    private val fpsRunnable = object : Runnable {
        override fun run() {
            if (listening && controlBindings.containsKey(InvControlId.FPS_INFO)) {
                refreshFpsControl()
                handler.postDelayed(this, 1000L)
            }
        }
    }

    fun bindHost(host: QSHost?) {
        qsHost = host
    }

    @JvmOverloads
    fun rebuild(
        controlsHost: ViewGroup,
        editMode: Boolean,
        maxRows: Int = Int.MAX_VALUE,
        maxItems: Int = Int.MAX_VALUE,
        onConfigChanged: () -> Unit
    ) {
        clearTileCallbacks()
        controlBindings.clear()
        customImageView = null
        customNameTitle = null
        customNameSubtitle = null

        val cols = config.computeAutoColumns(controlsHost.width)
        lastBuiltCols = cols
        val cell = context.resources.getDimensionPixelSize(R.dimen.inv_qs_control_grid_cell_size)
        val gap = context.resources.getDimensionPixelSize(R.dimen.inv_qs_control_grid_item_spacing)
        val halfGap = gap / 2
        val showLabels = config.showLabels
        val circleTileSize = context.resources.getDimensionPixelSize(R.dimen.inv_qs_native_tile_size)
            .coerceAtMost(cell)
            .coerceAtLeast(36.dp())

        if (controlsHost.getTag(R.id.controls_host) == null) {
            val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val newCols = config.computeAutoColumns(v.width)
                if (v.width > 0 && newCols != lastBuiltCols) {
                    v.post { onConfigChanged() }
                }
            }
            controlsHost.addOnLayoutChangeListener(listener)
            controlsHost.setTag(R.id.controls_host, listener)
        }

        var grid = controlsHost.getChildAt(0) as? GridLayout
        if (grid == null) {
            controlsHost.removeAllViews()
            grid = GridLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                useDefaultMargins = false
            }
            controlsHost.addView(
                grid,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        } else if (editMode && grid.childCount > 0) {
            InvGridAnimator.beginBouncyGridTransition(grid)
        }

        val hostTilesBySpec = qsHost?.tiles?.associateBy { it.tileSpec }.orEmpty()
        val hostSpecs = hostTilesBySpec.keys.toList()
        val allItems = config.getUnifiedItems(hostSpecs).filter { token ->
            InvPanelConfig.parseControlId(token) != null ||
                hostTilesBySpec.containsKey(InvPanelConfig.parseTileSpec(token))
        }
        val items = if (maxItems < allItems.size) allItems.take(maxItems.coerceAtLeast(1)) else allItems
        val placed = InvGridPacker.pack(
            items = items,
            cols = cols,
            maxRows = maxRows,
            shrinkHeightToFit = maxRows < Int.MAX_VALUE && !editMode
        ) { token ->
            val sz = config.getUnifiedItemSize(token, cols)
            sz.rows.coerceIn(1, maxRows.coerceAtLeast(1)) to sz.cols.coerceIn(1, cols)
        }

        val existingBySignature = LinkedHashMap<String, View>()
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            val sig = child.getTag(R.id.inv_grid_item_signature) as? String
            if (sig != null && !existingBySignature.containsKey(sig)) {
                existingBySignature[sig] = child
            }
        }

        val desiredSignatures = placed.map { placedItem ->
            unifiedViewSignature(placedItem, editMode, showLabels)
        }.toSet()

        // Remove only obsolete or changed-template views so unchanged neighbors animate smoothly.
        for (i in grid.childCount - 1 downTo 0) {
            val child = grid.getChildAt(i)
            val sig = child.getTag(R.id.inv_grid_item_signature) as? String
            if (sig == null || !desiredSignatures.contains(sig)) {
                grid.removeViewAt(i)
                existingBySignature.remove(sig)
            }
        }

        val targetRowCount = if (placed.isNotEmpty()) placed.maxOf { it.row + it.rowSpan } else 1
        // Expand grid counts BEFORE applying/adding children, and only shrink AFTER updating child LayoutParams.
        if (cols > grid.columnCount) {
            grid.columnCount = cols
        }
        if (targetRowCount > grid.rowCount) {
            grid.rowCount = targetRowCount
        }

        sliders.clear()
        val justResized = lastResizedToken
        lastResizedToken = null

        for (item in placed) {
            val token = item.data
            val signature = unifiedViewSignature(item, editMode, showLabels)
            val reused = existingBySignature.remove(signature)
            val view = if (reused != null) {
                rebindExistingUnifiedView(reused, item, editMode, showLabels, circleTileSize, hostTilesBySpec)
                reused
            } else {
                createUnifiedView(item, grid, editMode, showLabels, circleTileSize, hostTilesBySpec)?.also { created ->
                    created.setTag(R.id.inv_grid_item_signature, signature)
                } ?: continue
            }
            view.setTag(R.id.inv_grid_item_spec, token)

            val isLabeledSmallTile = InvPanelConfig.parseTileSpec(token) != null && item.colSpan < 2 && showLabels
            val rowSpec = if (item.rowSpan == 1 && showLabels) {
                GridLayout.spec(item.row, item.rowSpan, GridLayout.TOP)
            } else {
                GridLayout.spec(item.row, item.rowSpan)
            }
            val lp = GridLayout.LayoutParams(
                rowSpec,
                GridLayout.spec(item.col, item.colSpan, item.colSpan.toFloat())
            ).apply {
                width = 0
                height = if (isLabeledSmallTile) {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                } else {
                    cell * item.rowSpan + gap * (item.rowSpan - 1)
                }
                setMargins(
                    if (item.col > 0) halfGap else 0,
                    if (item.row > 0) halfGap else 0,
                    if (item.col + item.colSpan < cols) halfGap else 0,
                    halfGap
                )
            }
            attachEditCapabilities(view, grid, item, cols, cell, editMode, onConfigChanged)

            if (reused != null) {
                view.layoutParams = lp
            } else {
                grid.addView(view, lp)
                if (justResized == token) {
                    InvGridAnimator.springPopIn(view)
                }
            }
        }

        grid.columnCount = cols
        grid.rowCount = targetRowCount

        refreshDynamicValues()
    }

    private fun unifiedViewSignature(
        item: InvGridPacker.PlacedItem<String>,
        editMode: Boolean,
        showLabels: Boolean
    ): String {
        val token = item.data
        val ctrl = InvPanelConfig.parseControlId(token)
        if (ctrl != null) {
            return when (ctrl) {
                InvControlId.MEDIA,
                InvControlId.CUSTOM_IMAGE,
                InvControlId.BRIGHTNESS_H,
                InvControlId.VOLUME_H,
                InvControlId.BRIGHTNESS_V,
                InvControlId.VOLUME_V,
                InvControlId.CUSTOM_NAME -> "$token:$editMode"
                else -> "$token:${if (item.colSpan >= 2) "pill" else "circle"}:$editMode"
            }
        }
        return "$token:${if (item.colSpan >= 2) "pill" else "circle"}:$editMode:$showLabels"
    }

    /**
     * Reflows all unified items inside [grid] IN-PLACE during an active drag gesture
     * without detaching any view, so live size changes & neighbor movement happen immediately
     * before lifting the finger.
     */
    private fun reflowLiveItemsInPlace(
        grid: GridLayout,
        cols: Int,
        cellHeight: Int
    ) {
        val gap = context.resources.getDimensionPixelSize(R.dimen.inv_qs_control_grid_item_spacing)
        val halfGap = gap / 2
        val showLabels = config.showLabels
        val hostTilesBySpec = qsHost?.tiles?.associateBy { it.tileSpec }.orEmpty()
        val items = config.getUnifiedItems(hostTilesBySpec.keys.toList()).filter { token ->
            InvPanelConfig.parseControlId(token) != null ||
                hostTilesBySpec.containsKey(InvPanelConfig.parseTileSpec(token))
        }
        val placed = InvGridPacker.pack(items, cols, Int.MAX_VALUE) { token ->
            val sz = config.getUnifiedItemSize(token, cols)
            sz.rows.coerceIn(1, 2) to sz.cols.coerceIn(1, cols)
        }
        val targetRowCount = if (placed.isNotEmpty()) placed.maxOf { it.row + it.rowSpan } else 1

        InvGridAnimator.beginBouncyGridTransition(grid)
        if (cols > grid.columnCount) grid.columnCount = cols
        if (targetRowCount > grid.rowCount) grid.rowCount = targetRowCount

        val viewByToken = HashMap<String, View>()
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            val tok = child.getTag(R.id.inv_grid_item_spec) as? String
            if (tok != null) viewByToken[tok] = child
        }

        for (item in placed) {
            val view = viewByToken[item.data] ?: continue
            if (InvPanelConfig.parseControlId(item.data) == InvControlId.MEDIA) {
                media.bind(
                    view,
                    item.rowSpan,
                    item.colSpan,
                    editMode = true,
                    square = config.isTileSquare(item.data)
                )
            }
            val isLabeledSmallTile = InvPanelConfig.parseTileSpec(item.data) != null && item.colSpan < 2 && showLabels
            val rowSpec = if (item.rowSpan == 1 && showLabels) {
                GridLayout.spec(item.row, item.rowSpan, GridLayout.TOP)
            } else {
                GridLayout.spec(item.row, item.rowSpan)
            }
            view.layoutParams = GridLayout.LayoutParams(
                rowSpec,
                GridLayout.spec(item.col, item.colSpan, item.colSpan.toFloat())
            ).apply {
                width = 0
                height = if (isLabeledSmallTile) {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                } else {
                    cellHeight * item.rowSpan + gap * (item.rowSpan - 1)
                }
                setMargins(
                    if (item.col > 0) halfGap else 0,
                    if (item.row > 0) halfGap else 0,
                    if (item.col + item.colSpan < cols) halfGap else 0,
                    halfGap
                )
            }
        }

        grid.columnCount = cols
        grid.rowCount = targetRowCount
    }

    fun startListening() {
        if (listening) return
        listening = true
        sliders.startListening()
        media.startListening()
        tileHolders.forEach { it.tile.setListening(this, true) }
        bluetoothController.addCallback(bluetoothCallback)
        try {
            connectivityManager?.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                networkCallback
            )
        } catch (_: Throwable) {
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(AudioManager.VOLUME_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val stickyBattery = context.registerReceiver(systemReceiver, filter)
        stickyBattery?.let { refreshBatteryControl(it) }
        handler.removeCallbacks(fpsRunnable)
        if (controlBindings.containsKey(InvControlId.FPS_INFO)) {
            handler.post(fpsRunnable)
        }
        refreshDynamicValues()
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        handler.removeCallbacks(fpsRunnable)
        sliders.stopListening()
        media.stopListening()
        tileHolders.forEach { it.tile.setListening(this, false) }
        bluetoothController.removeCallback(bluetoothCallback)
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }
        try {
            context.unregisterReceiver(systemReceiver)
        } catch (_: Throwable) {
        }
    }

    private fun clearTileCallbacks() {
        tileHolders.forEach {
            try {
                it.tile.removeCallback(it.callback)
                it.tile.setListening(this, false)
            } catch (_: Throwable) {
            }
        }
        tileHolders.clear()
    }

    fun refreshDynamicValues() {
        refreshInternet()
        refreshBluetooth()
        refreshDataUsage()
        refreshCustomBlocks()
        refreshExtraControls()
        refreshFpsControl()
        sliders.refreshAll()
    }

    private fun bindControlView(
        view: View,
        controlId: InvControlId,
        item: InvGridPacker.PlacedItem<String>,
        editMode: Boolean,
        tileSize: Int
    ) {
        val square = config.isTileSquare(item.data)
        when (controlId) {
            InvControlId.MEDIA ->
                media.bind(view, item.rowSpan, item.colSpan, editMode = editMode, square = square)
            InvControlId.BRIGHTNESS_V ->
                sliders.bindBrightness(view, vertical = true, editMode = editMode, square = square)
            InvControlId.VOLUME_V ->
                sliders.bindVolume(view, vertical = true, editMode = editMode, square = square)
            InvControlId.BRIGHTNESS_H ->
                sliders.bindBrightness(view, vertical = false, editMode = editMode, square = square)
            InvControlId.VOLUME_H ->
                sliders.bindVolume(view, vertical = false, editMode = editMode, square = square)
            InvControlId.CUSTOM_NAME -> {
                customNameTitle = view.findViewById(R.id.custom_name_title)
                customNameSubtitle = view.findViewById(R.id.custom_name_subtitle)
                view.findViewById<View>(R.id.custom_name_body)?.setBackgroundResource(
                    if (square) R.drawable.inv_bg_pill_square else R.drawable.inv_bg_pill
                )
                if (!editMode) {
                    view.isClickable = true
                    view.setOnClickListener { openCustomControlsSettings() }
                } else {
                    view.setOnClickListener(null)
                }
            }
            InvControlId.CUSTOM_IMAGE -> {
                customImageView = view.findViewById(R.id.custom_image_view)
                val imgBg = if (square) R.drawable.inv_bg_media_square else R.drawable.inv_bg_media
                view.findViewById<View>(R.id.custom_image_body)?.setBackgroundResource(imgBg)
                customImageView?.setBackgroundResource(imgBg)
                if (!editMode) {
                    view.isClickable = true
                    view.setOnClickListener { openCustomControlsSettings() }
                } else {
                    view.setOnClickListener(null)
                }
            }
            else -> bindToggleableControl(view, controlId, item.colSpan, editMode, tileSize)
        }
    }

    private fun rebindExistingUnifiedView(
        view: View,
        item: InvGridPacker.PlacedItem<String>,
        editMode: Boolean,
        showLabels: Boolean,
        tileSize: Int,
        hostTilesBySpec: Map<String, QSTile>
    ) {
        val controlId = InvPanelConfig.parseControlId(item.data)
        if (controlId == null) {
            val spec = InvPanelConfig.parseTileSpec(item.data) ?: return
            val tile = hostTilesBySpec[spec] ?: return
            bindUnifiedTile(view, tile, item.colSpan, editMode, showLabels, tileSize)
            return
        }
        bindControlView(view, controlId, item, editMode, tileSize)
    }

    private fun createUnifiedView(
        item: InvGridPacker.PlacedItem<String>,
        parent: ViewGroup,
        editMode: Boolean,
        showLabels: Boolean,
        tileSize: Int,
        hostTilesBySpec: Map<String, QSTile>
    ): View? {
        val controlId = InvPanelConfig.parseControlId(item.data)
        if (controlId == null) {
            val spec = InvPanelConfig.parseTileSpec(item.data) ?: return null
            val tile = hostTilesBySpec[spec] ?: return null
            val layoutRes = if (item.colSpan >= 2) R.layout.inv_item_pill else R.layout.inv_item_tile
            val view = inflater.inflate(layoutRes, parent, false)
            bindUnifiedTile(view, tile, item.colSpan, editMode, showLabels, tileSize)
            return view
        }
        val layoutRes = when (controlId) {
            InvControlId.MEDIA -> R.layout.inv_item_media
            InvControlId.BRIGHTNESS_V, InvControlId.VOLUME_V -> R.layout.inv_item_slider_vertical
            InvControlId.BRIGHTNESS_H, InvControlId.VOLUME_H -> R.layout.inv_item_slider_horizontal
            InvControlId.CUSTOM_NAME -> R.layout.inv_item_custom_name
            InvControlId.CUSTOM_IMAGE -> R.layout.inv_item_custom_image
            else -> if (item.colSpan >= 2) R.layout.inv_item_pill else R.layout.inv_item_control_circle
        }
        val view = inflater.inflate(layoutRes, parent, false)
        bindControlView(view, controlId, item, editMode, tileSize)
        return view
    }

    private fun bindUnifiedTile(
        itemView: View,
        tile: QSTile,
        colSpan: Int,
        editMode: Boolean,
        showLabels: Boolean,
        tileSize: Int
    ) {
        val isWide = colSpan >= 2
        val bodyView: View
        val clickTarget: View
        val icon: ImageView
        val label: TextView
        val summary: TextView?

        if (isWide) {
            val pillRoot = itemView as LaunchableFrameLayout
            pillRoot.isClickable = !editMode
            pillRoot.isFocusable = !editMode
            val pillBody = pillRoot.findViewById<View>(R.id.pill_body).apply {
                isClickable = false
                isFocusable = false
            }
            bodyView = pillBody
            clickTarget = pillRoot
            icon = pillRoot.findViewById(R.id.pill_icon)
            label = pillRoot.findViewById(R.id.pill_label)
            summary = pillRoot.findViewById(R.id.pill_summary)
        } else {
            val circle = itemView.findViewById<View>(R.id.tile_circle).apply {
                layoutParams = layoutParams.apply {
                    width = tileSize
                    height = tileSize
                }
                isClickable = !editMode
                isFocusable = !editMode
                isLongClickable = !editMode
            }
            (circle.parent as? View)?.let { slotFrame ->
                slotFrame.layoutParams = slotFrame.layoutParams.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = tileSize
                }
            }
            bodyView = circle
            clickTarget = circle
            icon = itemView.findViewById(R.id.tile_icon)
            label = itemView.findViewById(R.id.tile_label)
            summary = null
            label.visibility = if (showLabels) View.VISIBLE else View.GONE
        }

        val iconPx = (tileSize * (if (isWide) 0.35f else 0.40f)).roundToInt().coerceIn(20.dp(), 28.dp())
        icon.layoutParams = icon.layoutParams.apply {
            width = iconPx
            height = iconPx
        }

        val cb = QSTile.Callback { state ->
            handler.post { renderTileState(tile, state, bodyView, icon, label, summary, isWide, config.isTileSquare(tile.tileSpec)) }
        }
        tileHolders.add(TileHolder(tile, cb))
        tile.addCallback(cb)
        tile.setListening(this, listening)
        renderTileState(tile, tile.state, bodyView, icon, label, summary, isWide, config.isTileSquare(tile.tileSpec))

        if (editMode) {
            clickTarget.setOnClickListener(null)
            clickTarget.setOnLongClickListener(null)
        } else {
            clickTarget.setOnClickListener { v -> tile.click(v) }
            clickTarget.setOnLongClickListener { v ->
                tile.longClick(v)
                true
            }
        }
    }

    private fun renderTileState(
        tile: QSTile,
        state: QSTile.State,
        bodyView: View,
        iconView: ImageView,
        labelView: TextView,
        summaryView: TextView?,
        isWide: Boolean,
        square: Boolean
    ) {
        val tileState = state.state
        val isActive = tileState == Tile.STATE_ACTIVE
        val isUnavailable = tileState == Tile.STATE_UNAVAILABLE
        val labelText = state.label?.toString() ?: tile.tileSpec
        val secondaryText = if (summaryView != null) {
            state.secondaryLabel?.toString()?.takeIf { it.isNotBlank() }
                ?: when (tileState) {
                    Tile.STATE_ACTIVE -> "On"
                    Tile.STATE_UNAVAILABLE -> "Unavailable"
                    else -> "Off"
                }
        } else null
        val renderKey = "${tile.tileSpec}:${tileState}:${isWide}:$square:$labelText:$secondaryText:${state.icon}"
        if (bodyView.getTag(R.id.inv_grid_item_signature) == renderKey) {
            return
        }
        bodyView.setTag(R.id.inv_grid_item_signature, renderKey)

        if (isWide) {
            val bgRes = if (isActive) {
                if (square) R.drawable.inv_bg_pill_on_square else R.drawable.inv_bg_pill_on
            } else {
                if (square) R.drawable.inv_bg_pill_square else R.drawable.inv_bg_pill
            }
            bodyView.setBackgroundResource(bgRes)
            bodyView.alpha = if (isUnavailable) 0.55f else 1.0f
        } else {
            val bgRes = when (tileState) {
                Tile.STATE_ACTIVE ->
                    if (square) R.drawable.inv_bg_tile_on_square else R.drawable.inv_bg_tile_on
                Tile.STATE_UNAVAILABLE ->
                    if (square) R.drawable.inv_bg_tile_unavailable_square else R.drawable.inv_bg_tile_unavailable
                else ->
                    if (square) R.drawable.inv_bg_tile_off_square else R.drawable.inv_bg_tile_off
            }
            bodyView.setBackgroundResource(bgRes)
            bodyView.alpha = if (isUnavailable) 0.55f else 1.0f
        }

        val iconColor = ContextCompat.getColor(
            context,
            when (tileState) {
                Tile.STATE_ACTIVE -> R.color.inv_icon_on_accent
                Tile.STATE_UNAVAILABLE -> R.color.inv_icon_unavailable
                else -> R.color.inv_icon_primary
            }
        )
        val labelColor = ContextCompat.getColor(
            context,
            when {
                isWide && isActive -> R.color.inv_text_on_accent
                isWide -> R.color.inv_text_primary
                isActive -> R.color.inv_text_primary
                else -> R.color.inv_text_secondary
            }
        )

        val drawable = state.icon?.getDrawable(context)
            ?: state.iconSupplier?.get()?.getDrawable(context)
        if (drawable != null) {
            val mutated = drawable.mutate()
            mutated.setTintList(ColorStateList.valueOf(iconColor))
            iconView.setImageDrawable(mutated)
            iconView.imageTintList = ColorStateList.valueOf(iconColor)
        }
        labelView.text = labelText
        labelView.setTextColor(labelColor)

        if (summaryView != null) {
            summaryView.text = secondaryText
            summaryView.visibility = View.VISIBLE
            summaryView.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isActive) R.color.inv_text_on_accent else R.color.inv_text_secondary
                )
            )
        }
    }

    private fun bindToggleableControl(
        view: View,
        controlId: InvControlId,
        colSpan: Int,
        editMode: Boolean,
        tileSize: Int
    ) {
        val squareKey = InvPanelConfig.controlToken(controlId)
        val surface = if (colSpan >= 2) {
            val root = view as LaunchableFrameLayout
            val body = root.findViewById<View>(R.id.pill_body)
            val icon = root.findViewById<ImageView>(R.id.pill_icon)
            val label = root.findViewById<TextView>(R.id.pill_label)
            val summary = root.findViewById<TextView>(R.id.pill_summary)
            root.isClickable = !editMode
            root.isFocusable = !editMode
            body?.isClickable = false
            body?.isFocusable = false
            body?.isLongClickable = false
            ControlSurfaceBinding(pill = PillBinding(root, body, icon, label, summary), squareKey = squareKey)
        } else {
            val launchBody = view.findViewById<LaunchableFrameLayout>(R.id.circle_body)
            (launchBody.layoutParams as? FrameLayout.LayoutParams)?.let { clp ->
                clp.width = tileSize
                clp.height = tileSize
                clp.gravity = Gravity.CENTER
                launchBody.layoutParams = clp
            }
            val icon = view.findViewById<ImageView>(R.id.circle_icon)
            launchBody.isClickable = !editMode
            launchBody.isFocusable = !editMode
            launchBody.isLongClickable = !editMode
            ControlSurfaceBinding(circle = CircleBinding(launchBody, icon), squareKey = squareKey)
        }

        controlBindings[controlId] = surface
        val target = surface.actionView ?: return

        if (editMode) {
            surface.apply(
                active = false,
                iconRes = controlId.iconRes,
                title = controlId.title,
                subtitle = if (colSpan >= 2) "2×1" else "1×1",
                showSubtitle = false
            )
            target.setOnClickListener(null)
            target.setOnLongClickListener(null)
            return
        }

        when (controlId) {
            InvControlId.INTERNET -> {
                target.setOnClickListener {
                    val launchView = target as? LaunchableFrameLayout ?: target
                    try {
                        internetDialogFactory.create(
                            aboveStatusBar = true,
                            canConfigMobileData = true,
                            canConfigWifi = true,
                            view = launchView
                        )
                    } catch (_: Throwable) {
                        activityStarter.postStartActivityDismissingKeyguard(
                            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), 0
                        )
                    }
                }
                target.setOnLongClickListener {
                    activityStarter.postStartActivityDismissingKeyguard(
                        Intent(Settings.ACTION_WIFI_SETTINGS), 0
                    )
                    true
                }
            }
            InvControlId.BLUETOOTH -> {
                target.setOnClickListener { toggleBluetooth(target) }
                target.setOnLongClickListener {
                    try {
                        bluetoothDialogFactory.create(true, target)
                    } catch (_: Throwable) {
                        activityStarter.postStartActivityDismissingKeyguard(
                            Intent(Settings.ACTION_BLUETOOTH_SETTINGS), 0
                        )
                    }
                    true
                }
            }
            InvControlId.DATA_USAGE -> target.setOnClickListener {
                activityStarter.postStartActivityDismissingKeyguard(
                    Intent(Settings.ACTION_DATA_USAGE_SETTINGS), 0
                )
            }
            InvControlId.BATTERY_VIEW -> target.setOnClickListener {
                activityStarter.postStartActivityDismissingKeyguard(
                    Intent(Intent.ACTION_POWER_USAGE_SUMMARY), 0
                )
            }
            InvControlId.AUTO_BRIGHTNESS -> target.setOnClickListener { toggleAutoBrightness() }
            InvControlId.MUTE -> target.setOnClickListener { toggleMute() }
            InvControlId.RINGER -> target.setOnClickListener { cycleRingerMode() }
            InvControlId.FPS_INFO -> target.setOnClickListener { refreshFpsControl() }
            else -> {}
        }
    }

    private fun toggleBluetooth(anchorView: View) {
        val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        val curEnabled = adapter?.isEnabled ?: bluetoothController.isBluetoothEnabled
        val nextEnabled = !curEnabled
        bluetoothTransientPending = nextEnabled

        val btTile = qsHost?.tiles?.firstOrNull { it.tileSpec == "bt" }
        if (btTile != null) {
            btTile.click(anchorView)
        } else {
            bluetoothController.setBluetoothEnabled(nextEnabled)
            try {
                if (nextEnabled) adapter?.enable() else adapter?.disable()
            } catch (_: SecurityException) {
            }
        }
        refreshBluetooth()
        handler.postDelayed({
            bluetoothTransientPending = null
            refreshBluetooth()
        }, 1500L)
    }

    private fun attachEditCapabilities(
        view: View,
        grid: GridLayout,
        item: InvGridPacker.PlacedItem<String>,
        cols: Int,
        cellHeight: Int,
        editMode: Boolean,
        onConfigChanged: () -> Unit
    ) {
        val token = item.data
        val controlId = InvPanelConfig.parseControlId(token)
        val tileSpec = InvPanelConfig.parseTileSpec(token)
        val badge = view.findViewById<View>(R.id.badge)
        val mediaHandle = view.findViewById<View>(R.id.media_resize_handle)
        val tileHandle = view.findViewById<View>(R.id.tile_resize_handle)

        // Proportional icon & badge scaling relative to cell size
        val density = context.resources.displayMetrics.density
        val badgePx = (cellHeight * 0.31f).roundToInt().coerceIn((20 * density).roundToInt(), (24 * density).roundToInt())
        badge?.layoutParams?.let { blp ->
            if (blp.width != badgePx || blp.height != badgePx) {
                blp.width = badgePx
                blp.height = badgePx
                badge.layoutParams = blp
            }
        }
        val handleH = (cellHeight * 0.48f).roundToInt().coerceIn((28 * density).roundToInt(), (38 * density).roundToInt())
        tileHandle?.layoutParams?.let { hlp ->
            if (hlp.height != handleH) {
                hlp.height = handleH
                tileHandle.layoutParams = hlp
            }
        }
        val circleIcon = view.findViewById<ImageView>(R.id.circle_icon)
        val pillIcon = view.findViewById<ImageView>(R.id.pill_icon)
        val targetCtrlIconPx = (cellHeight * 0.36f).roundToInt()
            .coerceIn((22 * density).roundToInt(), (28 * density).roundToInt())
        listOf(circleIcon, pillIcon).forEach { iv ->
            iv?.layoutParams?.let { ilp ->
                if (ilp.width != targetCtrlIconPx || ilp.height != targetCtrlIconPx) {
                    ilp.width = targetCtrlIconPx
                    ilp.height = targetCtrlIconPx
                    iv.layoutParams = ilp
                }
            }
        }

        val isToggleableControl = controlId != null &&
            controlId != InvControlId.MEDIA &&
            controlId != InvControlId.BRIGHTNESS_H &&
            controlId != InvControlId.VOLUME_H &&
            controlId != InvControlId.BRIGHTNESS_V &&
            controlId != InvControlId.VOLUME_V &&
            controlId != InvControlId.CUSTOM_IMAGE &&
            controlId != InvControlId.CUSTOM_NAME

        if (!editMode) {
            badge?.visibility = View.GONE
            mediaHandle?.visibility = View.GONE
            tileHandle?.visibility = View.GONE
            view.setOnDragListener(null)
            val usesRootInNormalMode = controlId == InvControlId.CUSTOM_NAME ||
                controlId == InvControlId.CUSTOM_IMAGE ||
                (item.colSpan >= 2 && (tileSpec != null || isToggleableControl))
            if (!usesRootInNormalMode) {
                view.setOnClickListener(null)
                view.isClickable = false
                view.setOnLongClickListener(null)
                view.isLongClickable = false
            }
            return
        }

        badge?.visibility = View.VISIBLE
        badge?.setOnClickListener {
            if (!config.removeUnifiedItem(token)) {
                Toast.makeText(context, "Minimal 1 item harus aktif", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onConfigChanged()
        }

        val squareKey = tileSpec ?: controlId?.let { InvPanelConfig.controlToken(it) }

        val dragStarter = View.OnLongClickListener { _ ->
            if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
                return@OnLongClickListener false
            }
            try {
                view.startDragAndDrop(
                    ClipData.newPlainText("inv_unified", token),
                    View.DragShadowBuilder(view),
                    UnifiedDragPayload(token),
                    0
                )
            } catch (_: Throwable) {
                false
            }
        }
        view.setOnLongClickListener(dragStarter)

        // Ensure inner surface views don't intercept single taps or long clicks in edit mode
        // so both double-tap corner toggling and long-press drag route cleanly through `view`.
        listOf(
            R.id.pill_body,
            R.id.circle_body,
            R.id.tile_circle,
            R.id.slider_body,
            R.id.media_body,
            R.id.custom_image_body,
            R.id.custom_name_body
        ).forEach { id ->
            view.findViewById<View>(id)?.apply {
                setOnClickListener(null)
                setOnLongClickListener(null)
                isClickable = false
                isLongClickable = false
                isFocusable = false
            }
        }

        if (squareKey != null) {
            view.setOnClickListener(object : View.OnClickListener {
                private var lastTapAt = 0L
                override fun onClick(v: View) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTapAt <= 380L) {
                        lastTapAt = 0L
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        config.toggleTileSquare(squareKey)
                        InvGridAnimator.springPopIn(v)
                        onConfigChanged()
                    } else {
                        lastTapAt = now
                    }
                }
            })
        } else {
            view.setOnClickListener(null)
            view.isClickable = false
        }

        if (controlId != null) {
            val allowedSizes = config.getAllowedControlSizes(controlId, cols)
            if (controlId == InvControlId.MEDIA || controlId == InvControlId.CUSTOM_IMAGE) {
                mediaHandle?.visibility = View.VISIBLE
                attachCardDragResize(mediaHandle, view, grid, token, controlId, cols, cellHeight, onConfigChanged)
            } else if (allowedSizes.size > 1) {
                tileHandle?.visibility = View.VISIBLE
                attachControlDragResize(tileHandle, view, grid, token, controlId, item, cols, cellHeight, allowedSizes, onConfigChanged)
            } else {
                tileHandle?.visibility = View.GONE
            }
        } else if (tileSpec != null && cols >= 2) {
            tileHandle?.visibility = View.VISIBLE
            attachTileDragResize(tileHandle, view, grid, token, tileSpec, item.colSpan, cols, cellHeight, onConfigChanged)
        } else {
            tileHandle?.visibility = View.GONE
        }

        view.setOnDragListener { target, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_ENTERED -> {
                    InvGridAnimator.springScale(target, 0.94f, 0.94f, dampingRatio = 0.6f, stiffness = 460f)
                    target.alpha = 0.75f
                    true
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    InvGridAnimator.springScale(target, 1f, 1f)
                    target.alpha = 1.0f
                    true
                }
                DragEvent.ACTION_DROP -> {
                    InvGridAnimator.springScale(target, 1f, 1f)
                    target.alpha = 1.0f
                    val payload = event.localState as? UnifiedDragPayload
                        ?: return@setOnDragListener false
                    val list = config.getUnifiedItems()
                    val fromIndex = list.indexOf(payload.token)
                    val toIndex = list.indexOf(token)
                    if (fromIndex != -1 && toIndex != -1 && fromIndex != toIndex) {
                        config.moveUnifiedItem(fromIndex, toIndex)
                        onConfigChanged()
                    }
                    true
                }
                DragEvent.ACTION_DRAG_STARTED -> event.localState is UnifiedDragPayload
                else -> true
            }
        }
    }

    private fun attachTileDragResize(
        handle: View?,
        tileView: View,
        grid: GridLayout,
        token: String,
        tileSpec: String,
        currentColSpan: Int,
        maxCols: Int,
        cellHeight: Int,
        onConfigChanged: () -> Unit
    ) {
        if (handle == null) return
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var dragging = false
        var pendingSpan = currentColSpan
        var stepW = cellHeight.toFloat()

        handle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    downX = event.rawX
                    dragging = false
                    pendingSpan = currentColSpan
                    stepW = (tileView.width.takeIf { it > 0 }?.toFloat()
                        ?.div(currentColSpan.coerceAtLeast(1)) ?: cellHeight.toFloat()).coerceAtLeast(48f)
                    tileView.pivotX = 0f
                    tileView.pivotY = tileView.height / 2f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    if (!dragging && abs(dx) > touchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        val threshold = (stepW * 0.35f).coerceAtLeast(touchSlop * 2f)
                        val candidate = if (currentColSpan == 1) {
                            when {
                                dx >= threshold && maxCols >= 2 -> 2
                                dx < (threshold * 0.45f) -> 1
                                else -> pendingSpan
                            }
                        } else {
                            when {
                                dx <= -threshold -> 1
                                dx > -(threshold * 0.45f) -> 2
                                else -> pendingSpan
                            }
                        }
                        if (candidate != pendingSpan) {
                            pendingSpan = candidate
                            config.setTileColSpan(tileSpec, candidate)
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            reflowLiveItemsInPlace(grid, maxCols, cellHeight)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    InvGridAnimator.springScale(tileView, 1f, 1f)
                    val nextSpan = if (!dragging) {
                        config.toggleTileColSpan(tileSpec, maxCols)
                    } else {
                        pendingSpan
                    }
                    if (nextSpan != currentColSpan) {
                        lastResizedToken = token
                        onConfigChanged()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    InvGridAnimator.springScale(tileView, 1f, 1f)
                    true
                }
                else -> false
            }
        }
    }

    private fun attachControlDragResize(
        handle: View?,
        controlView: View,
        grid: GridLayout,
        token: String,
        controlId: InvControlId,
        item: InvGridPacker.PlacedItem<String>,
        cols: Int,
        cellHeight: Int,
        allowedSizes: List<GridSize>,
        onConfigChanged: () -> Unit
    ) {
        if (handle == null) return
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val minCols = allowedSizes.minOf { it.cols }
        val maxColsAllowed = allowedSizes.maxOf { it.cols }.coerceAtMost(cols)

        var downX = 0f
        var dragging = false
        var baseUnitW = cellHeight.toFloat()
        var startSize = GridSize(item.rowSpan, item.colSpan)
        var currentSize = startSize

        handle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    downX = event.rawX
                    dragging = false
                    startSize = config.getControlSize(controlId, cols)
                    currentSize = startSize
                    baseUnitW = (controlView.width.takeIf { it > 0 }?.toFloat()
                        ?.div(startSize.cols.coerceAtLeast(1)) ?: cellHeight.toFloat()).coerceAtLeast(48f)
                    controlView.pivotX = 0f
                    controlView.pivotY = controlView.height / 2f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    if (!dragging && abs(dx) > touchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        val targetCols = (startSize.cols + (dx / (baseUnitW * 0.45f)).roundToInt())
                            .coerceIn(minCols, maxColsAllowed)
                        val candidate = allowedSizes.minByOrNull { abs(it.cols - targetCols) } ?: startSize
                        if (candidate != currentSize) {
                            currentSize = candidate
                            config.setControlSize(controlId, candidate)
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            reflowLiveItemsInPlace(grid, cols, cellHeight)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    InvGridAnimator.springScale(controlView, 1f, 1f)
                    val finalSize = if (!dragging) {
                        config.cycleControlSize(controlId, cols)
                    } else {
                        currentSize
                    }
                    if (finalSize != startSize) {
                        lastResizedToken = token
                        Toast.makeText(
                            context,
                            "${controlId.title}: ${finalSize.cols}×${finalSize.rows}",
                            Toast.LENGTH_SHORT
                        ).show()
                        onConfigChanged()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    InvGridAnimator.springScale(controlView, 1f, 1f)
                    true
                }
                else -> false
            }
        }
    }

    private fun attachCardDragResize(
        handle: View?,
        cardView: View,
        grid: GridLayout,
        token: String,
        controlId: InvControlId,
        cols: Int,
        cellHeight: Int,
        onConfigChanged: () -> Unit
    ) {
        if (handle == null) return
        val isMedia = controlId == InvControlId.MEDIA
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val maxCardCols = cols.coerceIn(2, 4)

        var downX = 0f
        var downY = 0f
        var dragging = false
        var baseStepW = cellHeight.toFloat()
        val baseStepH = cellHeight.toFloat().coerceAtLeast(48f)
        var startSize = if (isMedia) config.getMediaSize() else config.getCustomImageSize()
        var currentSize = startSize

        handle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    startSize = if (isMedia) config.getMediaSize() else config.getCustomImageSize()
                    currentSize = startSize
                    baseStepW = (cardView.width.takeIf { it > 0 }?.toFloat()
                        ?.div(startSize.cols.coerceAtLeast(1)) ?: cellHeight.toFloat()).coerceAtLeast(48f)
                    cardView.pivotX = 0f
                    cardView.pivotY = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val targetRows = (startSize.rows + (dy / (baseStepH * 0.6f)).roundToInt())
                            .coerceIn(1, 2)
                        val targetCols = (startSize.cols.coerceAtMost(maxCardCols) +
                            (dx / (baseStepW * 0.5f)).roundToInt()).coerceIn(2, maxCardCols)
                        val candidate = GridSize(targetRows, targetCols)
                        if (candidate != currentSize) {
                            currentSize = candidate
                            if (isMedia) config.setMediaSize(candidate) else config.setCustomImageSize(candidate)
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            reflowLiveItemsInPlace(grid, cols, cellHeight)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    InvGridAnimator.springScale(cardView, 1f, 1f)
                    val appliedSize = if (!dragging) {
                        if (isMedia) config.cycleMediaSize() else config.cycleCustomImageSize()
                    } else {
                        currentSize
                    }
                    if (appliedSize != startSize) {
                        lastResizedToken = token
                        Toast.makeText(
                            context,
                            "${controlId.title}: ${appliedSize.label}",
                            Toast.LENGTH_SHORT
                        ).show()
                        onConfigChanged()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    InvGridAnimator.springScale(cardView, 1f, 1f)
                    true
                }
                else -> false
            }
        }
    }

    private fun openCustomControlsSettings() {
        val intent = Intent().setClassName(
            "com.android.settings",
            "com.android.settings.Settings\$SharpenerQuickSettingsActivity"
        )
        activityStarter.postStartActivityDismissingKeyguard(intent, 0)
    }

    private fun refreshInternet() {
        val binding = controlBindings[InvControlId.INTERNET] ?: return
        val activeNetwork = connectivityManager?.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val wifiEnabled = wifiManager?.isWifiEnabled == true
        val dataEnabled = try {
            telephonyManager?.isDataEnabled == true
        } catch (_: Throwable) {
            false
        }
        val connected = isWifi || isCell

        val title = when {
            isWifi -> {
                val clean = wifiManager?.connectionInfo?.ssid?.removeSurrounding("\"")
                if (!clean.isNullOrBlank() && clean != "<unknown ssid>") clean else "Wi-Fi"
            }
            isCell -> telephonyManager?.networkOperatorName?.takeIf { it.isNotBlank() } ?: "Mobile Data"
            else -> "Internet"
        }

        val subtitle = when {
            isWifi -> "Wi-Fi connected"
            isCell -> "Mobile data"
            wifiEnabled || dataEnabled -> "Not connected"
            else -> "Off"
        }

        val iconRes = when {
            isWifi -> R.drawable.inv_ic_wifi
            isCell -> R.drawable.inv_ic_mobile_signal
            else -> R.drawable.inv_ic_internet
        }
        binding.apply(connected || wifiEnabled, iconRes, title, subtitle)
    }

    private fun refreshBluetooth() {
        val binding = controlBindings[InvControlId.BLUETOOTH] ?: return
        val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        val realEnabled = adapter?.isEnabled ?: bluetoothController.isBluetoothEnabled
        val pending = bluetoothTransientPending
        val enabled = pending ?: realEnabled
        val connected = bluetoothController.isBluetoothConnected
        val deviceName = bluetoothController.connectedDeviceName
        val title = if (connected && !deviceName.isNullOrBlank()) deviceName else "Bluetooth"
        val subtitle = when {
            pending != null -> if (pending) "Turning on..." else "Turning off..."
            connected -> "Connected"
            enabled -> "On"
            else -> "Off"
        }
        binding.apply(enabled, R.drawable.inv_ic_bluetooth, title, subtitle)
    }

    private fun refreshDataUsage() {
        val binding = controlBindings[InvControlId.DATA_USAGE] ?: return
        binding.apply(cachedDataUsageActive, R.drawable.inv_ic_data_usage, "Data usage", cachedDataUsageSubtitle)

        val now = System.currentTimeMillis()
        if (now - lastDataUsageQueryMs < 5000L) return
        lastDataUsageQueryMs = now

        bgExecutor.execute {
            val activeNetwork = connectivityManager?.activeNetwork
            val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val info = try {
                if (isWifi) dataUsageController.wifiDataUsageInfo else dataUsageController.dailyDataUsageInfo
            } catch (_: Throwable) {
                null
            }
            val active = info != null
            val subtitle = if (info != null) {
                val prefix = if (isWifi) "Wi-Fi" else "Mobile"
                "$prefix · ${Formatter.formatShortFileSize(context, info.usageLevel)}"
            } else {
                "Unavailable"
            }
            cachedDataUsageActive = active
            cachedDataUsageSubtitle = subtitle
            handler.post {
                controlBindings[InvControlId.DATA_USAGE]?.apply(
                    active,
                    R.drawable.inv_ic_data_usage,
                    "Data usage",
                    subtitle
                )
            }
        }
    }

    private fun refreshBatteryControl(intent: Intent) {
        val binding = controlBindings[InvControlId.BATTERY_VIEW] ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level >= 0) (level * 100f / scale).roundToInt() else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val iconRes = if (charging) R.drawable.inv_ic_battery_charging else R.drawable.inv_ic_battery_view
        val subtitle = when {
            pct >= 0 && charging -> "$pct% · Charging"
            pct >= 0 -> "$pct%"
            else -> "Battery"
        }
        binding.apply(charging, iconRes, "Battery", subtitle)
    }

    private fun refreshCustomBlocks() {
        val resolver = context.contentResolver
        customNameTitle?.let {
            val user = Settings.System.getStringForUser(
                resolver, "invos_qs_custom_username", UserHandle.USER_CURRENT
            )?.takeIf { s -> s.isNotBlank() } ?: "ArrowOS"
            it.text = user
        }
        customNameSubtitle?.let {
            val sub = Settings.System.getStringForUser(
                resolver, "invos_qs_custom_subtitle", UserHandle.USER_CURRENT
            )?.takeIf { s -> s.isNotBlank() } ?: "Extended QS"
            it.text = sub
        }
        customImageView?.loadFromSettings()
    }

    private fun refreshExtraControls() {
        controlBindings[InvControlId.AUTO_BRIGHTNESS]?.let { binding ->
            val auto = Settings.System.getIntForUser(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            binding.apply(
                auto,
                R.drawable.inv_ic_auto_brightness,
                InvControlId.AUTO_BRIGHTNESS.title,
                if (auto) "On" else "Off"
            )
        }
        controlBindings[InvControlId.RINGER]?.let { binding ->
            val mode = audioManager.ringerMode
            val (iconRes, sub) = when (mode) {
                AudioManager.RINGER_MODE_VIBRATE -> R.drawable.inv_ic_vibrate to "Vibrate"
                AudioManager.RINGER_MODE_SILENT -> R.drawable.inv_ic_notifications to "Silent"
                else -> R.drawable.inv_ic_notifications to "Normal"
            }
            binding.apply(
                mode != AudioManager.RINGER_MODE_NORMAL,
                iconRes,
                InvControlId.RINGER.title,
                sub
            )
        }
        controlBindings[InvControlId.MUTE]?.let { binding ->
            val muted = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
            binding.apply(
                muted,
                R.drawable.inv_ic_volume_off,
                InvControlId.MUTE.title,
                if (muted) "Muted" else "Active"
            )
        }
    }

    private fun refreshFpsControl() {
        val binding = controlBindings[InvControlId.FPS_INFO] ?: return
        val measured = readMeasuredFps()
        binding.apply(
            measured != null,
            R.drawable.inv_ic_fps,
            InvControlId.FPS_INFO.title,
            measured ?: "Unavailable"
        )
    }

    private fun readMeasuredFps(): String? {
        val paths = listOf(
            "/sys/class/drm/sde-crtc-0/measured_fps",
            "/sys/class/graphics/fb0/measured_fps"
        )
        for (p in paths) {
            try {
                val f = File(p)
                if (f.canRead()) {
                    val text = f.readText().trim()
                    if (text.isNotEmpty()) return text
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun toggleAutoBrightness() {
        val cur = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            UserHandle.USER_CURRENT
        )
        val next = if (cur == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        } else {
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }
        Settings.System.putIntForUser(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            next,
            UserHandle.USER_CURRENT
        )
        refreshExtraControls()
        sliders.refreshAll()
    }

    private fun cycleRingerMode() {
        try {
            val next = when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            audioManager.ringerModeInternal = next
            refreshExtraControls()
        } catch (_: Throwable) {
        }
    }

    private fun toggleMute() {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = if (cur == 0) (max / 3).coerceAtLeast(1) else 0
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            refreshExtraControls()
            sliders.refreshAll()
        } catch (_: Throwable) {
        }
    }

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).roundToInt()
}
