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

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.android.systemui.R
import kotlin.math.roundToInt

/**
 * In-QS bottom sheet editor for Extended QS:
 * configures tile labels and adds/removes dynamic control blocks.
 */
class InvPanelSettingsSheet(
    private val context: Context,
    private val config: InvPanelConfig,
    private val onChanged: () -> Unit
) {
    private var dialog: Dialog? = null

    fun show() {
        dismiss()
        val sheet = LayoutInflater.from(context).inflate(R.layout.inv_panel_settings_bottomsheet, null)
        val dlg = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar).apply {
            setContentView(sheet)
            window?.apply {
                setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
                setBackgroundDrawable(ColorDrawable(0x6C000000))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setGravity(Gravity.BOTTOM)
                addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            }
        }

        val overlay = sheet.findViewById<View>(R.id.inv_settings_overlay)
        val root = sheet.findViewById<View>(R.id.bottom_sheet_root)
        if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && root != null) {
            val maxW = context.resources.getDimensionPixelSize(R.dimen.inv_panel_settings_sheet_width_landscape)
            val screenW = context.resources.displayMetrics.widthPixels
            root.layoutParams = root.layoutParams.apply {
                width = maxW.coerceAtMost(screenW)
            }
        }
        overlay?.setOnClickListener { dlg.dismiss() }
        root?.setOnClickListener { /* absorb inside taps */ }

        sheet.findViewById<View>(R.id.btn_back)?.setOnClickListener { dlg.dismiss() }

        val switchLabels = sheet.findViewById<Switch>(R.id.switch_labels)
        switchLabels?.isChecked = config.showLabels
        switchLabels?.setOnCheckedChangeListener { _, checked ->
            config.showLabels = checked
            onChanged()
        }

        val btnQqs2 = sheet.findViewById<TextView>(R.id.btn_qqs_2)
        val btnQqs4 = sheet.findViewById<TextView>(R.id.btn_qqs_4)
        fun refreshQqsCountButtons() {
            val cur = config.qqsControlCount
            listOf(btnQqs2 to 2, btnQqs4 to 4).forEach { (btn, value) ->
                val selected = (cur == value)
                btn?.setBackgroundResource(
                    if (selected) R.drawable.inv_bg_tile_on else R.drawable.inv_bg_tile_off
                )
                btn?.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (selected) R.color.inv_text_on_accent else R.color.inv_text_primary
                    )
                )
            }
        }
        refreshQqsCountButtons()
        btnQqs2?.setOnClickListener {
            if (config.qqsControlCount != 2) {
                config.qqsControlCount = 2
                refreshQqsCountButtons()
                onChanged()
            }
        }
        btnQqs4?.setOnClickListener {
            if (config.qqsControlCount != 4) {
                config.qqsControlCount = 4
                refreshQqsCountButtons()
                onChanged()
            }
        }

        dlg.setOnDismissListener { dialog = null }
        dialog = dlg
        try {
            dlg.show()
        } catch (_: Throwable) {
            dialog = null
        }
    }

    fun dismiss() {
        try {
            dialog?.dismiss()
        } catch (_: Throwable) {
        }
        dialog = null
    }
}
