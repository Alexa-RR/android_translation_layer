/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.window;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.time.Duration;
import java.time.Instant;

/**
 * <p>The view which allows an activity to customize its splash screen exit animation.</p>
 *
 * <p>Activities will receive this view as a parameter of
 * {@link SplashScreen.OnExitAnimationListener#onSplashScreenExit} if they set
 * {@link SplashScreen#setOnExitAnimationListener}. When this callback is called, this view will be
 * on top of the activity.</p>
 *
 * <p>This view is composed of a view containing the splashscreen icon (see
 * windowSplashScreenAnimatedIcon) and a background.
 * Developers can use {@link #getIconView} to get this view and replace the drawable or
 * add animation to it. The background of this view is filled with a single color, which can be
 * edited during the animation by {@link View#setBackground} or {@link View#setBackgroundColor}.</p>
 *
 * ATL: AOSP's system server builds this view (SplashScreenView.Builder, from the starting window it
 * drew, partly as a SurfaceControlViewHost the app process takes over) and hands it to the activity.
 * This platform has no system server and draws no starting window, so no instance is ever handed over
 * (see {@link SplashScreen}); the class carries AOSP's app-facing API and its removal behaviour for the
 * views an instance holds, without the Builder and the surface transfer, which only the system uses.
 *
 * @see SplashScreen
 */
public final class SplashScreenView extends FrameLayout {
	private int mInitBackgroundColor;
	private View mIconView;
	private Bitmap mParceledIconBitmap;
	private View mBrandingImageView;
	private Bitmap mParceledBrandingBitmap;
	private Bitmap mParceledIconBackgroundBitmap;
	private Duration mIconAnimationDuration;
	private Instant mIconAnimationStart;
	private boolean mHasRemoved;

	/** @hide */
	public SplashScreenView(Context context) {
		super(context);
	}

	/** @hide */
	public SplashScreenView(Context context, AttributeSet attributeSet) {
		super(context, attributeSet);
	}

	/**
	 * Returns the duration of the icon animation if icon is animatable.
	 *
	 * @see android.R.attr#windowSplashScreenAnimatedIcon
	 * @see android.R.attr#windowSplashScreenAnimationDuration
	 */
	public Duration getIconAnimationDuration() {
		return mIconAnimationDuration;
	}

	/**
	 * If the replaced icon is animatable, return the animation start time based on system clock.
	 */
	public Instant getIconAnimationStart() {
		return mIconAnimationStart;
	}

	/**
	 * <p>Remove this view and release its resource. </p>
	 * <p><strong>Do not</strong> invoke this method from a drawing method
	 * ({@link #onDraw(android.graphics.Canvas)} for instance).</p>
	 */
	public void remove() {
		if (mHasRemoved) {
			return;
		}
		setVisibility(GONE);
		if (mParceledIconBitmap != null) {
			if (mIconView instanceof ImageView) {
				((ImageView)mIconView).setImageDrawable(null);
			} else if (mIconView != null) {
				mIconView.setBackground(null);
			}
			mParceledIconBitmap.recycle();
			mParceledIconBitmap = null;
		}
		if (mParceledBrandingBitmap != null) {
			mBrandingImageView.setBackground(null);
			mParceledBrandingBitmap.recycle();
			mParceledBrandingBitmap = null;
		}
		if (mParceledIconBackgroundBitmap != null) {
			if (mIconView != null) {
				mIconView.setBackground(null);
			}
			mParceledIconBackgroundBitmap.recycle();
			mParceledIconBackgroundBitmap = null;
		}
		// AOSP removes the view from the host window's DecorView it was attached to
		final ViewParent parent = getParent();
		if (parent instanceof ViewGroup) {
			((ViewGroup)parent).removeView(this);
		}
		mHasRemoved = true;
	}

	/**
	 * Get the view containing the Splash Screen icon and its background.
	 * @see android.R.attr#windowSplashScreenAnimatedIcon
	 */
	public View getIconView() {
		return mIconView;
	}

	/**
	 * Get the branding image view.
	 * @hide
	 */
	public View getBrandingView() {
		return mBrandingImageView;
	}

	/**
	 * Get the initial background color of this view.
	 * @hide
	 */
	public int getInitBackgroundColor() {
		return mInitBackgroundColor;
	}
}
