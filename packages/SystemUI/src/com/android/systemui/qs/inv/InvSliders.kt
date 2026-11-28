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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.hardware.display.BrightnessInfo
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.MathUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX
import com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MIN
import com.android.settingslib.display.BrightnessUtils.convertGammaToLinearFloat
import com.android.settingslib.display.BrightnessUtils.convertLinearToGammaFloat
import com.android.systemui.Dependency
import com.android.systemui.R
import com.android.systemui.plugins.ActivityStarter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Binds InvQs brightness and media-volume sliders (both vertical and horizontal variants)
 * using GPU fill translation (zero layout passes during drag) and offloaded coalesced Binder writes.
 */
class InvSlidersController(private val context: Context) {

    private data class SliderBinding(
        val root: View,
        val body: View,
        val fill: View,
        val icon: ImageView,
        val title: TextView?,
        val summary: TextView?,
        val vertical: Boolean,
        val isBrightness: Boolean,
        var lastPercent: Int = -1,
        var lastIconRes: Int = 0,
        var lastIconTint: Int = 0,
        var lastTitleColor: Int = 0,
        var lastSummaryColor: Int = 0,
        var lastIconPx: Int = 0
    )

    private data class BrightnessWrite(val displayId: Int, val linear: Float, val commit: Boolean)

    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val ipcExecutor = Executors.newSingleThreadExecutor()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val bindings = mutableListOf<SliderBinding>()
    private var isDraggingBrightness = false
    private var isDraggingVolume = false
    private var registered = false

    @Volatile
    private var cachedAutoBrightness = false
    @Volatile
    private var cachedBrightnessMin = 0.0f
    @Volatile
    private var cachedBrightnessMax = 1.0f
    @Volatile
    private var cachedMaxVolume = 15
    @Volatile
    private var lastAppliedVolumeStep = -1

    private val pendingBrightnessWrite = AtomicReference<BrightnessWrite?>(null)
    private val brightnessWorkerScheduled = AtomicInteger(0)
    private val pendingVolumeStep = AtomicInteger(-1)
    private val volumeWorkerScheduled = AtomicInteger(0)

    private val iconOnAccent by lazy { ContextCompat.getColor(context, R.color.inv_icon_on_accent) }
    private val iconPrimary by lazy { ContextCompat.getColor(context, R.color.inv_icon_primary) }
    private val textOnAccent by lazy { ContextCompat.getColor(context, R.color.inv_text_on_accent) }
    private val textPrimary by lazy { ContextCompat.getColor(context, R.color.inv_text_primary) }
    private val textSecondary by lazy { ContextCompat.getColor(context, R.color.inv_text_secondary) }

    private val brightnessObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            cachedAutoBrightness = queryAutoBrightnessEnabled()
            if (!isDraggingBrightness) {
                refreshBrightness()
            }
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (!isDraggingVolume && intent?.action == AudioManager.VOLUME_CHANGED_ACTION) {
                refreshVolume()
            }
        }
    }

    fun clear() {
        bindings.clear()
    }

    fun bindBrightness(view: View, vertical: Boolean, editMode: Boolean = false, square: Boolean = false) {
        val binding = extractBinding(view, vertical, isBrightness = true) ?: return
        bindings.add(binding)
        applyCornerStyle(binding, square)
        cachedAutoBrightness = queryAutoBrightnessEnabled()
        if (editMode) {
            freezeForEdit(binding)
        } else {
            attachSliderTouch(binding) { fraction, isFinal ->
                isDraggingBrightness = !isFinal
                enqueueBrightnessWrite(fraction, commit = isFinal)
                updateFillAndText(binding, fraction)
            }
            binding.icon.isClickable = true
            binding.icon.setOnClickListener { toggleAutoBrightness() }
            binding.root.setOnLongClickListener {
                Dependency.get(ActivityStarter::class.java).postStartActivityDismissingKeyguard(
                    Intent(Settings.ACTION_DISPLAY_SETTINGS),
                    0
                )
                true
            }
        }
        if (view.getTag(R.id.slider_fill) == null) {
            val lcl = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if ((right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) && !isDraggingBrightness) {
                    (view.getTag(R.id.slider_fill) as? SliderBinding)?.let { latest ->
                        updateFillAndText(latest, readBrightnessFraction())
                    }
                }
            }
            view.addOnLayoutChangeListener(lcl)
        }
        view.setTag(R.id.slider_fill, binding)
        updateFillAndText(binding, readBrightnessFraction())
    }

    fun bindVolume(view: View, vertical: Boolean, editMode: Boolean = false, square: Boolean = false) {
        val binding = extractBinding(view, vertical, isBrightness = false) ?: return
        bindings.add(binding)
        applyCornerStyle(binding, square)
        if (editMode) {
            freezeForEdit(binding)
        } else {
            attachSliderTouch(binding) { fraction, isFinal ->
                isDraggingVolume = !isFinal
                enqueueVolumeWrite(fraction)
                updateFillAndText(binding, fraction)
            }
            binding.icon.isClickable = true
            binding.icon.setOnClickListener { toggleMuteVolume() }
            binding.root.setOnLongClickListener {
                Dependency.get(ActivityStarter::class.java).postStartActivityDismissingKeyguard(
                    Intent(Settings.ACTION_SOUND_SETTINGS),
                    0
                )
                true
            }
        }
        if (view.getTag(R.id.slider_fill) == null) {
            val lcl = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if ((right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) && !isDraggingVolume) {
                    (view.getTag(R.id.slider_fill) as? SliderBinding)?.let { latest ->
                        updateFillAndText(latest, readVolumeFraction())
                    }
                }
            }
            view.addOnLayoutChangeListener(lcl)
        }
        view.setTag(R.id.slider_fill, binding)
        updateFillAndText(binding, readVolumeFraction())
    }

    private fun applyCornerStyle(binding: SliderBinding, square: Boolean) {
        if (binding.body.getTag(R.id.slider_body) == square) return
        binding.body.setTag(R.id.slider_body, square)
        binding.body.setBackgroundResource(
            if (square) R.drawable.inv_bg_slider_track_square else R.drawable.inv_bg_slider_track
        )
        val fillRes = when {
            binding.vertical && square -> R.drawable.inv_bg_slider_fill_vertical_square
            binding.vertical -> R.drawable.inv_bg_slider_fill_vertical
            square -> R.drawable.inv_bg_slider_fill_square
            else -> R.drawable.inv_bg_slider_fill
        }
        binding.fill.setBackgroundResource(fillRes)
    }

    private fun freezeForEdit(binding: SliderBinding) {
        binding.body.setOnTouchListener(null)
        binding.body.setOnClickListener(null)
        binding.body.setOnLongClickListener(null)
        binding.body.isClickable = false
        binding.body.isLongClickable = false
        binding.body.isFocusable = false
        binding.icon.setOnClickListener(null)
        binding.icon.setOnLongClickListener(null)
        binding.icon.isClickable = false
        binding.icon.isLongClickable = false
        binding.icon.isFocusable = false
        binding.root.setOnLongClickListener(null)
    }

    fun startListening() {
        if (registered) return
        registered = true
        cachedAutoBrightness = queryAutoBrightnessEnabled()
        refreshMaxVolume()
        val resolver = context.contentResolver
        resolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            brightnessObserver,
            UserHandle.USER_ALL
        )
        resolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
            false,
            brightnessObserver,
            UserHandle.USER_ALL
        )
        context.registerReceiver(
            volumeReceiver,
            IntentFilter(AudioManager.VOLUME_CHANGED_ACTION)
        )
        refreshAll()
    }

    fun stopListening() {
        if (!registered) return
        registered = false
        try {
            context.contentResolver.unregisterContentObserver(brightnessObserver)
        } catch (_: Throwable) {
        }
        try {
            context.unregisterReceiver(volumeReceiver)
        } catch (_: Throwable) {
        }
    }

    fun refreshAll() {
        refreshBrightness()
        refreshVolume()
    }

    private fun refreshBrightness() {
        val fraction = readBrightnessFraction()
        bindings.filter { it.isBrightness }.forEach { updateFillAndText(it, fraction) }
    }

    private fun refreshVolume() {
        val fraction = readVolumeFraction()
        bindings.filter { !it.isBrightness }.forEach { updateFillAndText(it, fraction) }
    }

    private fun extractBinding(view: View, vertical: Boolean, isBrightness: Boolean): SliderBinding? {
        val body = view.findViewById<View>(R.id.slider_body) ?: return null
        val fill = view.findViewById<View>(R.id.slider_fill) ?: return null
        val icon = view.findViewById<ImageView>(R.id.slider_icon) ?: return null
        val title = view.findViewById<TextView>(R.id.slider_title)
        val summary = view.findViewById<TextView>(R.id.slider_summary)
        title?.text = if (isBrightness) "Brightness" else "Volume"
        val flp = fill.layoutParams
        if (flp.width != ViewGroup.LayoutParams.MATCH_PARENT || flp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            flp.width = ViewGroup.LayoutParams.MATCH_PARENT
            flp.height = ViewGroup.LayoutParams.MATCH_PARENT
            fill.layoutParams = flp
        }
        return SliderBinding(view, body, fill, icon, title, summary, vertical, isBrightness)
    }

    private fun attachSliderTouch(
        binding: SliderBinding,
        onFractionChanged: (fraction: Float, isFinal: Boolean) -> Unit
    ) {
        var downX = 0f
        var downY = 0f
        var startFraction = 0f
        var moved = false

        binding.body.setOnTouchListener { v, event ->
            val span = if (binding.vertical) v.height else v.width
            if (span <= 0) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    binding.body.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    startFraction = if (binding.isBrightness) {
                        cachedAutoBrightness = queryAutoBrightnessEnabled()
                        readBrightnessFraction()
                    } else {
                        refreshMaxVolume()
                        readVolumeFraction()
                    }
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = if (binding.vertical) {
                        (downY - event.y) / span.toFloat()
                    } else {
                        (event.x - downX) / span.toFloat()
                    }
                    if (!moved && (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) {
                        moved = true
                    }
                    if (moved) {
                        val next = (startFraction + delta).coerceIn(0f, 1f)
                        onFractionChanged(next, false)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    binding.body.setLayerType(View.LAYER_TYPE_NONE, null)
                    val target = if (moved) {
                        val delta = if (binding.vertical) {
                            (downY - event.y) / span.toFloat()
                        } else {
                            (event.x - downX) / span.toFloat()
                        }
                        (startFraction + delta).coerceIn(0f, 1f)
                    } else {
                        if (binding.vertical) {
                            (1f - (event.y / span.toFloat())).coerceIn(0f, 1f)
                        } else {
                            (event.x / span.toFloat()).coerceIn(0f, 1f)
                        }
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        onFractionChanged(target, true)
                    } else if (moved) {
                        onFractionChanged(target, true)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateFillAndText(binding: SliderBinding, fraction: Float) {
        val clamped = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        val body = binding.body
        val fill = binding.fill
        val w = body.width
        val h = body.height

        if (binding.vertical) {
            if (h > 0) {
                fill.translationX = 0f
                fill.translationY = (1f - clamped) * h.toFloat()
            }
        } else {
            if (w > 0) {
                fill.translationY = 0f
                val rtlSign = if (body.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f else -1f
                fill.translationX = rtlSign * (1f - clamped) * w.toFloat()
            }
        }

        val percent = (clamped * 100f).roundToInt()
        if (binding.lastPercent != percent) {
            binding.lastPercent = percent
            binding.summary?.text = "$percent%"
        }

        val iconCovered = if (binding.vertical) clamped >= 0.20f else clamped >= 0.14f
        val textCovered = !binding.vertical && clamped >= 0.36f
        val iconTint = if (iconCovered) iconOnAccent else iconPrimary
        val titleColor = if (textCovered) textOnAccent else textPrimary
        val summaryColor = if (textCovered) textOnAccent else textSecondary

        val iconRes = if (binding.isBrightness) {
            when {
                cachedAutoBrightness -> R.drawable.inv_ic_auto_brightness
                percent >= 70 -> R.drawable.inv_ic_brightness_full
                percent >= 30 -> R.drawable.inv_ic_brightness_medium
                else -> R.drawable.inv_ic_brightness_low
            }
        } else {
            if (percent == 0) R.drawable.inv_ic_volume_off else R.drawable.inv_ic_volume
        }
        if (binding.lastIconRes != iconRes) {
            binding.lastIconRes = iconRes
            binding.icon.setImageResource(iconRes)
        }
        if (binding.lastIconTint != iconTint) {
            binding.lastIconTint = iconTint
            binding.icon.imageTintList = ColorStateList.valueOf(iconTint)
        }

        val bodyBasis = if (binding.vertical) w else h
        if (bodyBasis > 0) {
            val density = context.resources.displayMetrics.density
            val desiredIconPx = (bodyBasis * 0.34f).roundToInt()
                .coerceIn((20 * density).roundToInt(), (28 * density).roundToInt())
            if (binding.lastIconPx != desiredIconPx) {
                binding.lastIconPx = desiredIconPx
                val iconLp = binding.icon.layoutParams
                if (iconLp.width != desiredIconPx || iconLp.height != desiredIconPx) {
                    iconLp.width = desiredIconPx
                    iconLp.height = desiredIconPx
                    binding.icon.layoutParams = iconLp
                }
            }
        }

        if (binding.lastTitleColor != titleColor) {
            binding.lastTitleColor = titleColor
            binding.title?.setTextColor(titleColor)
        }
        if (binding.lastSummaryColor != summaryColor) {
            binding.lastSummaryColor = summaryColor
            binding.summary?.setTextColor(summaryColor)
        }
    }

    private fun readBrightnessFraction(): Float {
        val info: BrightnessInfo = context.display?.brightnessInfo ?: return 0.5f
        cachedBrightnessMin = info.brightnessMinimum
        cachedBrightnessMax = info.brightnessMaximum
        val gamma = convertLinearToGammaFloat(
            info.brightness,
            info.brightnessMinimum,
            info.brightnessMaximum
        )
        return ((gamma - GAMMA_SPACE_MIN).toFloat() /
            (GAMMA_SPACE_MAX - GAMMA_SPACE_MIN).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    }

    private fun enqueueBrightnessWrite(fraction: Float, commit: Boolean) {
        val min = cachedBrightnessMin
        val max = cachedBrightnessMax
        val gamma = (GAMMA_SPACE_MIN + fraction * (GAMMA_SPACE_MAX - GAMMA_SPACE_MIN)).roundToInt()
        val linear = MathUtils.min(convertGammaToLinearFloat(gamma, min, max), max)
        pendingBrightnessWrite.set(BrightnessWrite(context.displayId, linear, commit))
        if (brightnessWorkerScheduled.compareAndSet(0, 1)) {
            ipcExecutor.execute {
                try {
                    while (true) {
                        val work = pendingBrightnessWrite.getAndSet(null) ?: break
                        try {
                            displayManager?.setTemporaryBrightness(work.displayId, work.linear)
                            if (work.commit) {
                                displayManager?.setBrightness(work.displayId, work.linear)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                } finally {
                    brightnessWorkerScheduled.set(0)
                    if (pendingBrightnessWrite.get() != null) {
                        enqueueLatestBrightnessDrain()
                    }
                }
            }
        }
    }

    private fun enqueueLatestBrightnessDrain() {
        if (brightnessWorkerScheduled.compareAndSet(0, 1)) {
            ipcExecutor.execute {
                try {
                    while (true) {
                        val work = pendingBrightnessWrite.getAndSet(null) ?: break
                        try {
                            displayManager?.setTemporaryBrightness(work.displayId, work.linear)
                            if (work.commit) {
                                displayManager?.setBrightness(work.displayId, work.linear)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                } finally {
                    brightnessWorkerScheduled.set(0)
                }
            }
        }
    }

    private fun queryAutoBrightnessEnabled(): Boolean {
        return try {
            Settings.System.getIntForUser(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (_: Throwable) {
            false
        }
    }

    private fun toggleAutoBrightness() {
        try {
            val next = if (cachedAutoBrightness) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            } else {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            }
            cachedAutoBrightness = (next == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            Settings.System.putIntForUser(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                next,
                UserHandle.USER_CURRENT
            )
            refreshBrightness()
        } catch (_: Throwable) {
        }
    }

    private fun refreshMaxVolume() {
        try {
            cachedMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        } catch (_: Throwable) {
        }
    }

    private fun readVolumeFraction(): Float {
        return try {
            val max = cachedMaxVolume.coerceAtLeast(1)
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            lastAppliedVolumeStep = cur
            (cur.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        } catch (_: Throwable) {
            0f
        }
    }

    private fun enqueueVolumeWrite(fraction: Float) {
        val max = cachedMaxVolume.coerceAtLeast(1)
        val target = (fraction * max).roundToInt().coerceIn(0, max)
        if (target == lastAppliedVolumeStep) return
        lastAppliedVolumeStep = target
        pendingVolumeStep.set(target)
        if (volumeWorkerScheduled.compareAndSet(0, 1)) {
            ipcExecutor.execute {
                try {
                    while (true) {
                        val step = pendingVolumeStep.getAndSet(-1)
                        if (step < 0) break
                        try {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
                        } catch (_: Throwable) {
                        }
                    }
                } finally {
                    volumeWorkerScheduled.set(0)
                }
            }
        }
    }

    private fun toggleMuteVolume() {
        try {
            refreshMaxVolume()
            val max = cachedMaxVolume.coerceAtLeast(1)
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = if (cur == 0) (max / 3).coerceAtLeast(1) else 0
            lastAppliedVolumeStep = target
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            refreshVolume()
        } catch (_: Throwable) {
        }
    }
}
