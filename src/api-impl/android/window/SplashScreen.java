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

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * The interface that apps use to talk to the splash screen (AOSP, API 31).
 * <p>
 * Each splash screen instance is bound to a particular {@link Activity}; apps get it from
 * <code>Activity.getSplashScreen()</code>.
 * <p>
 * ATL: in AOSP the system server draws a starting window while an activity starts and hands it over
 * to the app as a {@link SplashScreenView} (SplashScreenManagerGlobal.handOverSplashScreenView), which
 * calls the app's {@link OnExitAnimationListener}. This platform has no system server and draws no
 * starting window, so there is never a splash screen to hand over: listeners are kept exactly as in
 * AOSP and are called only if a view is handed over, which here does not happen; AOSP behaves the same
 * for an activity launched without a splash screen.
 */
public interface SplashScreen {
	/** The splash screen style is not defined. @hide */
	int SPLASH_SCREEN_STYLE_UNDEFINED = -1;
	/** Force splash screen to be empty. @hide */
	int SPLASH_SCREEN_STYLE_EMPTY = 0;
	/** Force splash screen to show icon. @hide */
	int SPLASH_SCREEN_STYLE_ICON = 1;

	/**
	 * Specifies whether an {@link Activity} wants to handle the splash screen animation on its own:
	 * the activity receives {@link OnExitAnimationListener#onSplashScreenExit} if a splash screen was
	 * shown. Takes effect only during or before {@link Activity#onResume}.
	 */
	void setOnExitAnimationListener(SplashScreen.OnExitAnimationListener listener);

	/** Clear exist listener. @see #setOnExitAnimationListener */
	void clearOnExitAnimationListener();

	/**
	 * Overrides the theme used for the {@link SplashScreen}s of this application; 0
	 * (Resources.ID_NULL) resets it to the manifest's theme.
	 */
	void setSplashScreenTheme(int themeId);

	/** Listens for the splash screen exit event. */
	interface OnExitAnimationListener {
		/**
		 * The {@link SplashScreenView} is drawing on top of the activity; the activity can make its exit
		 * animation from it. Never invoked once the listener is cleared.
		 */
		void onSplashScreenExit(SplashScreenView view);
	}

	/** @hide */
	class SplashScreenImpl implements SplashScreen {
		private static final String TAG = "SplashScreenImpl";

		private OnExitAnimationListener mExitAnimationListener;
		/** AOSP keys an activity by its window token; ATL has no tokens and keys it by the Activity. */
		private final Activity mActivity;
		private final SplashScreenManagerGlobal mGlobal;

		public SplashScreenImpl(Context context) {
			mActivity = context instanceof Activity ? (Activity)context : null;
			mGlobal = SplashScreenManagerGlobal.getInstance();
		}

		@Override
		public void setOnExitAnimationListener(SplashScreen.OnExitAnimationListener listener) {
			if (mActivity == null) {
				// This is not an activity.
				return;
			}
			synchronized (mGlobal.mGlobalLock) {
				if (listener != null) {
					mExitAnimationListener = listener;
					mGlobal.addImpl(this);
				}
			}
		}

		@Override
		public void clearOnExitAnimationListener() {
			if (mActivity == null) {
				// This is not an activity.
				return;
			}
			synchronized (mGlobal.mGlobalLock) {
				mExitAnimationListener = null;
				mGlobal.removeImpl(this);
			}
		}

		@Override
		public void setSplashScreenTheme(int themeId) {
			if (mActivity == null) {
				Log.w(TAG, "Couldn't persist the starting theme. This instance is not an Activity");
				return;
			}
			// AOSP resolves the theme's name (a stable key across app updates; an unknown id throws
			// Resources.NotFoundException here as it does there) and has the package manager persist it
			// for the starting window of the next launch. ATL draws no starting window, so the name is
			// kept for this process only: nothing on this platform reads it.
			String themeName = themeId != 0 ? mActivity.getResources().getResourceName(themeId) : null;
			mGlobal.setSplashScreenTheme(mActivity.getComponentName().getPackageName(), themeName);
		}
	}

	/**
	 * Manages the activities of this process that wait for a splash screen hand-over.
	 * @hide
	 */
	class SplashScreenManagerGlobal {
		private static final String TAG = SplashScreen.class.getSimpleName();
		private final Object mGlobalLock = new Object();
		private final ArrayList<SplashScreenImpl> mImpls = new ArrayList<>();
		private final HashMap<String, String> mThemes = new HashMap<>();
		private static SplashScreenManagerGlobal sInstance;

		private SplashScreenManagerGlobal() {}

		public static SplashScreenManagerGlobal getInstance() {
			synchronized (SplashScreenManagerGlobal.class) {
				if (sInstance == null)
					sInstance = new SplashScreenManagerGlobal();
				return sInstance;
			}
		}

		/** ATL: Activity.performDestroy; AOSP's ActivityThread calls tokenDestroyed with the token. */
		public static void activityDestroyed(Activity activity) {
			SplashScreenManagerGlobal global;
			synchronized (SplashScreenManagerGlobal.class) {
				global = sInstance;
			}
			if (global != null)
				global.tokenDestroyed(activity);
		}

		private void addImpl(SplashScreenImpl impl) {
			synchronized (mGlobalLock) {
				mImpls.add(impl);
			}
		}

		private void removeImpl(SplashScreenImpl impl) {
			synchronized (mGlobalLock) {
				mImpls.remove(impl);
			}
		}

		private SplashScreenImpl findImpl(Activity activity) {
			synchronized (mGlobalLock) {
				for (SplashScreenImpl impl : mImpls) {
					if (impl.mActivity == activity) {
						return impl;
					}
				}
			}
			return null;
		}

		public void tokenDestroyed(Activity activity) {
			synchronized (mGlobalLock) {
				final SplashScreenImpl impl = findImpl(activity);
				if (impl != null) {
					removeImpl(impl);
				}
			}
		}

		public void handOverSplashScreenView(Activity activity, SplashScreenView splashScreenView) {
			dispatchOnExitAnimation(activity, splashScreenView);
		}

		private void dispatchOnExitAnimation(Activity activity, SplashScreenView view) {
			synchronized (mGlobalLock) {
				final SplashScreenImpl impl = findImpl(activity);
				if (impl == null) {
					return;
				}
				if (impl.mExitAnimationListener == null) {
					Slog.e(TAG, "cannot dispatch onExitAnimation to listener " + activity);
					return;
				}
				impl.mExitAnimationListener.onSplashScreenExit(view);
			}
		}

		public boolean containsExitListener(Activity activity) {
			synchronized (mGlobalLock) {
				final SplashScreenImpl impl = findImpl(activity);
				return impl != null && impl.mExitAnimationListener != null;
			}
		}

		void setSplashScreenTheme(String packageName, String themeName) {
			synchronized (mGlobalLock) {
				if (themeName == null)
					mThemes.remove(packageName);
				else
					mThemes.put(packageName, themeName);
			}
		}

		/** The theme name setSplashScreenTheme gave for the package, or null for the manifest's. */
		public String getSplashScreenTheme(String packageName) {
			synchronized (mGlobalLock) {
				return mThemes.get(packageName);
			}
		}
	}
}
