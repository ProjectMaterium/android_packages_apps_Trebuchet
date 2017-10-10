/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.launcher3;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;

import com.android.launcher3.allapps.AllAppsContainerView;
import com.android.launcher3.allapps.AllAppsTransitionController;
import com.android.launcher3.anim.AnimationLayerSet;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.util.Thunk;

/**
 * TODO: figure out what kind of tests we can write for this
 *
 * Things to test when changing the following class.
 *   - Home from workspace
 *          - from center screen
 *          - from other screens
 *   - Home from all apps
 *          - from center screen
 *          - from other screens
 *   - Back from all apps
 *          - from center screen
 *          - from other screens
 *   - Launch app from workspace and quit
 *          - with back
 *          - with home
 *   - Launch app from all apps and quit
 *          - with back
 *          - with home
 *   - Go to a screen that's not the default, then all
 *     apps, and launch and app, and go back
 *          - with back
 *          -with home
 *   - On workspace, long press power and go back
 *          - with back
 *          - with home
 *   - On all apps, long press power and go back
 *          - with back
 *          - with home
 *   - On workspace, power off
 *   - On all apps, power off
 *   - Launch an app and turn off the screen while in that app
 *          - Go back with home key
 *          - Go back with back key  TODO: make this not go to workspace
 *          - From all apps
 *          - From workspace
 *   - Enter and exit car mode (becuase it causes an extra configuration changed)
 *          - From all apps
 *          - From the center workspace
 *          - From another workspace
 */
public class LauncherStateTransitionAnimation {

    public static final String TAG = "LSTAnimation";

    @Thunk Launcher mLauncher;
    @Thunk AnimatorSet mCurrentAnimation;
    AllAppsTransitionController mAllAppsController;

    public LauncherStateTransitionAnimation(Launcher l, AllAppsTransitionController allAppsController) {
        mLauncher = l;
        mAllAppsController = allAppsController;
    }

    /**
     * Starts an animation to the apps view.
     */
    public void startAnimationToAllApps(final boolean animated) {
        final AllAppsContainerView toView = mLauncher.getAppsView();

        final AnimatorSet animation = LauncherAnimUtils.createAnimatorSet();
        final Resources res = mLauncher.getResources();
        final int revealDurationSlide = res.getInteger(R.integer.config_overlaySlideRevealTime);

        final AnimationLayerSet layerViews = new AnimationLayerSet();

        // If for some reason our views aren't initialized, don't animate
        boolean initialized = toView != null;

        // Cancel the current animation
        cancelAnimation();

        playCommonTransitionAnimations(Workspace.State.NORMAL_HIDDEN,
                animated, initialized, animation, layerViews);
        if (!animated || !initialized) {
            mAllAppsController.finishPullUp();
            toView.setTranslationX(0.0f);
            toView.setTranslationY(0.0f);
            toView.setScaleX(1.0f);
            toView.setScaleY(1.0f);
            toView.setAlpha(1.0f);
            toView.setVisibility(View.VISIBLE);

            mLauncher.getUserEventDispatcher().resetElapsedContainerMillis();
            return;
        }
        if (!FeatureFlags.LAUNCHER3_PHYSICS) {
            // We are animating the content view alpha, so ensure we have a layer for it.
            layerViews.addView(toView);
        }

        animation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                cleanupAnimation();
                mLauncher.getUserEventDispatcher().resetElapsedContainerMillis();
            }
        });
        boolean shouldPost = mAllAppsController.animateToAllApps(animation, revealDurationSlide);

        Runnable startAnimRunnable = new StartAnimRunnable(animation, toView);
        mCurrentAnimation = animation;
        mCurrentAnimation.addListener(layerViews);
        if (shouldPost) {
            toView.post(startAnimRunnable);
        } else {
            startAnimRunnable.run();
        }
    }

    /**
     * Starts an animation to the widgets view.
     */
    public void startAnimationToWidgets(final boolean animated) {
        // TODO: Remove this
        throw new RuntimeException("This cannot happen");
    }

    /**
     * Starts an animation to the workspace from the current overlay view.
     */
    public void startAnimationToWorkspace(final Launcher.State fromState,
            final Workspace.State fromWorkspaceState, final Workspace.State toWorkspaceState,
            final boolean animated, final Runnable onCompleteRunnable) {
        if (toWorkspaceState != Workspace.State.NORMAL &&
                toWorkspaceState != Workspace.State.SPRING_LOADED &&
                toWorkspaceState != Workspace.State.OVERVIEW) {
            Log.e(TAG, "Unexpected call to startAnimationToWorkspace");
        }

        if (fromState == Launcher.State.APPS || fromState == Launcher.State.APPS_SPRING_LOADED
                || mAllAppsController.isTransitioning()) {
            startAnimationToWorkspaceFromAllApps(fromWorkspaceState, toWorkspaceState,
                    animated, onCompleteRunnable);
        } else {
            startAnimationToNewWorkspaceState(fromWorkspaceState, toWorkspaceState,
                    animated, onCompleteRunnable);
        }
    }

    /**
     * Plays animations used by various transitions.
     */
    private void playCommonTransitionAnimations(
            Workspace.State toWorkspaceState,
            boolean animated, boolean initialized, AnimatorSet animation,
            AnimationLayerSet layerViews) {
        // Create the workspace animation.
        // NOTE: this call apparently also sets the state for the workspace if !animated
        Animator workspaceAnim = mLauncher.getWorkspace().
                setStateWithAnimation(toWorkspaceState, animated, layerViews);

        if (animated && initialized) {
            // Play the workspace animation
            if (workspaceAnim != null) {
                animation.play(workspaceAnim);
            }
        }
    }

    /**
     * Starts an animation to the workspace from the apps view.
     */
    private void startAnimationToWorkspaceFromAllApps(final Workspace.State fromWorkspaceState,
            final Workspace.State toWorkspaceState, final boolean animated,
            final Runnable onCompleteRunnable) {

        final AllAppsContainerView fromView = mLauncher.getAppsView();
        final AnimatorSet animation = LauncherAnimUtils.createAnimatorSet();
        final Resources res = mLauncher.getResources();
        final int revealDurationSlide = res.getInteger(R.integer.config_overlaySlideRevealTime);

        final View toView = mLauncher.getWorkspace();

        final AnimationLayerSet layerViews = new AnimationLayerSet();

        // If for some reason our views aren't initialized, don't animate
        boolean initialized = fromView != null;

        // Cancel the current animation
        cancelAnimation();

        playCommonTransitionAnimations(toWorkspaceState,
                animated, initialized, animation, layerViews);
        if (!animated || !initialized) {
            if (fromWorkspaceState == Workspace.State.NORMAL_HIDDEN) {
                mAllAppsController.finishPullDown();
            }
            fromView.setVisibility(View.GONE);
            mLauncher.getUserEventDispatcher().resetElapsedContainerMillis();

            // Run any queued runnables
            if (onCompleteRunnable != null) {
                onCompleteRunnable.run();
            }
            return;
        }

        // We are animating the content view alpha, so ensure we have a layer for it
        layerViews.addView(toView);

        animation.addListener(new AnimatorListenerAdapter() {
            boolean canceled = false;
            @Override
            public void onAnimationCancel(Animator animation) {
                canceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (canceled) return;
                // Run any queued runnables
                if (onCompleteRunnable != null) {
                    onCompleteRunnable.run();
                }

                cleanupAnimation();
                mLauncher.getUserEventDispatcher().resetElapsedContainerMillis();
            }

        });
        boolean shouldPost = mAllAppsController.animateToWorkspace(animation, revealDurationSlide);

        Runnable startAnimRunnable = new StartAnimRunnable(animation, toView);
        mCurrentAnimation = animation;
        mCurrentAnimation.addListener(layerViews);
        if (shouldPost) {
            fromView.post(startAnimRunnable);
        } else {
            startAnimRunnable.run();
        }
    }

    /**
     * Starts an animation to the workspace from another workspace state, e.g. normal to overview.
     */
    private void startAnimationToNewWorkspaceState(final Workspace.State fromWorkspaceState,
            final Workspace.State toWorkspaceState, final boolean animated,
            final Runnable onCompleteRunnable) {
        final View fromWorkspace = mLauncher.getWorkspace();
        final AnimationLayerSet layerViews = new AnimationLayerSet();
        final AnimatorSet animation = LauncherAnimUtils.createAnimatorSet();

        // Cancel the current animation
        cancelAnimation();

        playCommonTransitionAnimations(toWorkspaceState, animated, animated, animation, layerViews);
        mLauncher.getUserEventDispatcher().resetElapsedContainerMillis();

        if (animated) {
            animation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // Run any queued runnables
                    if (onCompleteRunnable != null) {
                        onCompleteRunnable.run();
                    }

                    // This can hold unnecessary references to views.
                    cleanupAnimation();
                }
            });
            animation.addListener(layerViews);
            fromWorkspace.post(new StartAnimRunnable(animation, null));
            mCurrentAnimation = animation;
        } else /* if (!animated) */ {
            // Run any queued runnables
            if (onCompleteRunnable != null) {
                onCompleteRunnable.run();
            }

            mCurrentAnimation = null;
        }
    }

    /**
     * Cancels the current animation.
     */
    private void cancelAnimation() {
        if (mCurrentAnimation != null) {
            mCurrentAnimation.setDuration(0);
            mCurrentAnimation.cancel();
            mCurrentAnimation = null;
        }
    }

    @Thunk void cleanupAnimation() {
        mCurrentAnimation = null;
    }

    private class StartAnimRunnable implements Runnable {

        private final AnimatorSet mAnim;
        private final View mViewToFocus;

        public StartAnimRunnable(AnimatorSet anim, View viewToFocus) {
            mAnim = anim;
            mViewToFocus = viewToFocus;
        }

        @Override
        public void run() {
            if (mCurrentAnimation != mAnim) {
                return;
            }
            if (mViewToFocus != null) {
                mViewToFocus.requestFocus();
            }
            mAnim.start();
        }
    }
}
