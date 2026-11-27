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
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.android.systemui.Dependency
import com.android.systemui.R
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.animation.LaunchableFrameLayout
import com.android.systemui.media.dialog.MediaOutputDialogFactory
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.NotificationMediaManager
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Binds `inv_item_media` dynamically across compact (1x2..1x4) and card (2x2..2x4) variants,
 * scaling controls/icons proportionally and blurring artwork off the main thread.
 */
class InvMediaController(private val context: Context) {

    private val mediaSessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val notifMediaManager = Dependency.get(NotificationMediaManager::class.java)
    private val mediaOutputDialogFactory = Dependency.get(MediaOutputDialogFactory::class.java)
    private val activityStarter = Dependency.get(ActivityStarter::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val blurExecutor = Executors.newSingleThreadExecutor()

    private var rootView: View? = null
    private var currentRowSpan: Int = 2
    private var currentColSpan: Int = 2
    private var activeController: MediaController? = null
    private var lastArtwork: Bitmap? = null
    private var lastBlurGeneration = 0
    private var lastRenderSignature: String? = null
    private var listening = false

    private var mediaBody: LaunchableFrameLayout? = null
    private var mediaContent: View? = null
    private var mediaBlurBg: ImageView? = null
    private var mediaScrim: View? = null
    private var mediaArtHolder: FrameLayout? = null
    private var mediaArt: ImageView? = null
    private var mediaPlaceholderIcon: ImageView? = null
    private var mediaOutputBtn: View? = null
    private var mediaOutputIcon: ImageView? = null
    private var mediaOutputLabel: TextView? = null
    private var mediaInfo: LinearLayout? = null
    private var mediaTitle: TextView? = null
    private var mediaArtist: TextView? = null
    private var mediaControls: FrameLayout? = null
    private var mediaPrev: ImageView? = null
    private var mediaPlay: ImageView? = null
    private var mediaNext: ImageView? = null

    private val sessionCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            renderState()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            renderState()
        }
    }

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { refreshActiveController() }

    private val periodicSyncRunnable = object : Runnable {
        override fun run() {
            if (listening) {
                refreshActiveController()
                handler.postDelayed(this, 5000L)
            }
        }
    }

    fun bind(view: View, rowSpan: Int, colSpan: Int, editMode: Boolean = false) {
        rootView = view
        currentRowSpan = rowSpan
        currentColSpan = colSpan
        // Views are recreated on rebind, so drop cached state to force a fresh render.
        lastArtwork = null
        lastRenderSignature = null

        mediaBody = view.findViewById(R.id.media_body)
        mediaContent = view.findViewById(R.id.media_content)
        mediaBlurBg = view.findViewById(R.id.media_blur_bg)
        mediaScrim = view.findViewById(R.id.media_scrim)
        mediaArtHolder = view.findViewById(R.id.media_art_holder)
        mediaArt = view.findViewById(R.id.media_art)
        mediaPlaceholderIcon = view.findViewById(R.id.media_placeholder_icon)
        mediaOutputBtn = view.findViewById(R.id.media_output_button)
        mediaOutputIcon = view.findViewById(R.id.media_output)
        mediaOutputLabel = view.findViewById(R.id.media_output_label)
        mediaInfo = view.findViewById(R.id.media_info)
        mediaTitle = view.findViewById(R.id.media_title)
        mediaArtist = view.findViewById(R.id.media_artist)
        mediaControls = view.findViewById(R.id.media_controls)
        mediaPrev = view.findViewById(R.id.media_prev)
        mediaPlay = view.findViewById(R.id.media_play)
        mediaNext = view.findViewById(R.id.media_next)

        applyLayoutVariant(rowSpan, colSpan)
        if (editMode) {
            freezeForEdit()
        } else {
            wireClickListeners()
        }
        refreshActiveController()
    }

    fun startListening() {
        if (listening) return
        listening = true
        try {
            mediaSessionManager?.addOnActiveSessionsChangedListener(activeSessionsListener, null)
        } catch (_: SecurityException) {
        }
        refreshActiveController()
        handler.removeCallbacks(periodicSyncRunnable)
        handler.postDelayed(periodicSyncRunnable, 5000L)
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        handler.removeCallbacks(periodicSyncRunnable)
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (_: Throwable) {
        }
        try {
            activeController?.unregisterCallback(sessionCallback)
        } catch (_: Throwable) {
        }
        activeController = null
    }

    private fun applyLayoutVariant(rowSpan: Int, colSpan: Int) {
        val compactBar = rowSpan <= 1
        val artLp = mediaArtHolder?.layoutParams as? FrameLayout.LayoutParams
        val infoLp = mediaInfo?.layoutParams as? FrameLayout.LayoutParams
        val artistLp = mediaArtist?.layoutParams as? LinearLayout.LayoutParams
        val ctrlLp = mediaControls?.layoutParams as? FrameLayout.LayoutParams
        val outLp = mediaOutputBtn?.layoutParams as? FrameLayout.LayoutParams

        if (compactBar) {
            mediaContent?.setPadding(14.dp(), 8.dp(), 14.dp(), 8.dp())
            val inlineSize = if (colSpan <= 2) 36.dp() else 42.dp()
            artLp?.apply {
                width = inlineSize
                height = inlineSize
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                topMargin = 0
            }
            val showSideTransport = colSpan >= 3
            val btnSize = 30.dp()
            val playSize = 36.dp()
            val controlGap = 8.dp()
            val controlsWidth = if (showSideTransport) {
                playSize + 2 * controlGap + 2 * btnSize
            } else {
                playSize
            }
            val controlsMarginEnd = 8.dp()
            infoLp?.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginStart = inlineSize + 10.dp()
                marginEnd = controlsWidth + controlsMarginEnd + 12.dp()
                topMargin = 0
            }
            artistLp?.topMargin = 2.dp()
            mediaTitle?.textSize = if (colSpan <= 2) 13f else 14f
            mediaArtist?.textSize = 12f
            mediaPrev?.visibility = if (showSideTransport) View.VISIBLE else View.GONE
            mediaNext?.visibility = if (showSideTransport) View.VISIBLE else View.GONE
            // Compact 1-row media bar: hide the output/speaker button.
            mediaOutputBtn?.visibility = View.GONE
            mediaOutputLabel?.visibility = View.GONE
            mediaOutputBtn?.setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
            outLp?.apply {
                width = 30.dp()
                height = 30.dp()
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            mediaOutputIcon?.layoutParams?.let { iconLp ->
                iconLp.width = 18.dp()
                iconLp.height = 18.dp()
            }

            ctrlLp?.apply {
                width = controlsWidth
                height = 38.dp()
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = controlsMarginEnd
            }
            positionCompactControls(showSideTransport, btnSize, playSize, controlGap)
        } else {
            val compactLand = context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            if (colSpan <= 2 && compactLand) {
                mediaContent?.setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
                val artSize = 36.dp()
                artLp?.apply {
                    width = artSize
                    height = artSize
                    gravity = Gravity.START or Gravity.TOP
                    topMargin = 2.dp()
                }
                mediaOutputBtn?.visibility = View.VISIBLE
                mediaOutputLabel?.visibility = View.GONE
                mediaOutputBtn?.setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
                outLp?.apply {
                    width = 30.dp()
                    height = 30.dp()
                    gravity = Gravity.END or Gravity.TOP
                }
                mediaOutputIcon?.layoutParams?.let { iconLp ->
                    iconLp.width = 16.dp()
                    iconLp.height = 16.dp()
                }
                infoLp?.apply {
                    gravity = Gravity.START or Gravity.TOP
                    marginStart = artSize + 8.dp()
                    marginEnd = 30.dp()
                    topMargin = 2.dp()
                }
                artistLp?.topMargin = 1.dp()
                mediaTitle?.textSize = 13f
                mediaArtist?.textSize = 11.5f
                mediaPrev?.visibility = View.VISIBLE
                mediaNext?.visibility = View.VISIBLE

                val playSize = 36.dp()
                val sideSize = 30.dp()
                val offset = 40.dp()
                ctrlLp?.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = playSize
                    gravity = Gravity.BOTTOM
                    marginEnd = 0
                }
                positionCardControls(sideSize, playSize, offset)
            } else {
                if (colSpan <= 2) {
                    mediaContent?.setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                } else {
                    mediaContent?.setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())
                }
                val artSize = when {
                    colSpan >= 4 -> 54.dp().takeIf { compactLand } ?: 60.dp()
                    colSpan == 3 -> 48.dp().takeIf { compactLand } ?: 54.dp()
                    else -> 46.dp()
                }
                artLp?.apply {
                    width = artSize
                    height = artSize
                    gravity = Gravity.START or Gravity.TOP
                    topMargin = 0
                }
                mediaOutputBtn?.visibility = View.VISIBLE
                val wideOut = colSpan >= 4
                mediaOutputLabel?.visibility = if (wideOut) View.VISIBLE else View.GONE
                mediaOutputBtn?.setPadding(8.dp(), 0, 8.dp(), 0)
                outLp?.apply {
                    width = if (wideOut) ViewGroup.LayoutParams.WRAP_CONTENT else 34.dp()
                    height = 32.dp()
                    gravity = Gravity.END or Gravity.TOP
                }
                mediaOutputIcon?.layoutParams?.let { iconLp ->
                    val sz = if (colSpan >= 3) 20.dp() else 18.dp()
                    iconLp.width = sz
                    iconLp.height = sz
                }

                infoLp?.apply {
                    gravity = Gravity.START or Gravity.TOP
                    if (colSpan >= 3) {
                        marginStart = artSize + 12.dp()
                        marginEnd = if (wideOut) 118.dp() else 40.dp()
                        topMargin = if (compactLand) 6.dp() else if (colSpan >= 4) 12.dp() else 10.dp()
                    } else {
                        marginStart = 0
                        marginEnd = 0
                        topMargin = artSize + 6.dp()
                    }
                }
                artistLp?.topMargin = if (colSpan >= 3) 3.dp() else 2.dp()
                mediaTitle?.textSize = when {
                    colSpan >= 4 -> 15f
                    colSpan == 3 -> 14f
                    else -> 14f
                }
                mediaArtist?.textSize = when {
                    colSpan >= 4 -> 13.5f
                    colSpan == 3 -> 12.5f
                    else -> 12f
                }
                mediaPrev?.visibility = View.VISIBLE
                mediaNext?.visibility = View.VISIBLE

                val playSize = when {
                    colSpan >= 4 -> 44.dp().takeIf { compactLand } ?: 50.dp()
                    colSpan == 3 -> 40.dp().takeIf { compactLand } ?: 46.dp()
                    else -> 38.dp()
                }
                val sideSize = when {
                    colSpan >= 4 -> 36.dp().takeIf { compactLand } ?: 42.dp()
                    colSpan == 3 -> 34.dp().takeIf { compactLand } ?: 38.dp()
                    else -> 32.dp()
                }
                val offset = when {
                    colSpan >= 4 -> 72.dp()
                    colSpan == 3 -> 60.dp()
                    else -> 44.dp()
                }

                ctrlLp?.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = playSize
                    gravity = Gravity.BOTTOM
                    marginEnd = 0
                }
                positionCardControls(sideSize, playSize, offset)
            }
        }
        mediaArtHolder?.requestLayout()
        mediaInfo?.requestLayout()
        mediaArtist?.requestLayout()
        mediaControls?.requestLayout()
        mediaOutputBtn?.requestLayout()
    }

    private fun positionCardControls(btnSize: Int, playSize: Int, offset: Int) {
        val sidePad = (btnSize * 0.18f).roundToInt().coerceIn(4.dp(), 9.dp())
        val playPad = (playSize * 0.21f).roundToInt().coerceIn(6.dp(), 12.dp())
        mediaPrev?.setPadding(sidePad, sidePad, sidePad, sidePad)
        mediaPlay?.setPadding(playPad, playPad, playPad, playPad)
        mediaNext?.setPadding(sidePad, sidePad, sidePad, sidePad)

        (mediaPrev?.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = btnSize
            height = btnSize
            gravity = Gravity.CENTER
            marginEnd = offset
            marginStart = 0
        }
        (mediaPlay?.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = playSize
            height = playSize
            gravity = Gravity.CENTER
            marginStart = 0
            marginEnd = 0
        }
        (mediaNext?.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = btnSize
            height = btnSize
            gravity = Gravity.CENTER
            marginStart = offset
            marginEnd = 0
        }
    }

    private fun positionCompactControls(
        showSides: Boolean,
        btnSize: Int,
        playSize: Int,
        gap: Int
    ) {
        val sidePad = 5.dp()
        val playPad = 7.dp()
        mediaPrev?.setPadding(sidePad, sidePad, sidePad, sidePad)
        mediaPlay?.setPadding(playPad, playPad, playPad, playPad)
        mediaNext?.setPadding(sidePad, sidePad, sidePad, sidePad)

        // Keep the three controls inside the container with a real gap between them instead of
        // letting the side buttons overflow the wrap-content bounds (which clipped/tightened
        // the controls against the card edge in the 1-row layout).
        val sideOffset = playSize / 2 + gap + btnSize / 2

        (mediaPrev?.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = btnSize
            height = btnSize
            gravity = Gravity.CENTER
            marginEnd = if (showSides) sideOffset else 0
            marginStart = 0
        }
        (mediaPlay?.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = playSize
            height = playSize
            gravity = Gravity.CENTER
            marginStart = 0
            marginEnd = 0
        }
        (mediaNext?.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = btnSize
            height = btnSize
            gravity = Gravity.CENTER
            marginStart = if (showSides) sideOffset else 0
            marginEnd = 0
        }
    }

    private fun freezeForEdit() {
        val appButton = rootView?.findViewById<View>(R.id.media_app_button)
        listOf<View?>(mediaBody, mediaOutputBtn, mediaPrev, mediaPlay, mediaNext, appButton).forEach { v ->
            v?.setOnClickListener(null)
            v?.setOnTouchListener(null)
            v?.isClickable = false
            v?.isFocusable = false
        }
    }

    private fun wireClickListeners() {
        mediaBody?.isClickable = true
        mediaBody?.isFocusable = true
        mediaOutputBtn?.isClickable = true
        mediaPrev?.isClickable = true
        mediaPlay?.isClickable = true
        mediaNext?.isClickable = true
        mediaBody?.setOnClickListener {
            val pending = activeController?.sessionActivity
            if (pending != null) {
                val controller = mediaBody?.let {
                    ActivityLaunchAnimator.Controller.fromView(it)
                }
                activityStarter.postStartActivityDismissingKeyguard(pending, controller)
            }
        }
        mediaOutputBtn?.setOnClickListener { v ->
            val pkg = activeController?.packageName ?: notifMediaManager.mediaNotificationKey ?: ""
            mediaOutputDialogFactory.create(pkg, true, v)
        }
        mediaPrev?.setOnClickListener {
            activeController?.transportControls?.skipToPrevious()
            handler.postDelayed({ renderState() }, 180L)
        }
        mediaPlay?.setOnClickListener {
            val ctrl = activeController ?: return@setOnClickListener
            val isPlaying = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
            if (isPlaying) {
                ctrl.transportControls.pause()
            } else {
                ctrl.transportControls.play()
            }
            handler.postDelayed({ renderState() }, 180L)
        }
        mediaNext?.setOnClickListener {
            activeController?.transportControls?.skipToNext()
            handler.postDelayed({ renderState() }, 180L)
        }
    }

    private fun refreshActiveController() {
        val next = findActiveController()
        if (next?.sessionToken != activeController?.sessionToken) {
            try {
                activeController?.unregisterCallback(sessionCallback)
            } catch (_: Throwable) {
            }
            activeController = next
            try {
                activeController?.registerCallback(sessionCallback)
            } catch (_: Throwable) {
            }
        }
        renderState()
    }

    private fun findActiveController(): MediaController? {
        return try {
            val sessions = mediaSessionManager?.getActiveSessions(null).orEmpty()
            sessions.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: sessions.firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    private fun renderState() {
        val ctrl = activeController
        val meta = ctrl?.metadata ?: notifMediaManager.mediaMetadata
        val state = ctrl?.playbackState?.state
        val isPlaying = state == PlaybackState.STATE_PLAYING
        val hasSession = ctrl != null && !TextUtils.isEmpty(ctrl.packageName)

        val title = meta?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        val artist = meta?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()?.takeIf { it.isNotBlank() }
        val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val validArt = art?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }

        val titleText = title ?: "Not Playing"
        val artistText = artist ?: if (hasSession) "" else "No active media"

        // Skip the render entirely when nothing visible changed. The periodic 5s sync otherwise
        // re-applies backgrounds and resources on every tick, forcing a relayout of the media
        // card and restarting the artist marquee, which shows up as a stutter.
        val signature = "$titleText\u0000$artistText\u0000$isPlaying\u0000$hasSession\u0000" +
                (validArt?.let { System.identityHashCode(it) } ?: 0)
        if (signature == lastRenderSignature) {
            return
        }
        lastRenderSignature = signature

        // Re-assigning the same text restarts the marquee, so only touch it when it changed.
        if (mediaTitle?.text?.toString() != titleText) {
            mediaTitle?.text = titleText
        }
        if (mediaArtist?.text?.toString() != artistText) {
            mediaArtist?.text = artistText
        }
        mediaPlay?.setImageResource(if (isPlaying) R.drawable.inv_ic_pause else R.drawable.inv_ic_play)

        val primaryText = ContextCompat.getColor(context, R.color.inv_text_primary)
        val secondaryText = ContextCompat.getColor(context, R.color.inv_text_secondary)
        val white = ContextCompat.getColor(context, R.color.inv_media_full_art_control_foreground)

        if (validArt != null) {
            mediaBody?.setBackgroundResource(R.drawable.inv_bg_media)
            mediaBlurBg?.visibility = View.VISIBLE
            mediaScrim?.visibility = View.VISIBLE
            if (lastArtwork !== validArt) {
                lastArtwork = validArt
                mediaArt?.setImageBitmap(validArt)
                val gen = ++lastBlurGeneration
                blurExecutor.execute {
                    val blurred = blurBitmap(validArt)
                    handler.post {
                        if (gen == lastBlurGeneration && mediaBlurBg != null) {
                            mediaBlurBg?.setImageBitmap(blurred ?: validArt)
                        }
                    }
                }
            }
            mediaArt?.visibility = View.VISIBLE
            mediaPlaceholderIcon?.visibility = View.GONE
            mediaArtHolder?.setBackgroundResource(R.drawable.inv_bg_media_art)

            mediaTitle?.setTextColor(white)
            mediaArtist?.setTextColor(white)
            tintMediaControls(white)
            mediaPlay?.setBackgroundResource(R.drawable.inv_bg_media_play_button)
        } else {
            lastArtwork = null
            mediaBody?.setBackgroundResource(R.drawable.inv_bg_media_empty)
            mediaBlurBg?.visibility = View.GONE
            mediaScrim?.visibility = View.GONE
            mediaArt?.visibility = View.GONE
            mediaPlaceholderIcon?.visibility = View.VISIBLE
            mediaArtHolder?.setBackgroundResource(R.drawable.inv_bg_media_art_empty)

            val iconColor = if (hasSession) primaryText else ContextCompat.getColor(context, R.color.inv_icon_muted)
            mediaTitle?.setTextColor(primaryText)
            mediaArtist?.setTextColor(secondaryText)
            tintMediaControls(iconColor)
            mediaPlay?.setBackgroundResource(R.drawable.inv_bg_media_empty_pill)
        }
    }

    private fun tintMediaControls(color: Int) {
        val csl = ColorStateList.valueOf(color)
        mediaPrev?.imageTintList = csl
        mediaPlay?.imageTintList = csl
        mediaNext?.imageTintList = csl
        mediaOutputIcon?.imageTintList = csl
    }

    private fun blurBitmap(source: Bitmap): Bitmap? {
        return try {
            if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
            val swSource = if (source.config == Bitmap.Config.HARDWARE) {
                source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            } else {
                source
            }
            val targetW = 36
            val targetH = ((swSource.height.toFloat() / swSource.width.coerceAtLeast(1)) * targetW)
                .roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(swSource, targetW, targetH, true)
            val copy = if (scaled.config == Bitmap.Config.ARGB_8888) {
                scaled
            } else {
                val argb = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                Canvas(argb).drawBitmap(scaled, 0f, 0f, null)
                argb
            }
            applyBoxBlur(copy, 2)
        } catch (_: Throwable) {
            null
        }
    }

    private fun applyBoxBlur(bmp: Bitmap, radius: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val src = IntArray(w * h)
        val dst = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        for (pass in 0..1) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0
                    for (dy in -radius..radius) {
                        val yy = (y + dy).coerceIn(0, h - 1)
                        for (dx in -radius..radius) {
                            val xx = (x + dx).coerceIn(0, w - 1)
                            val c = src[yy * w + xx]
                            r += (c ushr 16) and 0xFF
                            g += (c ushr 8) and 0xFF
                            b += c and 0xFF
                            count++
                        }
                    }
                    dst[y * w + x] = Color.argb(0xFF, r / count, g / count, b / count)
                }
            }
            System.arraycopy(dst, 0, src, 0, src.size)
        }
        bmp.setPixels(dst, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).roundToInt()
}
