/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.policy.IKeyguardShowCallback;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;

import com.mediatek.keyguard.PowerOffAlarm.PowerOffAlarmManager ;
import com.mediatek.keyguard.VoiceWakeup.VoiceWakeupManager ;

import static com.android.keyguard.KeyguardHostView.OnDismissAction;

/**
 * Manages creating, showing, hiding and resetting the keyguard within the status bar. Calls back
 * via {@link ViewMediatorCallback} to poke the wake lock and report that the keyguard is done,
 * which is in turn, reported to this class by the current
 * {@link com.android.keyguard.KeyguardViewBase}.
 */
public class StatusBarKeyguardViewManager {

    // When hiding the Keyguard with timing supplied from WindowManager, better be early than late.
    private static final long HIDE_TIMING_CORRECTION_MS = -3 * 16;

    // Delay for showing the navigation bar when the bouncer appears. This should be kept in sync
    // with the appear animations of the PIN/pattern/password views.
    private static final long NAV_BAR_SHOW_DELAY_BOUNCER = 320;

    private static String TAG = "StatusBarKeyguardViewManager";
    private final boolean DEBUG = true ;

    private final Context mContext;

    private LockPatternUtils mLockPatternUtils;
    private ViewMediatorCallback mViewMediatorCallback;
    private PhoneStatusBar mPhoneStatusBar;
    private ScrimController mScrimController;

    private ViewGroup mContainer;
    private StatusBarWindowManager mStatusBarWindowManager;

    private boolean mScreenOn = false;
    private KeyguardBouncer mBouncer;
    ///shuyong hall 
    private MalataHall mHall;
    private boolean mShowing;
    private boolean mOccluded;

    private boolean mFirstUpdate = true;
    private boolean mLastShowing;
    private boolean mLastOccluded;
    private boolean mLastBouncerShowing;
    private boolean mLastBouncerDismissible;
    private OnDismissAction mAfterKeyguardGoneAction;

    public StatusBarKeyguardViewManager(Context context, ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils) {
        mContext = context;
        mViewMediatorCallback = callback;
        mLockPatternUtils = lockPatternUtils;
    }

    public void registerStatusBar(PhoneStatusBar phoneStatusBar,
            ViewGroup container, StatusBarWindowManager statusBarWindowManager,
            ScrimController scrimController) {
        mPhoneStatusBar = phoneStatusBar;
        mContainer = container;
        mStatusBarWindowManager = statusBarWindowManager;
        mScrimController = scrimController;
        mBouncer = new KeyguardBouncer(mContext, mViewMediatorCallback, mLockPatternUtils,
                mStatusBarWindowManager, container);

        mHall = new MalataHall(mContext, mViewMediatorCallback, mStatusBarWindowManager, container);
    }

    /**
     * Show the keyguard.  Will handle creating and attaching to the view manager
     * lazily.
     */
    public void show(Bundle options) {
        if (DEBUG) Log.d(TAG, "show() is called.") ;
        mShowing = true;
        mStatusBarWindowManager.setKeyguardShowing(true);
        reset();
    }

    /**
     * Shows the notification keyguard or the bouncer depending on
     * {@link KeyguardBouncer#needsFullscreenBouncer()}.
     */
    private void showBouncerOrKeyguard() {
        if (DEBUG) Log.d(TAG, "showBouncerOrKeyguard() is called.") ;

        if (mBouncer.needsFullscreenBouncer()) {
            if (DEBUG) Log.d(TAG, "needsFullscreenBouncer() is true, show \"Bouncer\" view directly.") ;
            // The keyguard might be showing (already). So we need to hide it.
            mPhoneStatusBar.hideKeyguard();
            mBouncer.show();
        } else {
            if (DEBUG) Log.d(TAG, "needsFullscreenBouncer() is false, show \"Notification Keyguard\" view.") ;
            mPhoneStatusBar.showKeyguard();
            mBouncer.hide(false /* destroyView */);
            mBouncer.prepare();
        }
    }

    //shuyong begin
    public void showHall(){
        mHall.show();
    }
    public void hideHall(){
        mHall.hide(false);
    }
    //shuyong end

    private void showBouncer() {
        showBouncer(false) ;
    }

    private void showBouncer(boolean authenticated) {
        if (mShowing) {
            mBouncer.show(authenticated);
        }
        updateStates();
    }

    public void dismissWithAction(OnDismissAction r, boolean afterKeyguardGone) {
        if (DEBUG) Log.d(TAG, "dismissWithAction() is called.") ;

        if (mShowing) {
            if (!afterKeyguardGone) {
                mBouncer.showWithDismissAction(r);
            } else {
                mBouncer.show();
                mAfterKeyguardGoneAction = r;
            }
        }
        updateStates();
    }

    /**
     * Reset the state of the view.
     */
    public void reset() {
        if (DEBUG) Log.d(TAG, "reset() is called, mShowing = " + mShowing + " ,mOccluded = " + mOccluded) ;
        if (mShowing) {
            if (mOccluded) {
                mPhoneStatusBar.hideKeyguard();
                mBouncer.hide(false /* destroyView */);
            } else {
                showBouncerOrKeyguard();
            }
            updateStates();
        }
    }

    public void onScreenTurnedOff() {
        mScreenOn = false;
        mPhoneStatusBar.onScreenTurnedOff();
        mBouncer.onScreenTurnedOff();
        ///shuyong hall
        mHall.onScreenTurnedOff();
    }

    public void onScreenTurnedOn(final IKeyguardShowCallback callback) {
        mScreenOn = true;
        mPhoneStatusBar.onScreenTurnedOn();
        if (callback != null) {
            callbackAfterDraw(callback);
        }

        ///shuyong hall
        mHall.onScreenTurnedOn();
    }

    private void callbackAfterDraw(final IKeyguardShowCallback callback) {
        if (PowerOffAlarmManager.isAlarmBoot() && !mBouncer.isContainerInflated()) {
            Log.d(TAG, "callbackAfterDraw() - AlarmBoot, but Bouncer container not inflated yet." +
                "Do not call callback to avoid turning on screen too early.") ;
            return ;
        }

        mContainer.post(new Runnable() {
            @Override
            public void run() {
                try {
                    callback.onShown(mContainer.getWindowToken());
                } catch (RemoteException e) {
                    Slog.w(TAG, "Exception calling onShown():", e);
                }
            }
        });
    }

    public void verifyUnlock() {
        ///M: fix ALPS01807921
        ///   We make VerifyUnlock flow just the same as it in KK.
        ///   This can prevent status unsynced.
        show(null);
        dismiss();
    }

    public void setNeedsInput(boolean needsInput) {
        mStatusBarWindowManager.setKeyguardNeedsInput(needsInput);
    }

    public void updateUserActivityTimeout() {
        mStatusBarWindowManager.setKeyguardUserActivityTimeout(mBouncer.getUserActivityTimeout());
    }

    public void setOccluded(boolean occluded) {
        if (occluded && !mOccluded && mShowing) {
            if (mPhoneStatusBar.isInLaunchTransition()) {
                mOccluded = true;
                mPhoneStatusBar.fadeKeyguardAfterLaunchTransition(null /* beforeFading */,
                        new Runnable() {
                            @Override
                            public void run() {
                                mStatusBarWindowManager.setKeyguardOccluded(true);
                                reset();
                            }
                        });
                return;
            }
        }
        mOccluded = occluded;
        mStatusBarWindowManager.setKeyguardOccluded(occluded);
        reset();
    }

    public boolean isOccluded() {
        return mOccluded;
    }

    /**
     * Starts the animation before we dismiss Keyguard, i.e. an disappearing animation on the
     * security view of the bouncer.
     *
     * @param finishRunnable the runnable to be run after the animation finished, or {@code null} if
     *                       no action should be run
     */
    public void startPreHideAnimation(Runnable finishRunnable) {
        if (mBouncer.isShowing()) {
            mBouncer.startPreHideAnimation(finishRunnable);
        } else if (finishRunnable != null) {
            finishRunnable.run();
        }
    }

    /**
     * Hides the keyguard view
     */

    public void hide(long startTime, final long fadeoutDuration) {
        if (DEBUG) Log.d(TAG, "hide() is called.") ;

        mShowing = false;

        long uptimeMillis = SystemClock.uptimeMillis();
        long delay = Math.max(0, startTime + HIDE_TIMING_CORRECTION_MS - uptimeMillis);

        if (mPhoneStatusBar.isInLaunchTransition() ) {
            mPhoneStatusBar.fadeKeyguardAfterLaunchTransition(new Runnable() {
                @Override
                public void run() {
                    mStatusBarWindowManager.setKeyguardShowing(false);
                    mStatusBarWindowManager.setKeyguardFadingAway(true);
                    mBouncer.hide(true /* destroyView */);
                    updateStates();
                    mScrimController.animateKeyguardFadingOut(
                            PhoneStatusBar.FADE_KEYGUARD_START_DELAY,
                            PhoneStatusBar.FADE_KEYGUARD_DURATION, null);
                }
            }, new Runnable() {
                @Override
                public void run() {
                    mPhoneStatusBar.hideKeyguard();
                    mStatusBarWindowManager.setKeyguardFadingAway(false);
                    mViewMediatorCallback.keyguardGone();
                    executeAfterKeyguardGoneAction();
                }
            });
        } else {
            mPhoneStatusBar.setKeyguardFadingAway(delay, fadeoutDuration);
            boolean staying = mPhoneStatusBar.hideKeyguard();
            if (!staying) {
                mStatusBarWindowManager.setKeyguardFadingAway(true);
                mScrimController.animateKeyguardFadingOut(delay, fadeoutDuration, new Runnable() {
                    @Override
                    public void run() {
                        mStatusBarWindowManager.setKeyguardFadingAway(false);
                        mPhoneStatusBar.finishKeyguardFadingAway();
                    }
                });
            } else {
                mScrimController.animateGoingToFullShade(delay, fadeoutDuration);
                mPhoneStatusBar.finishKeyguardFadingAway();
            }
            mStatusBarWindowManager.setKeyguardShowing(false);
            mBouncer.hide(true /* destroyView */);
            mViewMediatorCallback.keyguardGone();
            executeAfterKeyguardGoneAction();
            updateStates();
        }

    }

    private void executeAfterKeyguardGoneAction() {
        if (mAfterKeyguardGoneAction != null) {
            mAfterKeyguardGoneAction.onDismiss();
            mAfterKeyguardGoneAction = null;
        }
    }

    /**
     * Dismisses the keyguard by going to the next screen or making it gone.
     */
    public void dismiss() {
        dismiss(false) ;
    }

    public void dismiss(boolean authenticated) {
        if (DEBUG) Log.d(TAG, "dismiss(authenticated = " + authenticated + ") is called." +
             " mScreenOn = " + mScreenOn) ;
        // VoiceWakeup will need to dismiss keyguard even if screen is off.
        if (mScreenOn || VoiceWakeupManager.getInstance().isDismissAndLaunchApp()) {
            showBouncer(authenticated);
        }
    }

    public boolean isSecure() {
        return mBouncer.isSecure();
    }

    /**
     * @return Whether the keyguard is showing
     */
    public boolean isShowing() {
        return mShowing;
    }

    /**
     * Notifies this manager that the back button has been pressed.
     *
     * @return whether the back press has been handled
     */
    public boolean onBackPressed() {
        if (mBouncer.isShowing()) {
            reset();
            return true;
        }
        return false;
    }

    public boolean isBouncerShowing() {
        return mBouncer.isShowing();
    }

    private long getNavBarShowDelay() {
        if (mPhoneStatusBar.isKeyguardFadingAway()) {
            return mPhoneStatusBar.getKeyguardFadingAwayDelay();
        } else {

            // Keyguard is not going away, thus we are showing the navigation bar because the
            // bouncer is appearing.
            return NAV_BAR_SHOW_DELAY_BOUNCER;
        }
    }

    private Runnable mMakeNavigationBarVisibleRunnable = new Runnable() {
        @Override
        public void run() {
            mPhoneStatusBar.getNavigationBarView().setVisibility(View.VISIBLE);
        }
    };

    /**
     * Update Navi-bar visibility, BACK key visibility, and notify ScrimController/PhoneStatusBar/StatusBarWindowManager that bouncer show/hide.
     *
     */
    public void updateStates() {
        int vis = mContainer.getSystemUiVisibility();
        boolean showing = mShowing;
        boolean occluded = mOccluded;
        boolean bouncerShowing = mBouncer.isShowing();
        boolean bouncerDismissible = !mBouncer.needsFullscreenBouncer();

        if ((bouncerDismissible || !showing) != (mLastBouncerDismissible || !mLastShowing)
                || mFirstUpdate) {
            if (bouncerDismissible || !showing) {
                mContainer.setSystemUiVisibility(vis & ~View.STATUS_BAR_DISABLE_BACK);
            } else {
                mContainer.setSystemUiVisibility(vis | View.STATUS_BAR_DISABLE_BACK);
            }
        }
        if ((!(showing && !occluded) || bouncerShowing)
                != (!(mLastShowing && !mLastOccluded) || mLastBouncerShowing) || mFirstUpdate) {
            if (mPhoneStatusBar.getNavigationBarView() != null) {
                if (!(showing && !occluded) || bouncerShowing) {
                    mContainer.postOnAnimationDelayed(mMakeNavigationBarVisibleRunnable,
                            getNavBarShowDelay());
                } else {
                    mContainer.removeCallbacks(mMakeNavigationBarVisibleRunnable);
                    mPhoneStatusBar.getNavigationBarView().setVisibility(View.GONE);
                }
            }
        }

        if (bouncerShowing != mLastBouncerShowing || mFirstUpdate) {
            mStatusBarWindowManager.setBouncerShowing(bouncerShowing);
            mPhoneStatusBar.setBouncerShowing(bouncerShowing);
            mScrimController.setBouncerShowing(bouncerShowing);
        }

        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        if ((showing && !occluded) != (mLastShowing && !mLastOccluded) || mFirstUpdate) {
            updateMonitor.sendKeyguardVisibilityChanged(showing && !occluded);
        }
        if (bouncerShowing != mLastBouncerShowing || mFirstUpdate) {
            updateMonitor.sendKeyguardBouncerChanged(bouncerShowing);
        }

        mFirstUpdate = false;
        mLastShowing = showing;
        mLastOccluded = occluded;
        mLastBouncerShowing = bouncerShowing;
        mLastBouncerDismissible = bouncerDismissible;
    }

    public boolean onMenuPressed() {
        return mBouncer.onMenuPressed();
    }

    public boolean interceptMediaKey(KeyEvent event) {
        return mBouncer.interceptMediaKey(event);
    }

    public void onActivityDrawn() {
        if (mPhoneStatusBar.isCollapsing()) {
            mPhoneStatusBar.addPostCollapseAction(new Runnable() {
                @Override
                public void run() {
                    mViewMediatorCallback.readyForKeyguardDone();
                }
            });
        } else {
            mViewMediatorCallback.readyForKeyguardDone();
        }
    }

    public boolean shouldDisableWindowAnimationsForUnlock() {
        return mPhoneStatusBar.isInLaunchTransition();
    }

    public boolean isGoingToNotificationShade() {
        return mPhoneStatusBar.isGoingToNotificationShade();
    }
}