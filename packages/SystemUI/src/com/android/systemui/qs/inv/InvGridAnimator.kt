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

import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.systemui.R

/**
 * Provides Android 17 style bouncy spring physics and overshoot bounds transitions
 * when tiles or controls resize, reorder, or reflow inside Extended QS grids.
 */
object InvGridAnimator {

    fun beginBouncyGridTransition(container: ViewGroup) {
        val transition = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(
                ChangeBounds().apply {
                    duration = 380L
                    interpolator = OvershootInterpolator(1.18f)
                }
            )
            addTransition(Fade(Fade.IN).apply { duration = 200L })
            addTransition(Fade(Fade.OUT).apply { duration = 140L })
        }
        TransitionManager.endTransitions(container)
        TransitionManager.beginDelayedTransition(container, transition)
    }

    fun springScale(
        view: View,
        targetX: Float,
        targetY: Float,
        dampingRatio: Float = 0.52f,
        stiffness: Float = 380f
    ) {
        val animX = (view.getTag(R.id.inv_anim_scale_x) as? SpringAnimation)
            ?: SpringAnimation(view, DynamicAnimation.SCALE_X, targetX).also {
                view.setTag(R.id.inv_anim_scale_x, it)
            }
        val animY = (view.getTag(R.id.inv_anim_scale_y) as? SpringAnimation)
            ?: SpringAnimation(view, DynamicAnimation.SCALE_Y, targetY).also {
                view.setTag(R.id.inv_anim_scale_y, it)
            }

        animX.spring = (animX.spring ?: SpringForce()).apply {
            finalPosition = targetX
            this.dampingRatio = dampingRatio
            this.stiffness = stiffness
        }
        animY.spring = (animY.spring ?: SpringForce()).apply {
            finalPosition = targetY
            this.dampingRatio = dampingRatio
            this.stiffness = stiffness
        }
        animX.animateToFinalPosition(targetX)
        animY.animateToFinalPosition(targetY)
    }

    fun springPopIn(view: View) {
        view.scaleX = 0.86f
        view.scaleY = 0.86f
        springScale(view, 1f, 1f, dampingRatio = 0.48f, stiffness = 400f)
    }
}
