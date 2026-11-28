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
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManagerGlobal
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.android.systemui.Dependency
import com.android.systemui.R
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.animation.LaunchableFrameLayout
import com.android.systemui.animation.LaunchableView
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.TileUtils
import com.android.systemui.statusbar.policy.ConfigurationController
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Root view for the Extended QS panel inside SystemUI `QSPanel`.
 * Hosts the single unified Controls + Tiles grid, drag-and-drop reordering,
 * live drag resizing, and the in-QS BottomSheet editor (`InvPanelSettingsSheet`).
 */
class InvQsPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr),
    LaunchableView,
    ConfigurationController.ConfigurationListener {

    private val inflater = LayoutInflater.from(context)
    private val handler = Handler(Looper.getMainLooper())
    private val config = InvPanelConfig(context)
    private val controlGrid = InvControlGridBinder(context, config)
    private val activityStarter = Dependency.get(ActivityStarter::class.java)
    private val configController = Dependency.get(ConfigurationController::class.java)

    private val catalogLoader = InvTileCatalogLoader(context)
    private var settingsSheet: InvPanelSettingsSheet? = null
    private var qsHost: QSHost? = null
    private var isEditMode = false
    private var blockVisibilityChanges = false
    private var isListening = false
    private var pendingHostSyncTarget: List<String>? = null
    private var lastExpansion = 0f
    private var lastBuiltCols = 0
    private var customObserverRegistered = false

    private val headerNormal: View
    private val headerEdit: View
    private val txtEditHint: View
    private val bodyHost: LinearLayout
    private val controlsColumn: LinearLayout
    private val controlsHost: LinearLayout
    private val unifiedCatalogContainer: LinearLayout
    private val tilesColumn: LinearLayout
    private val labelControls: TextView
    private val labelTiles: TextView

    private val btnEdit: ImageButton
    private val btnSettings: LaunchableFrameLayout
    private val btnMore: ImageButton
    private val invHeaderClock: View?
    private val invHeaderDate: View?
    private val btnPanelSettings: View
    private val btnReset: View
    private val btnDone: View

    private val hostCallback = QSHost.Callback {
        handler.post {
            val hostSpecs = qsHost?.tiles?.map { it.tileSpec }.orEmpty()
            val pending = pendingHostSyncTarget
            if (pending != null) {
                if (hostSpecs == pending) {
                    pendingHostSyncTarget = null
                } else {
                    rebuildPanel()
                    return@post
                }
            }
            config.syncExternalHostTiles(hostSpecs)
            rebuildPanel()
        }
    }

    private val customContentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val changedKey = uri?.lastPathSegment
            if (changedKey == "invos_qs_custom_image") {
                val cur = Settings.System.getStringForUser(
                    context.contentResolver,
                    "invos_qs_custom_image",
                    UserHandle.USER_CURRENT
                )
                if (!cur.isNullOrBlank() && config.ensureControl(InvControlId.CUSTOM_IMAGE, forceShow = true)) {
                    rebuildPanel()
                    return
                }
            }
            controlGrid.refreshDynamicValues()
        }
    }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        inflater.inflate(R.layout.inv_view_qs_panel, this, true)

        headerNormal = findViewById(R.id.header_normal)
        headerEdit = findViewById(R.id.header_edit)
        txtEditHint = findViewById(R.id.txt_edit_hint)
        bodyHost = findViewById(R.id.body_host)
        controlsColumn = findViewById(R.id.controls_column)
        controlsHost = findViewById(R.id.controls_host)
        unifiedCatalogContainer = findViewById(R.id.unified_catalog_container)
        tilesColumn = findViewById(R.id.tiles_column)
        labelControls = findViewById(R.id.label_controls)
        labelTiles = findViewById(R.id.label_tiles)

        tilesColumn.visibility = View.GONE
        labelControls.visibility = View.GONE
        labelTiles.visibility = View.GONE

        btnEdit = findViewById(R.id.btn_edit)
        btnSettings = findViewById(R.id.btn_settings)
        btnMore = findViewById(R.id.btn_more)
        invHeaderClock = findViewById(R.id.inv_header_clock)
        invHeaderDate = findViewById(R.id.inv_header_date)
        btnPanelSettings = findViewById(R.id.btn_panel_settings)
        btnReset = findViewById(R.id.btn_reset)
        btnDone = findViewById(R.id.btn_done)

        wireHeaderButtons()
        controlsHost.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val availCols = config.computeAutoColumns(v.width)
            if (v.width > 0 && availCols != lastBuiltCols) {
                v.post { rebuildPanel() }
            }
        }
        applyResponsiveOrientation()
        rebuildPanel()
    }

    fun bindHost(host: QSHost?) {
        if (qsHost === host) return
        qsHost?.removeCallback(hostCallback)
        qsHost = host
        controlGrid.bindHost(host)
        if (isAttachedToWindow && host != null) {
            host.addCallback(hostCallback)
        }
        val hostSpecs = host?.tiles?.map { it.tileSpec }.orEmpty()
        if (hostSpecs.isNotEmpty()) {
            config.getUnifiedItems(hostSpecs)
            syncUnifiedToHost()
        }
        rebuildPanel()
    }

    fun setListening(listening: Boolean) {
        if (isListening == listening) return
        isListening = listening
        if (listening) {
            controlGrid.startListening()
        } else {
            controlGrid.stopListening()
        }
    }

    fun onNativeQsExpansion(expansion: Float) {
        val safeExpansion = if (expansion.isFinite()) expansion else 0f
        val clamped = safeExpansion.coerceIn(0f, 1f)
        if (clamped > 0f && clamped < 1f && abs(clamped - lastExpansion) < 0.002f) {
            return
        }
        lastExpansion = clamped
        if (isEditMode) return

        val inMotion = clamped in 0.001f..0.999f
        val desiredLayer = if (inMotion) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (controlsColumn.layerType != desiredLayer) {
            controlsColumn.setLayerType(desiredLayer, null)
        }

        val headerAlpha = ((clamped - 0.15f) / 0.55f).coerceIn(0f, 1f)
        headerNormal.alpha = headerAlpha
        headerNormal.translationY = (1f - headerAlpha) * -10f

        val controlsAlpha = ((clamped - 0.08f) / 0.52f).coerceIn(0f, 1f)
        controlsColumn.alpha = controlsAlpha
        controlsColumn.translationY = (1f - controlsAlpha) * -14f
    }

    fun exitEditModeIfNeeded() {
        settingsSheet?.dismiss()
        if (isEditMode) {
            setEditMode(false)
        }
    }

    override fun setShouldBlockVisibilityChanges(block: Boolean) {
        blockVisibilityChanges = block
    }

    override fun setVisibility(visibility: Int) {
        if (!blockVisibilityChanges) {
            super.setVisibility(visibility)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        configController.addCallback(this)
        qsHost?.addCallback(hostCallback)
        registerCustomContentObservers()
        val curCustomImg = Settings.System.getStringForUser(
            context.contentResolver,
            "invos_qs_custom_image",
            UserHandle.USER_CURRENT
        )
        if (!curCustomImg.isNullOrBlank()) {
            config.ensureControl(InvControlId.CUSTOM_IMAGE)
        }
        rebuildPanel()
        applyThemeColors()
        if (isShown && TileUtils.isInvQsEnabled(context)) {
            setListening(true)
        }
    }

    override fun onDetachedFromWindow() {
        catalogLoader.cancel()
        settingsSheet?.dismiss()
        setListening(false)
        qsHost?.removeCallback(hostCallback)
        configController.removeCallback(this)
        if (customObserverRegistered) {
            try {
                context.contentResolver.unregisterContentObserver(customContentObserver)
            } catch (_: Throwable) {
            }
            customObserverRegistered = false
        }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) {
            val availCols = config.computeAutoColumns(controlsHost.width)
            if (availCols != lastBuiltCols) {
                post { rebuildPanel() }
            }
        }
    }

    override fun onConfigChanged(newConfig: Configuration?) {
        applyResponsiveOrientation()
        applyThemeColors()
        rebuildPanel()
    }

    override fun onThemeChanged() {
        settingsSheet?.dismiss()
        applyThemeColors()
        rebuildPanel()
    }

    override fun onUiModeChanged() {
        settingsSheet?.dismiss()
        applyThemeColors()
        rebuildPanel()
    }

    private fun applyThemeColors() {
        (invHeaderClock as? TextView)?.setTextColor(
            ContextCompat.getColor(context, R.color.inv_text_primary)
        )
        val secondaryText = ContextCompat.getColor(context, R.color.inv_text_secondary)
        (invHeaderDate as? TextView)?.setTextColor(secondaryText)
        (txtEditHint as? TextView)?.setTextColor(secondaryText)

        val primaryIcon = ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.inv_icon_primary)
        )
        btnEdit.setBackgroundResource(R.drawable.inv_bg_header_btn)
        btnEdit.imageTintList = primaryIcon
        btnMore.setBackgroundResource(R.drawable.inv_bg_header_btn)
        btnMore.imageTintList = primaryIcon
        btnReset.setBackgroundResource(R.drawable.inv_bg_header_btn)
        (btnReset as? ImageView)?.imageTintList = primaryIcon

        btnSettings.setBackgroundResource(R.drawable.inv_bg_header_btn)
        for (i in 0 until btnSettings.childCount) {
            (btnSettings.getChildAt(i) as? ImageView)?.imageTintList = primaryIcon
        }

        val onAccent = ContextCompat.getColor(context, R.color.inv_icon_on_accent)
        btnPanelSettings.setBackgroundResource(R.drawable.inv_bg_btn_secondary_pill)
        (btnPanelSettings as? TextView)?.setTextColor(onAccent)
        btnDone.setBackgroundResource(R.drawable.inv_bg_btn_done)
        (btnDone as? TextView)?.setTextColor(onAccent)
    }

    private fun registerCustomContentObservers() {
        if (customObserverRegistered) return
        customObserverRegistered = true
        val resolver = context.contentResolver
        listOf(
            "invos_qs_custom_username",
            "invos_qs_custom_subtitle",
            "invos_qs_custom_image",
            "invos_qs_custom_gif_animate",
            "invos_qs_custom_image_alpha"
        ).forEach { key ->
            resolver.registerContentObserver(
                Settings.System.getUriFor(key),
                false,
                customContentObserver,
                UserHandle.USER_ALL
            )
        }
    }

    private fun wireHeaderButtons() {
        btnEdit.setOnClickListener { setEditMode(true) }
        btnDone.setOnClickListener { setEditMode(false) }

        btnSettings.setOnClickListener {
            val controller = ActivityLaunchAnimator.Controller.fromView(btnSettings)
            activityStarter.postStartActivityDismissingKeyguard(
                Intent(Settings.ACTION_SETTINGS), 0, controller
            )
        }

        btnMore.setOnClickListener {
            try {
                WindowManagerGlobal.getWindowManagerService().showGlobalActions()
            } catch (_: Throwable) {
            }
        }

        invHeaderClock?.setOnClickListener {
            activityStarter.postStartActivityDismissingKeyguard(
                Intent(AlarmClock.ACTION_SHOW_ALARMS), 0
            )
        }

        invHeaderDate?.setOnClickListener {
            val builder = CalendarContract.CONTENT_URI.buildUpon()
            builder.appendPath("time")
            builder.appendPath(System.currentTimeMillis().toString())
            activityStarter.postStartActivityDismissingKeyguard(
                Intent(Intent.ACTION_VIEW, builder.build()), 0
            )
        }

        btnPanelSettings.setOnClickListener {
            if (!isAttachedToWindow) return@setOnClickListener
            val sheet = settingsSheet ?: InvPanelSettingsSheet(context, config) {
                syncUnifiedToHost()
                rebuildPanel()
            }.also { settingsSheet = it }
            sheet.show()
        }

        btnReset.setOnClickListener {
            val defaultSpecs = context.getString(R.string.quick_settings_tiles_default)
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            config.resetDefaults(defaultSpecs)
            syncUnifiedToHost()
            rebuildPanel()
        }
    }

    private fun setEditMode(editing: Boolean) {
        isEditMode = editing
        headerNormal.visibility = if (editing) View.GONE else View.VISIBLE
        headerEdit.visibility = if (editing) View.VISIBLE else View.GONE
        txtEditHint.visibility = if (editing) View.VISIBLE else View.GONE
        labelControls.visibility = View.GONE
        labelTiles.visibility = View.GONE

        if (editing) {
            controlsColumn.alpha = 1f
            controlsColumn.translationY = 0f
            controlsColumn.scaleX = 1f
            controlsColumn.scaleY = 1f
        } else {
            onNativeQsExpansion(lastExpansion)
        }
        rebuildPanel()
    }

    private fun applyResponsiveOrientation() {
        bodyHost.orientation = VERTICAL
        tilesColumn.visibility = View.GONE
        val ctrlLp = controlsColumn.layoutParams as LayoutParams
        ctrlLp.width = ViewGroup.LayoutParams.MATCH_PARENT
        ctrlLp.weight = 0f
        ctrlLp.marginEnd = 0
        controlsColumn.layoutParams = ctrlLp
    }

    private fun syncUnifiedToHost() {
        val host = qsHost ?: return
        val prev = host.tiles.map { it.tileSpec }
        val target = config.buildHostTileSpecsForUnified()
        if (target == prev || target.isEmpty()) return
        pendingHostSyncTarget = target
        host.changeTilesByUser(prev, target)
    }

    private fun rebuildPanel() {
        catalogLoader.cancel()
        val cols = config.computeAutoColumns(controlsHost.width)
        lastBuiltCols = cols

        controlGrid.rebuild(controlsHost, isEditMode) {
            syncUnifiedToHost()
            rebuildPanel()
        }

        if (!isEditMode) {
            unifiedCatalogContainer.removeAllViews()
            unifiedCatalogContainer.visibility = View.GONE
            return
        }

        val host = qsHost
        unifiedCatalogContainer.removeAllViews()
        unifiedCatalogContainer.visibility = View.VISIBLE
        populateUnifiedCatalog(unifiedCatalogContainer, host, cols)
    }

    private fun populateUnifiedCatalog(
        catalogSection: LinearLayout,
        host: QSHost?,
        columns: Int
    ) {
        val gap = resources.getDimensionPixelSize(R.dimen.inv_qs_control_grid_item_spacing)
        val halfGap = gap / 2
        val hostSpecs = host?.tiles?.map { it.tileSpec }.orEmpty()
        val activeUnified = config.getUnifiedItems(hostSpecs)
        val activeControls = activeUnified.mapNotNull { InvPanelConfig.parseControlId(it) }.toSet()
        val activeTileSpecs = activeUnified.mapNotNull { InvPanelConfig.parseTileSpec(it) }.toSet()

        val catalogHeader = TextView(context).apply {
            text = "Tap to add"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.inv_text_secondary))
            setPadding(4.dp(), 10.dp(), 4.dp(), 8.dp())
        }
        catalogSection.addView(catalogHeader)

        val catalogGrid = GridLayout(context).apply {
            columnCount = columns
            clipChildren = false
            clipToPadding = false
            useDefaultMargins = false
        }
        catalogSection.addView(catalogGrid)

        fun appendCatalogItem(
            titleText: String,
            iconRes: Int? = null,
            iconDrawable: android.graphics.drawable.Drawable? = null,
            onAddClick: () -> Unit
        ) {
            val catalogView = inflater.inflate(R.layout.inv_item_catalog_tile, catalogGrid, false)
            val icon = catalogView.findViewById<ImageView>(R.id.icon)
            val title = catalogView.findViewById<TextView>(R.id.tile_title)
            val btnAdd = catalogView.findViewById<View>(R.id.btn_add)
            val tileBody = catalogView.findViewById<View>(R.id.tile_body)

            if (iconRes != null) {
                icon.setImageResource(iconRes)
            } else if (iconDrawable != null) {
                icon.setImageDrawable(iconDrawable)
            }
            title.text = titleText

            val action = View.OnClickListener { onAddClick() }
            tileBody.setOnClickListener(action)
            btnAdd.setOnClickListener(action)

            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(halfGap, halfGap, halfGap, halfGap)
            }
            catalogGrid.addView(catalogView, lp)
        }

        val inactiveControls = InvControlId.values().filter { !activeControls.contains(it) }
        inactiveControls.forEach { ctrlId ->
            val token = InvPanelConfig.controlToken(ctrlId)
            appendCatalogItem(
                titleText = ctrlId.title,
                iconRes = ctrlId.iconRes
            ) {
                if (config.addUnifiedItem(token, hostSpecs)) {
                    syncUnifiedToHost()
                    rebuildPanel()
                }
            }
        }

        if (host == null) {
            if (inactiveControls.isEmpty()) {
                catalogSection.visibility = View.GONE
            }
            return
        }

        val statusLabel = TextView(context).apply {
            text = "Loading available tiles…"
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.inv_text_secondary))
            setPadding(4.dp(), 6.dp(), 4.dp(), 8.dp())
        }
        catalogSection.addView(statusLabel)

        catalogLoader.loadAvailableTiles(host, activeTileSpecs) { entries ->
            if (!isEditMode || qsHost == null) return@loadAvailableTiles
            catalogSection.removeView(statusLabel)
            if (entries.isEmpty() && inactiveControls.isEmpty()) {
                catalogSection.visibility = View.GONE
                return@loadAvailableTiles
            }
            entries.forEach { entry ->
                val label = if (!entry.appLabel.isNullOrBlank()) {
                    "${entry.label} (${entry.appLabel})"
                } else {
                    entry.label
                }
                appendCatalogItem(
                    titleText = label,
                    iconDrawable = entry.icon
                ) {
                    val token = InvPanelConfig.tileToken(entry.spec)
                    if (config.addUnifiedItem(token, hostSpecs)) {
                        syncUnifiedToHost()
                        rebuildPanel()
                    }
                }
            }
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()
}
