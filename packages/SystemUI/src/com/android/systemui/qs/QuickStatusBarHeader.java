/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.util.LargeScreenUtils;
import com.android.systemui.qs.TouchAnimator;
import com.android.systemui.qs.TouchAnimator.Builder;
import com.android.systemui.qs.inv.InvControlGridBinder;
import com.android.systemui.qs.inv.InvPanelConfig;

import kotlin.Unit;

/**
 * View that contains the top-most bits of the QS panel (primarily the status bar with date, time,
 * battery, carrier info and privacy icons) and also contains the {@link QuickQSPanel}.
 */
public class QuickStatusBarHeader extends FrameLayout {

    private boolean mExpanded;
    private boolean mQsDisabled;

    protected QuickQSPanel mHeaderQsPanel;

    public TouchAnimator mQQSContainerAnimator;

    private ViewGroup mQSControlLayout;
    private LinearLayout mInvQqsControlsHost;
    private InvPanelConfig mInvConfig;
    private InvControlGridBinder mInvControlsBinder;
    private boolean mInvControlsListening;
    private boolean mInvControlsDirty = true;
    private int mLastInvControlsCount = -1;
    private boolean mInvPrefRegistered;
    private QSHost mInvQsHost;
    private boolean mInvHostCallbackRegistered;

    private final QSHost.Callback mInvHostCallback = () -> {
        mInvControlsDirty = true;
        if (mInvQqsControlsHost != null && isAttachedToWindow()) {
            mInvQqsControlsHost.post(() -> rebuildInvQuickControls(true));
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener mInvPrefListener =
            (prefs, key) -> {
                mInvControlsDirty = true;
                if (mInvQqsControlsHost != null && isAttachedToWindow()) {
                    mInvQqsControlsHost.post(() -> rebuildInvQuickControls(true));
                }
            };

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mQSControlLayout = findViewById(R.id.qs_controls);
        mInvQqsControlsHost = findViewById(R.id.inv_qqs_controls_host);

        updateResources();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mInvConfig != null && !mInvPrefRegistered) {
            mInvConfig.registerListener(mInvPrefListener);
            mInvPrefRegistered = true;
        }
        if (mInvQsHost != null && !mInvHostCallbackRegistered) {
            mInvQsHost.addCallback(mInvHostCallback);
            mInvHostCallbackRegistered = true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mInvConfig != null && mInvPrefRegistered) {
            mInvConfig.unregisterListener(mInvPrefListener);
            mInvPrefRegistered = false;
        }
        if (mInvQsHost != null && mInvHostCallbackRegistered) {
            mInvQsHost.removeCallback(mInvHostCallback);
            mInvHostCallbackRegistered = false;
        }
        setInvControlsListening(false);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (TileUtils.isInvQsEnabled(mContext)) {
            if (mExpanded || mInvQqsControlsHost == null
                    || mInvQqsControlsHost.getVisibility() != View.VISIBLE
                    || mInvQqsControlsHost.getAlpha() <= 0.05f) {
                return false;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        View target = (mInvQqsControlsHost != null && mInvQqsControlsHost.getVisibility() == View.VISIBLE)
                ? mInvQqsControlsHost : mHeaderQsPanel;
        if (target != null && event.getY() > target.getTop()) {
            return super.onTouchEvent(event);
        } else {
            return false;
        }
    }

    void updateResources() {
        Resources resources = mContext.getResources();
        boolean largeScreenHeaderActive =
                LargeScreenUtils.shouldUseLargeScreenShadeHeader(resources);

        ViewGroup.LayoutParams lp = getLayoutParams();
        if (mQsDisabled) {
            lp.height = 0;
        } else {
            lp.height = WRAP_CONTENT;
        }
        setLayoutParams(lp);

        boolean mQsWidgetsEnabled = TileUtils.canShowQsWidgets(mContext);
        boolean invQs = TileUtils.isInvQsEnabled(mContext);
        boolean splitMode = TileUtils.isExtendedQsSplitMode(mContext);
        boolean showInvQuickControls = invQs && !splitMode && !mQsDisabled;

        MarginLayoutParams qqsLP = (MarginLayoutParams) mHeaderQsPanel.getLayoutParams();
        int qqsTopMargin = mContext.getResources()
                    .getDimensionPixelSize(largeScreenHeaderActive 
                    ? R.dimen.qqs_layout_margin_top 
                    : R.dimen.large_screen_shade_header_min_height);
        qqsLP.topMargin = mQsWidgetsEnabled ? 0 : qqsTopMargin;
        mHeaderQsPanel.setLayoutParams(qqsLP);
        mHeaderQsPanel.setVisibility(invQs ? View.GONE : View.VISIBLE);

        if (mInvQqsControlsHost != null) {
            int shadeHeaderTopPad = invQs
                    ? resources.getDimensionPixelSize(R.dimen.inv_shade_header_top_padding)
                    : 0;
            MarginLayoutParams invLp = (MarginLayoutParams) mInvQqsControlsHost.getLayoutParams();
            int targetInvTopMargin = qqsTopMargin + shadeHeaderTopPad;
            if (invLp.topMargin != targetInvTopMargin) {
                invLp.topMargin = targetInvTopMargin;
                mInvQqsControlsHost.setLayoutParams(invLp);
            }
            mInvQqsControlsHost.setVisibility(showInvQuickControls ? View.VISIBLE : View.GONE);
            if (showInvQuickControls) {
                ensureInvControlsBinder();
                rebuildInvQuickControls(false);
            } else {
                setInvControlsListening(false);
            }
        }

        if (mQsWidgetsEnabled && !mQsDisabled) {
            mQSControlLayout.setVisibility(View.VISIBLE);
            MarginLayoutParams qsControlsLp = (MarginLayoutParams) mQSControlLayout.getLayoutParams();
            int qqsMarginTop = resources.getDimensionPixelSize(largeScreenHeaderActive ?
                                R.dimen.qqs_layout_margin_top : R.dimen.large_screen_shade_header_min_height);
            qsControlsLp.topMargin = qqsMarginTop;
            mQSControlLayout.setLayoutParams(qsControlsLp);

            float qqsExpandY = resources.getDimensionPixelSize(R.dimen.qs_header_height)
                                + resources.getDimensionPixelSize(R.dimen.qs_controls_top_margin)
                                - qqsMarginTop;
            TouchAnimator.Builder builderP = new TouchAnimator.Builder()
                .addFloat(mQSControlLayout, "translationY", 0, qqsExpandY);
            mQQSContainerAnimator = builderP.build();
        } else {
            mQSControlLayout.setVisibility(View.GONE);
        }
    }

    public void setExpanded(boolean expanded, QuickQSPanelController quickQSPanelController) {
        boolean invQs = TileUtils.isInvQsEnabled(mContext);
        boolean splitMode = TileUtils.isExtendedQsSplitMode(mContext);
        if (mHeaderQsPanel != null) {
            mHeaderQsPanel.setVisibility(invQs ? View.GONE : View.VISIBLE);
        }
        if (mInvQqsControlsHost != null) {
            boolean showInvQuickControls = invQs && !splitMode && !mQsDisabled;
            if (!showInvQuickControls) {
                mInvQqsControlsHost.setVisibility(View.GONE);
            } else if (expanded) {
                mInvQqsControlsHost.setVisibility(View.INVISIBLE);
            } else {
                mInvQqsControlsHost.setVisibility(View.VISIBLE);
            }
        }
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        quickQSPanelController.setExpanded(expanded);
    }

    public void setExpansion(boolean forceExpanded, float expansionFraction, float panelTranslationY) {
        if (mInvQqsControlsHost != null && TileUtils.isInvQsEnabled(mContext)
                && !TileUtils.isExtendedQsSplitMode(mContext) && !mQsDisabled) {
            float fade = forceExpanded ? 0f : Math.max(0f, 1f - expansionFraction * 2.2f);
            mInvQqsControlsHost.setAlpha(fade);
            mInvQqsControlsHost.setTranslationY(-expansionFraction * 24f);
            int targetVis = fade <= 0.02f ? View.INVISIBLE : View.VISIBLE;
            if (mInvQqsControlsHost.getVisibility() != targetVis) {
                mInvQqsControlsHost.setVisibility(targetVis);
            }
        }
        if (!TileUtils.canShowQsWidgets(mContext)) return;
        if (mQQSContainerAnimator != null) {
            mQQSContainerAnimator.setPosition(forceExpanded ? 1f : expansionFraction);
        }
        setAlpha(forceExpanded ? expansionFraction : 1);
    }

    void setInvControlsListening(boolean listening) {
        if (mInvControlsListening == listening) return;
        mInvControlsListening = listening;
        if (mInvControlsBinder != null) {
            if (listening && mInvQqsControlsHost != null
                    && mInvQqsControlsHost.getVisibility() == View.VISIBLE) {
                mInvControlsBinder.startListening();
            } else {
                mInvControlsBinder.stopListening();
            }
        }
    }

    void setInvQsHost(QSHost host) {
        if (mInvQsHost == host) return;
        if (mInvQsHost != null && mInvHostCallbackRegistered) {
            mInvQsHost.removeCallback(mInvHostCallback);
            mInvHostCallbackRegistered = false;
        }
        mInvQsHost = host;
        if (mInvControlsBinder != null) {
            mInvControlsBinder.bindHost(host);
        }
        if (mInvQsHost != null && isAttachedToWindow() && !mInvHostCallbackRegistered) {
            mInvQsHost.addCallback(mInvHostCallback);
            mInvHostCallbackRegistered = true;
        }
        mInvControlsDirty = true;
        if (mInvQqsControlsHost != null && isAttachedToWindow()) {
            rebuildInvQuickControls(true);
        }
    }

    private void ensureInvControlsBinder() {
        if (mInvConfig == null) {
            mInvConfig = new InvPanelConfig(mContext);
            if (isAttachedToWindow() && !mInvPrefRegistered) {
                mInvConfig.registerListener(mInvPrefListener);
                mInvPrefRegistered = true;
            }
        }
        if (mInvControlsBinder == null) {
            mInvControlsBinder = new InvControlGridBinder(mContext, mInvConfig);
        }
        if (mInvQsHost != null) {
            mInvControlsBinder.bindHost(mInvQsHost);
        }
    }

    private void rebuildInvQuickControls(boolean force) {
        if (!isAttachedToWindow() && !force) return;
        if (mInvQqsControlsHost == null || mInvControlsBinder == null || mInvConfig == null) return;
        int targetCount = mInvConfig.getQqsControlCount();
        if (!force && !mInvControlsDirty && mLastInvControlsCount == targetCount
                && mInvQqsControlsHost.getChildCount() > 0) {
            return;
        }
        mInvControlsDirty = false;
        mLastInvControlsCount = targetCount;
        mInvControlsBinder.rebuild(mInvQqsControlsHost, false, targetCount, Integer.MAX_VALUE, () -> {
            rebuildInvQuickControls(true);
            return Unit.INSTANCE;
        });
        if (mInvControlsListening) {
            mInvControlsBinder.refreshDynamicValues();
        }
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mHeaderQsPanel.setDisabledByPolicy(disabled);
        updateResources();
    }

    private void setContentMargins(View view, int marginStart, int marginEnd) {
        MarginLayoutParams lp = (MarginLayoutParams) view.getLayoutParams();
        lp.setMarginStart(marginStart);
        lp.setMarginEnd(marginEnd);
        view.setLayoutParams(lp);
    }
}
