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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Base64
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer

/** TextView that keeps horizontal marquee active while visible without stealing focus. */
class InvMarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    init {
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        setSingleLine(true)
        isHorizontalFadingEdgeEnabled = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isSelected = visibility == View.VISIBLE
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        isSelected = isAttachedToWindow && visibility == View.VISIBLE
    }

    override fun isFocused(): Boolean = true
}

/** ImageView supporting static images and animated GIF/WebP from custom InvQs settings. */
class InvGifImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private var currentSpec: String? = null
    private var currentAnimate: Boolean = false
    private var activeAnimated: AnimatedImageDrawable? = null

    fun loadFromSettings(): Boolean {
        val resolver = context.contentResolver
        val spec = Settings.System.getStringForUser(
            resolver,
            "invos_qs_custom_image",
            UserHandle.USER_CURRENT
        )?.trim().orEmpty()
        val animate = Settings.System.getIntForUser(
            resolver,
            "invos_qs_custom_gif_animate",
            0,
            UserHandle.USER_CURRENT
        ) != 0
        val alphaInt = Settings.System.getIntForUser(
            resolver,
            "invos_qs_custom_image_alpha",
            255,
            UserHandle.USER_CURRENT
        ).coerceIn(0, 255)
        imageAlpha = alphaInt

        if (spec.isEmpty()) {
            clearImage()
            return false
        }
        if (spec == currentSpec && animate == currentAnimate && drawable != null) {
            updateAnimationState()
            return true
        }

        currentSpec = spec
        currentAnimate = animate
        stopAnimation()

        val loadedDrawable = decodeSpecDrawable(spec, animate)
        if (loadedDrawable != null) {
            setImageDrawable(loadedDrawable)
            if (loadedDrawable is AnimatedImageDrawable) {
                activeAnimated = loadedDrawable
                updateAnimationState()
            }
            return true
        }
        clearImage()
        return false
    }

    fun clearImage() {
        stopAnimation()
        currentSpec = null
        setImageDrawable(null)
        try {
            val cached = File(context.filesDir, CACHE_FILE_NAME)
            if (cached.exists()) cached.delete()
            context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CACHED_SPEC)
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun decodeSpecDrawable(spec: String, animate: Boolean): Drawable? {
        return try {
            if (spec.startsWith("data:image/")) {
                val b64 = spec.substringAfter("base64,", "")
                if (b64.isEmpty()) return null
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                return if (animate) {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        val maxDim = 720
                        val w = info.size.width
                        val h = info.size.height
                        if (w > maxDim || h > maxDim) {
                            decoder.setTargetSampleSize(maxOf(w / maxDim, h / maxDim, 1))
                        }
                    }
                } else {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                        android.graphics.drawable.BitmapDrawable(resources, it)
                    }
                }
            }

            val sourceFile = if (spec.startsWith("content://") || spec.startsWith("file://")) {
                cacheUriLocally(spec)
            } else {
                File(spec).takeIf { it.exists() }
            }

            val source = when {
                sourceFile != null && sourceFile.exists() -> ImageDecoder.createSource(sourceFile)
                spec.startsWith("content://") || spec.startsWith("file://") ->
                    ImageDecoder.createSource(context.contentResolver, Uri.parse(spec))
                else -> return null
            }
            if (animate) {
                ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    val maxDim = 720
                    val w = info.size.width
                    val h = info.size.height
                    if (w > maxDim || h > maxDim) {
                        decoder.setTargetSampleSize(maxOf(w / maxDim, h / maxDim, 1))
                    } else {
                        decoder.setTargetSampleSize(1)
                    }
                }
            } else {
                val bitmap: Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val maxDim = 720
                    val w = info.size.width
                    val h = info.size.height
                    if (w > maxDim || h > maxDim) {
                        val scale = maxOf(w / maxDim, h / maxDim, 1)
                        decoder.setTargetSampleSize(scale)
                    }
                }
                android.graphics.drawable.BitmapDrawable(resources, bitmap)
            }
        } catch (_: Throwable) {
            try {
                val cached = File(context.filesDir, CACHE_FILE_NAME)
                val fallbackFile = if (cached.exists()) cached else File(spec)
                if (fallbackFile.exists()) {
                    BitmapFactory.decodeFile(fallbackFile.absolutePath)?.let {
                        android.graphics.drawable.BitmapDrawable(resources, it)
                    }
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun cacheUriLocally(spec: String): File? {
        val prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        val cachedFile = File(context.filesDir, CACHE_FILE_NAME)
        if (prefs.getString(KEY_CACHED_SPEC, null) == spec && cachedFile.exists() && cachedFile.length() > 0L) {
            return cachedFile
        }
        return try {
            val uri = Uri.parse(spec)
            val tmpFile = File(context.filesDir, "$CACHE_FILE_NAME.tmp")
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
                true
            } ?: false
            if (copied && tmpFile.exists() && tmpFile.length() > 0L) {
                if (cachedFile.exists()) cachedFile.delete()
                tmpFile.renameTo(cachedFile)
                prefs.edit().putString(KEY_CACHED_SPEC, spec).apply()
                cachedFile
            } else {
                tmpFile.delete()
                cachedFile.takeIf { it.exists() }
            }
        } catch (_: Throwable) {
            cachedFile.takeIf { it.exists() }
        }
    }

    companion object {
        private const val PREFS_CACHE = "inv_custom_image_cache_prefs"
        private const val KEY_CACHED_SPEC = "cached_spec"
        private const val CACHE_FILE_NAME = "invqs_custom_image_cache"
    }

    private fun updateAnimationState() {
        val anim = activeAnimated ?: return
        if (isAttachedToWindow && isShown && currentAnimate) {
            if (!anim.isRunning) anim.start()
        } else {
            if (anim.isRunning) anim.stop()
        }
    }

    private fun stopAnimation() {
        activeAnimated?.let {
            if (it.isRunning) it.stop()
        }
        activeAnimated = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimationState()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
}
