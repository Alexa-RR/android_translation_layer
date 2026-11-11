package android.app;

import android.app.ActionBar;
import android.atl.ATLLoadedApp;
import android.atl.annotation.Export;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageParser;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Activity extends ContextThemeWrapper implements Window.Callback, KeyEvent.Callback, LayoutInflater.Factory2, android.content.ComponentCallbacks2 {
	private final static String TAG = "Activity";

	public static final int RESULT_CANCELED = 0;
	public static final int RESULT_OK = -1;

	Window window;
	int requested_orientation = -1 /*ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED*/; // dummy
	public Intent intent;
	private Activity resultActivity;
	private int resultRequestCode;
	private boolean paused = false;
	private CharSequence title = null;
	List<Fragment> fragments = new ArrayList<>();
	boolean destroyed = false;
	private boolean finishing = false;

	public static Activity internalCreateActivity(String className, long native_window, Intent intent) throws ReflectiveOperationException {
		Activity activity = ATLLoadedApp.getPrimaryApplication().createActivity(className, intent);
		activity.window.set_native_window(native_window);
		return activity;
	}

	/**
	 * Helper function to be called from native code to construct main activity
	 *
	 * @param className  class name of activity or null
	 * @return  instance of main activity class
	 * @throws Exception
	 */
	@Export
	private static Activity createMainActivity(String className, long native_window, String uriString) throws ReflectiveOperationException {
		Uri uri = uriString != null ? Uri.parse(uriString) : null;
		if (className == null) {
			for (PackageParser.Activity activity : ATLLoadedApp.getPrimaryApplication().pkg.activities) {
				if (!activity.info.enabled)
					continue;
				boolean done = false;
				for (PackageParser.IntentInfo intent : activity.intents) {
					Slog.i(TAG, intent.toString());
					if ((uri == null && intent.hasCategory("android.intent.category.LAUNCHER") && intent.hasAction("android.intent.action.MAIN")) ||        // NOLINT
					    (uri != null && intent.hasDataScheme(uri.getScheme())                  && intent.hasCategory("android.intent.category.DEFAULT"))) { // NOLINT
						className = activity.info.targetActivity != null ? activity.info.targetActivity : activity.className;
						done = true;
						break;
					}
				}
				if (done)
					break;
			}
		} else {
			className = className.replace('/', '.');
		}
		if (className == null) {
			if (uri != null)
				System.err.println("Failed to find Activity to launch URI: " + uri);
			else
				System.err.println("Failed to find main Activity");
			System.exit(1);
		}
		return internalCreateActivity(className, native_window, uri != null ? new Intent("android.intent.action.VIEW", uri) : new Intent());
	}

	public Activity() {
		super(null);
	}

	public View root_view;

	public final Application getApplication() {
		return (Application)getApplicationContext();
	}

	public WindowManager getWindowManager() {
		return new WindowManagerImpl();
	}

	/** AOSP: the package of the activity that started this one for a result, else null. */
	public String getCallingPackage() {
		return resultActivity != null && resultRequestCode >= 0 ? resultActivity.getPackageName() : null;
	}

	public ComponentName getComponentName() {
		return intent.getComponent();
	}

	public Intent getIntent() {
		return intent;
	}

	public int getRequestedOrientation() {
		return requested_orientation;
	}

	public void setRequestedOrientation(int orientation) {
		requested_orientation = orientation;
	}

	public boolean isFinishing() {
		return finishing;
	}

	/** AOSP: asks the window for the feature; ATL's Window grants none of the decor features. */
	public final boolean requestWindowFeature(int featureId) {
		return getWindow().requestFeature(featureId);
	}

	/** AOSP: the audio stream the hardware volume keys adjust while this activity is in front. */
	public final void setVolumeControlStream(int streamType) {
		mVolumeControlStream = streamType;
	}

	public final int getVolumeControlStream() {
		return mVolumeControlStream;
	}

	@Export
	protected void onCreate(Bundle savedInstanceState) {
		Slog.i(TAG, "- onCreate - yay!");

		for (Fragment fragment : fragments) {
			fragment.onCreate(savedInstanceState);
		}

		dispatchActivityCreated(savedInstanceState); // AOSP: ActivityLifecycleCallbacks.onActivityCreated
		return;
	}

	@Export
	protected void onPostCreate(Bundle savedInstanceState) {
		Slog.i(TAG, "- onPostCreate - yay!");
		return;
	}

	@Export
	protected void onStart() {
		Slog.i(TAG, "- onStart - yay!");
		/* tl_activity_transition: the pending transition animates this page change (AOSP app transition) */
		int[] tlTransition = tlTakePageTransition();
		if (tlTransition != null)
			nativeSetPageTransition(window.native_window, tlTransition[0], tlTransition[1]);
		window.set_widget_as_root(window.native_window, window.getDecorView().widget);
		if (tlTransition != null)
			nativeSetPageTransition(window.native_window, 0, 0);
		window.setTitle(title);
		mTitleReady = true;

		for (Fragment fragment : fragments) {
			fragment.onStart();
		}

		dispatchActivityStarted();
		return;
	}

	protected void onRestart() {
		Slog.i(TAG, "- onRestart - yay!");

		return;
	}

	@Export
	protected void onResume() {
		Slog.i(TAG, "- onResume - yay!");

		for (Fragment fragment : fragments) {
			fragment.onResume();
		}

		paused = false;
		dispatchActivityResumed();
		android.atl.ViewDump.installPoller(this); // ATL_DEBUG_DUMPVIEWS only
		return;
	}

	@Export
	protected void onPostResume() {
		Slog.i(TAG, "- onPostResume - yay!");
		return;
	}

	@Export
	protected void onPause() {
		dispatchActivityPaused(); // AOSP dispatches Paused first
		Slog.i(TAG, "- onPause - yay!");

		for (Fragment fragment : fragments) {
			fragment.onPause();
		}

		paused = true;
		return;
	}

	@Export
	protected void onStop() {
		Slog.i(TAG, "- onStop - yay!");

		for (Fragment fragment : fragments) {
			fragment.onStop();
		}

		dispatchActivityStopped();
		return;
	}

	@Export
	protected void onDestroy() {
		Slog.i(TAG, "- onDestroy - yay!");

		for (Fragment fragment : fragments) {
			fragment.onDestroy();
		}

		destroyed = true;
		dispatchActivityDestroyed();
		/* AOSP: the activity's window is removed with it (tl_window_attach: its decor page leaves the stack);
		 * while the closing transition still draws it, the page is removed once that has run */
		if (window != null && window.native_window != 0 && window.getDecorView() != null)
			tlRemoveDecorPage(window, window.native_window, window.getDecorView().widget);
		return;
	}

	@Export
	public void onWindowFocusChanged(boolean hasFocus) {
		Slog.i(TAG, "- onWindowFocusChanged - yay! (hasFocus: " + hasFocus + ")");

		return;
	}

	/**
	 * AOSP: the window's view hierarchy state (PhoneWindow.saveHierarchyState: the views' saved
	 * states and the focused view's id) and the autofill id; ActivityLifecycleCallbacks get
	 * onActivitySaveInstanceState from performSaveInstanceState.
	 */
	protected void onSaveInstanceState(Bundle outState) {
		outState.putBundle(WINDOW_HIERARCHY_TAG, tlSaveHierarchyState());
		outState.putInt(LAST_AUTOFILL_ID, mLastAutofillId);
	}

	public void onConfigurationChanged(Configuration newConfig) {
	}

	public void onLowMemory() {
	}

	/* --- */

	public void setContentView(int layoutResID) throws Exception {
		Slog.i(TAG, "- setContentView - yay!");

		root_view = getLayoutInflater().inflate(layoutResID, null, false);

		window.setContentView(root_view);
		onContentChanged();
	}

	public void setContentView(View view, ViewGroup.LayoutParams layoutParams) {
		setContentView(view);
	}

	public void setContentView(View view) {
		window.setContentView(view);
		onContentChanged();
	}

	public <T extends android.view.View> T findViewById(int id) {
		View view = window.findViewById(id);

		return (T)view;
	}

	public void invalidateOptionsMenu() {
		Slog.i(TAG, "invalidateOptionsMenu() called, should we do something?");
	}

	public Window getWindow() {
		return this.window;
	}

	public Display getDisplay() {
		return new Display();
	}

	public final void runOnUiThread(Runnable action) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			action.run();
		} else {
			new Handler(Looper.getMainLooper()).post(action);
		}
	}

	protected void onActivityResult(int requestCode, int resultCode, Intent data) {}

	// the order must match GtkFileChooserAction enum
	private static final List<String> FILE_CHOOSER_ACTIONS = Arrays.asList(
	    "android.intent.action.OPEN_DOCUMENT",     // (0) GTK_FILE_CHOOSER_ACTION_OPEN
	    "android.intent.action.CREATE_DOCUMENT",   // (1) GTK_FILE_CHOOSER_ACTION_SAVE
	    "android.intent.action.OPEN_DOCUMENT_TREE" // (2) GTK_FILE_CHOOSER_ACTION_SELECT_FOLDER
	);

	// callback from native code
	@Export
	protected void fileChooserResultCallback(int requestCode, int resultCode, int action, String uri) {
		onActivityResult(requestCode, resultCode, new Intent(FILE_CHOOSER_ACTIONS.get(action), uri != null ? Uri.parse(uri) : null));
	}

	public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
		Slog.i(TAG, "startActivityForResult(" + intent + ", " + requestCode + "," + options + ") called");
		tlApplyLaunchOptions(this, options);
		if (intent.getComponent() != null) {
			try {
				final Activity activity = internalCreateActivity(intent.getComponent().getClassName(), getWindow().native_window, intent);
				activity.resultRequestCode = requestCode;
				activity.resultActivity = this;
				activity.tlLaunchedByApp = true;
				activity.tlLaunchOptions = ActivityOptions.fromBundle(options);
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						nativeStartActivity(activity);
					}
				});
			} catch (ReflectiveOperationException e) {
				onActivityResult(requestCode, 0 /*RESULT_CANCELED*/, new Intent());
			}
		} else if (FILE_CHOOSER_ACTIONS.contains(intent.getAction())) {
			nativeFileChooser(FILE_CHOOSER_ACTIONS.indexOf(intent.getAction()), intent.getType(), intent.getStringExtra("android.intent.extra.TITLE"), requestCode);
		} else if (Intent.ACTION_GET_CONTENT.equals(intent.getAction())) {
			nativeFileChooser(0 /*GTK_FILE_CHOOSER_ACTION_OPEN*/, intent.getType(), intent.getStringExtra(Intent.EXTRA_TITLE), requestCode);
		} else if ("android.intent.action.INSTALL_PACKAGE".equals(intent.getAction())) {
			try {
				Process p = new ProcessBuilder("/usr/bin/env", "android-translation-layer", "--install", intent.getData().getPath()).start();
				int exitValue = p.waitFor();
				if (exitValue == 0) {
					onActivityResult(requestCode, -1 /*RESULT_OK*/, new Intent());
				} else {
					onActivityResult(requestCode, 0 /*RESULT_CANCELED*/, new Intent());
				}
			} catch (IOException | InterruptedException e) {
				e.printStackTrace();
				onActivityResult(requestCode, 0 /*RESULT_CANCELED*/, new Intent());
			}
		} else {
			Slog.i(TAG, "startActivityForResult: intent was not handled. Calling onActivityResult(RESULT_CANCELED).");
			onActivityResult(requestCode, 0 /*RESULT_CANCELED*/, new Intent());
		}
	}
	public void startActivityForResult(Intent intent, int requestCode) {
		startActivityForResult(intent, requestCode, null);
	}

	/** AOSP: the result is kept and delivered to the caller when this activity finishes. */
	public void setResult(int resultCode, Intent data) {
		synchronized (this) {
			mResultCode = resultCode;
			mResultData = data;
		}
	}

	public void setResult(int resultCode) {
		setResult(resultCode, null);
	}

	protected Dialog onCreateDialog(int id) {
		Slog.i(TAG, "Activity.onCreateDialog(" + id + ") called");
		return null;
	}

	protected void onPrepareDialog(int id, Dialog dialog) {
		Slog.i(TAG, "Activity.onPrepareDialog(" + id + ") called");
	}

	private Map<Integer, Dialog> dialogs = new HashMap<Integer, Dialog>();

	public final void showDialog(int id) {
		showDialog(id, null);
	}

	/** AOSP managed dialogs: created once by onCreateDialog(id, args), prepared before every show. */
	public final boolean showDialog(int id, Bundle args) {
		Dialog dialog = dialogs.get(id);
		if (dialog == null) {
			dialog = onCreateDialog(id, args);
			if (dialog == null)
				return false;
			dialog.setOwnerActivity(this);
			dialog.dispatchOnCreate(null);
			dialogs.put(id, dialog);
		}
		onPrepareDialog(id, dialog, args);
		dialog.show();
		return true;
	}

	protected Dialog onCreateDialog(int id, Bundle args) {
		return onCreateDialog(id);
	}

	protected void onPrepareDialog(int id, Dialog dialog, Bundle args) {
		onPrepareDialog(id, dialog);
	}

	public final void dismissDialog(int id) {
		Dialog dialog = dialogs.get(id);
		if (dialog == null)
			throw new IllegalArgumentException("no dialog with id " + id + " was ever shown via Activity#showDialog");
		dialog.dismiss();
	}

	public void removeDialog(int id) {
		Dialog dialog = dialogs.remove(id);
		if (dialog != null)
			dialog.dismiss();
	}

	public void finish() {
		if (finishing)
			return; /* AOSP: finishing an activity that is already finishing does nothing */
		finishing = true;
		tlOnFinish();
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				if (window != null && window.native_window != 0) {
					nativeFinish(getWindow().native_window);
					window.native_window = 0;
				}
			}
		});
	}

	/** AOSP: the object onRetainNonConfigurationInstance() returned in the instance this one replaces (recreate()). */
	public Object getLastNonConfigurationInstance() {
		return tlLastNonConfigurationInstance;
	}

	public FragmentManager getFragmentManager() {
		return new FragmentManager(this);
	}

	public LayoutInflater getLayoutInflater() {
		return (LayoutInflater)getSystemService("layout_inflater");
	}

	/** AOSP: true while this activity is destroyed to be recreated (recreate()). */
	public boolean isChangingConfigurations() { return mChangingConfigurations; }

	@Override
	public void onContentChanged() {
		Slog.i(TAG, "- onContentChanged - yay!");
	}

	public boolean onCreateOptionsMenu(Menu menu) {
		return true;
	}

	@Override
	public boolean onCreatePanelMenu(int featureId, Menu menu) {
		if (featureId == Window.FEATURE_OPTIONS_PANEL) {
			// HACK: catch non critical error occuring in Open Sudoku app
			try {
				return onCreateOptionsMenu(menu);
			} catch (Exception e) {
				e.printStackTrace();
				return false;
			}
		}
		return false;
	}

	@Override
	public View onCreatePanelView(int featureId) {
		return null;
	}

	public MenuInflater getMenuInflater() {
		return new MenuInflater(this);
	}

	public boolean onPrepareOptionsMenu(Menu menu) {
		return true;
	}

	@Override
	public boolean onPreparePanel(int featureId, View view, Menu menu) {
		if (featureId == Window.FEATURE_OPTIONS_PANEL && menu != null) {
			return onPrepareOptionsMenu(menu);
		}
		return true;
	}

	@Override
	public boolean onMenuItemSelected(int featureId, MenuItem item) {
		if (featureId == Window.FEATURE_OPTIONS_PANEL) {
			return onOptionsItemSelected(item);
		}
		return false;
	}

	public boolean onOptionsItemSelected(MenuItem item) {
		return false;
	}

	public void onOptionsMenuClosed(Menu menu) {}

	@Override
	public void onPanelClosed(int featureId, Menu menu) {
		if (featureId == Window.FEATURE_OPTIONS_PANEL) {
			onOptionsMenuClosed(menu);
		}
	}

	public void setTitle(CharSequence title) {
		this.title = title;
		onTitleChanged(title, mTitleColor);
	}

	public void setTitle(int titleId) {
		setTitle(getText(titleId));
	}

	public CharSequence getTitle() {
		return title;
	}

	@Export
	public void onBackPressed() {
		System.out.println("onBackPressed() called");
		finish();
	}

	public void setIntent(Intent newIntent) {
		this.intent = newIntent;
	}

	/** AOSP: an explicit intent for the manifest's android:parentActivityName, or null. */
	public Intent getParentActivityIntent() {
		final android.content.pm.ActivityInfo info = tlActivityInfo();
		final String parentName = info != null ? info.parentActivityName : null;
		if (android.text.TextUtils.isEmpty(parentName)) {
			return null;
		}
		// If the parent itself has no parent, generate a main activity intent.
		final ComponentName target = new ComponentName(this, parentName);
		final android.content.pm.ActivityInfo parentInfo = tlActivityInfo(parentName);
		final String parentActivity = parentInfo != null ? parentInfo.parentActivityName : null;
		final Intent parentIntent = parentActivity == null
		    ? Intent.makeMainActivity(target)
		    : new Intent().setComponent(target);
		return parentIntent;
	}

	@Override
	public boolean onMenuOpened(int featureId, Menu menu) {
		Slog.i(TAG, "onMenuOpened(" + featureId + ", " + menu + ") called");
		return false;
	}

	/**
	 * AOSP relaunch: the instance state is saved, onRetainNonConfigurationInstance() is kept, this
	 * instance is destroyed with isChangingConfigurations() true, and a new instance of the same
	 * activity is created from the saved state in its place.
	 */
	public void recreate() {
		if (finishing)
			return;
		finishing = true;
		try {
			final Activity activity = internalCreateActivity(this.getClass().getName(), getWindow().native_window, intent);
			activity.resultActivity = resultActivity;
			activity.resultRequestCode = resultRequestCode;
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override
				public void run() {
					mChangingConfigurations = true;
					Bundle state = new Bundle();
					performSaveInstanceState(state);
					activity.tlPendingSavedState = state;
					activity.tlLastNonConfigurationInstance = onRetainNonConfigurationInstance();
					nativeFinish(0);
					nativeStartActivity(activity);
				}
			});
		} catch (ReflectiveOperationException e) {
			finishing = false;
			Slog.w(TAG, "Activity.recreate: " + e);
			e.printStackTrace();
		}
	}

	public String getLocalClassName() {
		final String pkg = getPackageName();
		final String cls = this.getClass().getName();
		int packageLen = pkg.length();
		if (!cls.startsWith(pkg) || cls.length() <= packageLen || cls.charAt(packageLen) != '.') {
			return cls;
		}
		return cls.substring(packageLen + 1);
	}

	public SharedPreferences getPreferences(int mode) {
		return getSharedPreferences(getLocalClassName(), mode);
	}

	@Export
	protected void onNewIntent(Intent intent) {}

	public final Activity getParent() {
		return null;
	}

	/** AOSP: the decor view's window focus, while the window is visible. */
	public boolean hasWindowFocus() {
		View d = window != null ? window.peekDecorView() : null;
		if (d != null && d.getWindowVisibility() == View.VISIBLE) {
			return d.hasWindowFocus();
		}
		return false;
	}

	public boolean isDestroyed() {
		return destroyed;
	}

	public void finishAffinity() {
		finish();
	}

	/** AOSP: the given animations play for the activity switch that follows (startActivity or finish). */
	public void overridePendingTransition(int enterAnim, int exitAnim) {
		overridePendingTransition(enterAnim, exitAnim, 0);
	}

	public void overridePendingTransition(int enterAnim, int exitAnim, int backgroundColor) {
		tlSetPendingTransition(this, enterAnim, exitAnim);
	}

	public native boolean isTaskRoot();

	public void postponeEnterTransition() {}

	public void startPostponedEnterTransition() {}

	public boolean isChild() {
		return false;
	}

	/** AOSP: the task's label, icon and colour for recents; kept for ActivityManager.AppTask.getTaskInfo (no recents UI here). */
	public void setTaskDescription(ActivityManager.TaskDescription description) {
		synchronized (tlLiveActivities) {
			tlTaskDescription = description != null ? new ActivityManager.TaskDescription(description) : null;
		}
	}

	private native void nativeFinish(long native_window);
	public static native void nativeStartActivity(Activity activity);
	public static native boolean nativeResumeActivity(Class<? extends Activity> activityClass, Intent intent);
	public static native void nativeOpenURI(String uri);
	public native void nativeFileChooser(int action, String type, String title, int requestCode);
	/** AOSP: ActivityMetricsLogger logs the time from the launch to the first call ("Fully drawn"). */
	public void reportFullyDrawn() {
		if (tlFullyDrawnReported)
			return;
		tlFullyDrawnReported = true;
		long ms = android.os.SystemClock.uptimeMillis() - tlCreateUptime;
		android.util.Log.i("ActivityTaskManager", "Fully drawn " + getPackageName() + "/" + getLocalClassName() + ": +" + ms + "ms");
	}

	/** AOSP: shows or hides the activity's window (the decor) without changing its lifecycle. */
	public void setVisible(boolean visible) {
		if (mVisibleFromClient != visible) {
			mVisibleFromClient = visible;
			View decor = window != null ? window.peekDecorView() : null;
			if (decor != null)
				decor.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
		}
	}

	/**
	 * AOSP: Intent.EXTRA_REFERRER / EXTRA_REFERRER_NAME, else the package that launched the activity.
	 * Activities after the first are launched by this app itself; the first one is launched by the
	 * runtime, which (like a launcher that sets no referrer) gives none.
	 */
	public Uri getReferrer() {
		Intent i = getIntent();
		if (i != null) {
			try {
				Object referrer = i.getParcelableExtra(Intent.EXTRA_REFERRER);
				if (referrer instanceof Uri)
					return (Uri) referrer;
				String referrerName = i.getStringExtra(Intent.EXTRA_REFERRER_NAME);
				if (referrerName != null)
					return Uri.parse(referrerName);
			} catch (RuntimeException e) {
				android.util.Log.w(TAG, "Cannot read referrer from intent; intent extras={" + i.getExtras() + "}", e);
			}
		}
		if (tlLaunchedByApp)
			return new Uri.Builder().scheme("android-app").authority(getPackageName()).build();
		return null;
	}
	/**
	 * AOSP: the dialer, shortcut and search modes feed unhandled keys to the options menu, the dialer
	 * or SearchManager; this platform has no options menu panel, dialer or search service, so those
	 * modes keep the value and otherwise behave like DEFAULT_KEYS_DISABLE.
	 */
	public void setDefaultKeyMode(int flag) {
		mDefaultKeyMode = flag;
	}
	public void registerForContextMenu(View view) {}
	public native boolean isInMultiWindowMode();

	/* registerActivityLifecycleCallbacks: see the lifecycle block appended below (ATL's was a no-op) */

	public void setDisablePreviewScreenshots(boolean disable) {}
	public final View requireViewById(int id) {
		View view = findViewById(id);
		if (view == null)
			throw new IllegalArgumentException("ID does not reference a View inside this View");
		return view;
	}

	/** AOSP: <fragment> tags belong to the (framework) fragment controller, every other tag to onCreateView(name, context, attrs). */
	public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
		if (!"fragment".equals(name)) {
			return onCreateView(name, context, attrs);
		}
		return null;
	}

	public View onCreateView(String name, Context context, AttributeSet attrs) {
		return null;
	}

	/**
	 * AOSP: starts the search UI of SearchManager unless the device is a TV. This platform has no
	 * search dialog (no SearchManager UI service), so no search can start: false, as AOSP returns
	 * when it does not start one.
	 */
	public boolean onSearchRequested() {
		return false;
	}

	/** AOSP: the view of this activity's window that has the focus. */
	public View getCurrentFocus() {
		View decor = window != null ? window.peekDecorView() : null;
		return decor != null ? decor.findFocus() : null;
	}

	public void setProgressBarIndeterminateVisibility(boolean indeterminate) {}

	public int getChangingConfigurations() {
		return 0;
	}

	public int getTaskId() {
		/* we don't support multiple activity stacks, so this is probably fine? */
		return System.identityHashCode(this.getApplicationContext());
	}

	/**
	 * AOSP: moves the task behind the other tasks (the activities stop; the home screen shows).
	 * This runtime is the only task on its display, with nothing to show in its place, so the task
	 * cannot be moved: false, as AOSP returns when the move is refused (nonRoot false and this is
	 * not the root activity also returns false there).
	 */
	public boolean moveTaskToBack(boolean nonRoot) {
		return false;
	}

	public void setFinishOnTouchOutside(boolean finish) {
		mFinishOnTouchOutside = finish;
	}

	/**
	 * AOSP: closes the options panel of the window. ATL's Window has no options panel
	 * (FEATURE_OPTIONS_PANEL is not available), so no panel is ever open.
	 */
	public void closeOptionsMenu() {
		/* AOSP closes the action bar's overflow menu or the window's options panel: ATL has neither
		 * (getActionBar() is null, the Window has no panels), so there is nothing open to close */
	}

	/** AOSP: opens the action bar's overflow menu, else the window's options panel; ATL has neither (see closeOptionsMenu). */
	public void openOptionsMenu() {
		/* nothing can open: no action bar, no options panel */
	}

	/** AOSP: finishes the activities this one started with startActivityForResult(..., requestCode). */
	public void finishActivity(int requestCode) {
		for (Activity a : tlActivities()) {
			if (a.resultActivity == this && a.resultRequestCode == requestCode)
				a.finish();
		}
	}

	public void finishAfterTransition() {
		finish();
	}

	/**
	 * AOSP: the event goes to the window's views first (ATL's focused widget sees it natively before
	 * the window-level controller calls here), then to this activity's KeyEvent.Callback methods,
	 * where BACK ends in onBackPressed().
	 */
	public boolean dispatchKeyEvent(KeyEvent event) {
		onUserInteraction();
		return event.dispatch(this, tlKeyDispatchState, this);
	}

	/**
	 * AOSP: the permission controller asks the user, then onRequestPermissionsResult gets the
	 * grants. ATL's PackageManager decides every permission without a prompt, so the result is
	 * delivered right away (posted, as AOSP delivers it after the request returns) with the
	 * current grant state of each permission.
	 */
	public final void requestPermissions(final String[] permissions, final int requestCode) {
		if (requestCode < 0) {
			throw new IllegalArgumentException("requestCode should be >= 0");
		}
		if (permissions == null) {
			throw new IllegalArgumentException("permissions cannot be null");
		}
		final int[] results = new int[permissions.length];
		for (int i = 0; i < permissions.length; i++)
			results[i] = checkSelfPermission(permissions[i]);
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				if (!isDestroyed())
					onRequestPermissionsResult(requestCode, permissions, results);
			}
		});
	}

	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
	}

	/**
	 * AOSP: true only after the user denied the permission in the prompt without "don't ask
	 * again". There is no permission prompt on this platform, so the user never denied one: false.
	 */
	public boolean shouldShowRequestPermissionRationale(String permission) {
		return false;
	}

	public ActionBar getActionBar() {
		return null;
	}

	@Override
	public void atl_attach_base_context(Context baseContext) {
		super.atl_attach_base_context(baseContext);
		this.window = new Window(this, this);
	}


	/* teslalinux: Activity additions */
	/** AOSP: the dispatcher of this activity's window (TlBackDispatcher: when its callbacks take BACK). */
	public android.window.OnBackInvokedDispatcher getOnBackInvokedDispatcher() {
		if (mBackDispatcher == null)
			mBackDispatcher = new TlBackDispatcher(this);
		return mBackDispatcher;
	}
	public void addContentView(View view, ViewGroup.LayoutParams params) { setContentView(view, params); }
	/** AOSP: whether the activity was launched in a bubble; this platform has no bubbles (no SystemUI), so never. */
	public boolean isLaunchedFromBubble() { return false; }
	/** AOSP: a hook for the app; the base implementation does nothing. */
	public void onUserInteraction() {}

	/**
	 * AOSP: navigates up to the activity upIntent names. When an instance of it is in the task, the
	 * activities above it finish and it gets the intent (onNewIntent); true. Otherwise this activity
	 * just finishes; false.
	 */
	public boolean navigateUpTo(Intent upIntent) {
		ComponentName target = upIntent != null ? upIntent.getComponent() : null;
		Class<? extends Activity> cls = null;
		if (target != null) {
			try {
				cls = getClassLoader().loadClass(target.getClassName()).asSubclass(Activity.class);
			} catch (ClassNotFoundException | ClassCastException e) {
				cls = null;
			}
		}
		boolean found = false;
		if (cls != null) {
			for (Activity a : tlActivities()) {
				if (a != this && a.getClass() == cls && !a.isFinishing()) {
					found = true;
					break;
				}
			}
		}
		if (!found) {
			finish();
			return false;
		}
		Intent resultData;
		int resultCode;
		synchronized (this) {
			resultCode = mResultCode;
			resultData = mResultData;
		}
		if (resultActivity != null && resultData != null)
			resultActivity.tlQueueActivityResult(resultRequestCode, resultCode, resultData);
		final Class<? extends Activity> cls_ = cls;
		final Intent upIntent_ = new Intent(upIntent);
		finishing = true;
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				nativeResumeActivity(cls_, upIntent_);
			}
		});
		return true;
	}

	/**
	 * AOSP: whether up navigation must build a new task, i.e. the target's task affinity differs
	 * from this task's. This runtime runs the app in its one task with the app's affinity, which
	 * the app's own activities share unless they declare another one.
	 */
	public boolean shouldUpRecreateTask(Intent targetIntent) {
		ComponentName target = targetIntent != null ? targetIntent.getComponent() : null;
		if (target == null)
			return false;
		android.content.pm.ActivityInfo info = tlActivityInfo(target.getClassName());
		android.content.pm.ActivityInfo mine = tlActivityInfo();
		String targetAffinity = info != null ? info.taskAffinity : null;
		String myAffinity = mine != null ? mine.taskAffinity : null;
		if (targetAffinity == null || myAffinity == null)
			return false;
		return !targetAffinity.equals(myAffinity);
	}

	/** AOSP: kept for the (shared element) activity transitions; ATL has no transition coordinator to call them. */
	public void setEnterSharedElementCallback(SharedElementCallback callback) { mEnterSharedElementCallback = callback; }
	public void setExitSharedElementCallback(SharedElementCallback callback) { mExitSharedElementCallback = callback; }

	/** AOSP: the window's views first, then this activity's onKeyShortcut. */
	public boolean dispatchKeyShortcutEvent(android.view.KeyEvent event) {
		onUserInteraction();
		if (getWindow().superDispatchKeyShortcutEvent(event)) {
			return true;
		}
		return onKeyShortcut(event.getKeyCode(), event);
	}

	public void startIntentSenderForResult(android.content.IntentSender intent, int requestCode, Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags) throws android.content.IntentSender.SendIntentException {
		startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags, null);
	}

	/**
	 * AOSP: like startActivityForResult for the intent behind an IntentSender (a PendingIntent's).
	 * An activity PendingIntent is started for a result; any other kind is sent and gives none.
	 */
	public void startIntentSenderForResult(android.content.IntentSender intent, int requestCode, Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags, android.os.Bundle options) throws android.content.IntentSender.SendIntentException {
		PendingIntent pi = intent instanceof PendingIntent.TlIntentSender ? ((PendingIntent.TlIntentSender) intent).mPendingIntent : null;
		if (intent == null)
			throw new NullPointerException("intent");
		if (pi == null) {
			Slog.w(TAG, "startIntentSenderForResult: not a PendingIntent's sender: " + intent);
			throw intent.new SendIntentException();
		}
		Intent target = pi.tlFillIn(fillInIntent, flagsMask, flagsValues);
		if (target == null) /* canceled */
			throw intent.new SendIntentException();
		if (pi.type == 0) {
			startActivityForResult(target, requestCode, options);
		} else {
			try {
				pi.send(this, 0, fillInIntent);
			} catch (Exception e) {
				throw intent.new SendIntentException();
			}
		}
	}

	/** AOSP: passes the level to the fragments (ATL's framework fragments have no memory hook). */
	public void onTrimMemory(int level) {}

	/* ---- AOSP activity-level lifecycle callbacks (API 29) and the perform*() entry points ----
	 * ATL's native side calls performCreate/Start/Resume/Pause/Stop/Destroy (android_app_Activity.c,
	 * through the generated J__Activity__perform* upcall wrappers); each wraps the virtual on*() with the Pre/Post dispatch exactly as AOSP's
	 * Activity does. The plain Created/Started/... dispatch sits in the base on*() methods, as in AOSP. */
	private final java.util.ArrayList<Application.ActivityLifecycleCallbacks> mActivityLifecycleCallbacks = new java.util.ArrayList<>();

	public void registerActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks callback) {
		if (System.getenv("ATL_DEBUG_LIFECYCLE") != null)
			android.util.Log.i("Lifecycle", "Activity.registerActivityLifecycleCallbacks " + callback.getClass().getName() + " on " + getClass().getName());
		synchronized (mActivityLifecycleCallbacks) { mActivityLifecycleCallbacks.add(callback); }
	}
	public void unregisterActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks callback) {
		synchronized (mActivityLifecycleCallbacks) { mActivityLifecycleCallbacks.remove(callback); }
	}
	private Object[] collectActivityLifecycleCallbacks() {
		synchronized (mActivityLifecycleCallbacks) { return mActivityLifecycleCallbacks.isEmpty() ? null : mActivityLifecycleCallbacks.toArray(); }
	}
	private void dispatchActivityPreCreated(android.os.Bundle s) { getApplication().dispatchActivityPreCreated(this, s); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPreCreated(this, s); }
	private void dispatchActivityCreated(android.os.Bundle s) { getApplication().dispatchActivityCreated(this, s); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityCreated(this, s); }
	private void dispatchActivityPostCreated(android.os.Bundle s) { Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPostCreated(this, s); getApplication().dispatchActivityPostCreated(this, s); }
	private void dispatchActivityPreStarted() { getApplication().dispatchActivityPreStarted(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPreStarted(this); }
	private void dispatchActivityStarted() { getApplication().dispatchActivityStarted(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityStarted(this); }
	private void dispatchActivityPostStarted() { Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPostStarted(this); getApplication().dispatchActivityPostStarted(this); }
	private void dispatchActivityPreResumed() { getApplication().dispatchActivityPreResumed(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPreResumed(this); }
	private void dispatchActivityResumed() { getApplication().dispatchActivityResumed(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityResumed(this); }
	private void dispatchActivityPostResumed() { Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPostResumed(this); getApplication().dispatchActivityPostResumed(this); }
	private void dispatchActivityPrePaused() { getApplication().dispatchActivityPrePaused(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPrePaused(this); }
	private void dispatchActivityPaused() { getApplication().dispatchActivityPaused(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPaused(this); }
	private void dispatchActivityPostPaused() { Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPostPaused(this); getApplication().dispatchActivityPostPaused(this); }
	private void dispatchActivityPreStopped() { getApplication().dispatchActivityPreStopped(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPreStopped(this); }
	private void dispatchActivityStopped() { getApplication().dispatchActivityStopped(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityStopped(this); }
	private void dispatchActivityPostStopped() { Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPostStopped(this); getApplication().dispatchActivityPostStopped(this); }
	private void dispatchActivityPreSaveInstanceState(android.os.Bundle out) { getApplication().dispatchActivityPreSaveInstanceState(this, out); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPreSaveInstanceState(this, out); }
	private void dispatchActivitySaveInstanceState(android.os.Bundle out) { getApplication().dispatchActivitySaveInstanceState(this, out); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivitySaveInstanceState(this, out); }
	private void dispatchActivityPostSaveInstanceState(android.os.Bundle out) { Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPostSaveInstanceState(this, out); getApplication().dispatchActivityPostSaveInstanceState(this, out); }
	private void dispatchActivityPreDestroyed() { getApplication().dispatchActivityPreDestroyed(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPreDestroyed(this); }
	private void dispatchActivityDestroyed() { getApplication().dispatchActivityDestroyed(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityDestroyed(this); }
	private void dispatchActivityPostDestroyed() { Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPostDestroyed(this); getApplication().dispatchActivityPostDestroyed(this); }

	/** Native entry points: AOSP's perform*() ordering. */
	@Export
	public final void performCreate(android.os.Bundle savedInstanceState) {
		if (savedInstanceState == null)
			savedInstanceState = tlPendingSavedState; /* recreate(): the replaced instance's state */
		tlOnCreate();
		dispatchActivityPreCreated(savedInstanceState); onCreate(savedInstanceState); dispatchActivityPostCreated(savedInstanceState);
	}
	/** AOSP: a stopped activity gets onRestart before onStart. */
	private boolean mStopped;
	@Export
	public final void performStart() {
		if (mStopped) { mStopped = false; tlRequeryManagedCursors(); onRestart(); }
		dispatchActivityPreStarted(); onStart(); tlAfterStart(); dispatchActivityPostStarted();
	}
	@Export
	public final void performResume() {
		tlDispatchPendingResults(); /* AOSP: results of finished activities arrive right before onResume */
		dispatchActivityPreResumed(); onResume(); dispatchActivityPostResumed();
		if (tlResumeUptime == 0)
			tlResumeUptime = android.os.SystemClock.uptimeMillis();
	}
	@Export
	public final void performPause() { dispatchActivityPrePaused(); onPause(); dispatchActivityPostPaused(); }
	@Export
	public final void performStop() {
		dispatchActivityPreStopped(); onStop(); mStopped = true; tlAfterStop(); dispatchActivityPostStopped();
	}
	@Export
	public final void performDestroy() {
		dispatchActivityPreDestroyed(); onDestroy(); dispatchActivityPostDestroyed();
		tlAfterDestroy();
	}
	public final void performSaveInstanceState(android.os.Bundle outState) { dispatchActivityPreSaveInstanceState(outState); onSaveInstanceState(outState); dispatchActivitySaveInstanceState(outState); dispatchActivityPostSaveInstanceState(outState); }

	/* ---- lock screen / caller / locus (AOSP Activity, API 27-30) ----
	 * On Android these go to the activity task manager; the observable effect of the first three is
	 * the FLAG_SHOW_WHEN_LOCKED / FLAG_TURN_SCREEN_ON window state, which is what is kept here. There
	 * is no keyguard in this runtime, so an activity is always shown "when locked". */
	private boolean mInheritShowWhenLocked;

	public void setShowWhenLocked(boolean showWhenLocked) {
		if (showWhenLocked)
			getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
		else
			getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
	}

	public void setInheritShowWhenLocked(boolean inheritShowWhenLocked) {
		mInheritShowWhenLocked = inheritShowWhenLocked;
	}

	public void setTurnScreenOn(boolean turnScreenOn) {
		if (turnScreenOn)
			getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
		else
			getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
	}

	/** AOSP: the component that started this activity with startActivityForResult, else null.
	 * ATL does not attribute launches to a calling activity, so this is null, as for an activity
	 * launched without a result request. */
	public android.content.ComponentName getCallingActivity() {
		return resultActivity != null && resultRequestCode >= 0 ? resultActivity.getComponentName() : null;
	}

	/** AOSP forwards the locus to the activity task manager for content capture / shortcuts; neither
	 * service exists here, so only the value is kept. */
	private android.content.LocusId mLocusId;
	public void setLocusContext(android.content.LocusId locusId, android.os.Bundle bundle) {
		mLocusId = locusId;
	}
	/** AOSP: the configuration changed while this activity is resumed (Application first, then the activity). */
	public final void performConfigurationChanged(android.content.res.Configuration newConfig) {
		android.app.Application app = getApplication();
		if (app != null)
			app.onConfigurationChanged(newConfig);
		onConfigurationChanged(newConfig);
	}
	/* AOSP default key handling */
	public static final int DEFAULT_KEYS_DISABLE = 0;
	public static final int DEFAULT_KEYS_DIALER = 1;
	public static final int DEFAULT_KEYS_SHORTCUT = 2;
	public static final int DEFAULT_KEYS_SEARCH_LOCAL = 3;
	public static final int DEFAULT_KEYS_SEARCH_GLOBAL = 4;
	private int mDefaultKeyMode = DEFAULT_KEYS_DISABLE;
	private final KeyEvent.DispatcherState tlKeyDispatchState = new KeyEvent.DispatcherState();

	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			if (getApplicationInfo().targetSdkVersion >= android.os.Build.VERSION_CODES.ECLAIR) {
				event.startTracking();
			} else {
				onBackPressed();
			}
			return true;
		}
		return false;
	}

	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		return false;
	}

	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if (getApplicationInfo().targetSdkVersion >= android.os.Build.VERSION_CODES.ECLAIR) {
			if (keyCode == KeyEvent.KEYCODE_BACK && event.isTracking() && !event.isCanceled()) {
				/* AOSP: with OnBackInvokedCallback enabled, the window's top callback gets BACK */
				android.window.OnBackInvokedCallback tlBack = mBackDispatcher != null ? mBackDispatcher.getTopCallback() : null;
				if (tlBack != null)
					tlBack.onBackInvoked();
				else
					onBackPressed();
				return true;
			}
		}
		return false;
	}

	public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
		return false;
	}

	public boolean onKeyShortcut(int keyCode, KeyEvent event) {
		return false;
	}
	/** teslalinux: ATL's window focus entry (AOSP ViewRootImpl): the decor views first, then this activity. */
	@Export
	public void tlWindowFocusChanged(boolean hasFocus) {
		View decor = window != null ? window.getDecorView() : null;
		if (decor != null)
			decor.dispatchWindowFocusChanged(hasFocus);
		onWindowFocusChanged(hasFocus);
	}

	/* ==== teslalinux (app): AOSP Activity behaviour ATL lacked ====
	 * Task bookkeeping (ActivityManager.AppTask), results delivered on finish, recreate() with saved
	 * state, window token visibility for dialogs, activity transitions, PiP/multi-window reporting. */

	private static final String WINDOW_HIERARCHY_TAG = "android:viewHierarchyState";
	private static final String LAST_AUTOFILL_ID = "android:lastAutofillId";
	private static final String VIEWS_TAG = "android:views";
	private static final String FOCUSED_ID_TAG = "android:focusedViewId";

	/* ---- the one task: activities alive in this process, in launch order (root first) ---- */
	static final ArrayList<Activity> tlLiveActivities = new ArrayList<>();
	/** activities between onStart and onStop (ActivityManager importance of this process) */
	static int tlStartedCount;
	/** the task's description (Activity.setTaskDescription), for AppTask.getTaskInfo */
	static ActivityManager.TaskDescription tlTaskDescription;
	static long tlTaskLastActiveTime;

	static List<Activity> tlActivities() {
		synchronized (tlLiveActivities) {
			return new ArrayList<>(tlLiveActivities);
		}
	}

	/** between onStart and onStop: the windows of this activity's token (its dialogs) are shown */
	boolean tlTokenVisible;
	boolean tlLaunchedByApp;
	ActivityOptions tlLaunchOptions;
	private long tlCreateUptime;
	private long tlResumeUptime;
	private boolean tlFullyDrawnReported;

	private boolean mChangingConfigurations;
	Object tlLastNonConfigurationInstance;
	Bundle tlPendingSavedState;
	private int mLastAutofillId = 0x3fffffff; /* AOSP View.LAST_APP_AUTOFILL_ID */

	private boolean mTitleReady;
	private int mTitleColor;
	private boolean mVisibleFromClient = true;
	private boolean mFinishOnTouchOutside;
	private int mVolumeControlStream = Integer.MIN_VALUE; /* AudioManager.USE_DEFAULT_STREAM_TYPE */
	private TlBackDispatcher mBackDispatcher;
	private SharedElementCallback mEnterSharedElementCallback;
	private SharedElementCallback mExitSharedElementCallback;

	/* AOSP: the result this activity returns to the one that started it for a result */
	private int mResultCode = RESULT_CANCELED;
	private Intent mResultData;
	/* results of activities this one started, waiting for its next resume */
	private final ArrayList<Object[]> tlPendingResults = new ArrayList<>();

	private void tlOnCreate() {
		tlCreateUptime = android.os.SystemClock.uptimeMillis();
		synchronized (tlLiveActivities) {
			if (!tlLiveActivities.isEmpty())
				tlLaunchedByApp = true;
			if (!tlLiveActivities.contains(this))
				tlLiveActivities.add(this);
		}
	}

	private void tlAfterStart() {
		synchronized (tlLiveActivities) {
			if (!tlTokenVisible) {
				tlTokenVisible = true;
				tlStartedCount++;
			}
			tlTaskLastActiveTime = System.currentTimeMillis();
		}
		Dialog.tlTokenVisibility(this, true);
		/* AOSP performRestoreInstanceState: after onStart of a recreated instance */
		if (tlPendingSavedState != null) {
			Bundle state = tlPendingSavedState;
			tlPendingSavedState = null;
			onRestoreInstanceState(state);
		}
	}

	private void tlAfterStop() {
		synchronized (tlLiveActivities) {
			if (tlTokenVisible) {
				tlTokenVisible = false;
				tlStartedCount--;
			}
		}
		Dialog.tlTokenVisibility(this, false);
		/* AOSP: managed cursors are deactivated while the activity is stopped */
		synchronized (mManagedCursors) {
			for (android.database.Cursor c : mManagedCursors)
				c.deactivate();
		}
	}

	private void tlAfterDestroy() {
		synchronized (tlLiveActivities) {
			if (tlTokenVisible) {
				tlTokenVisible = false;
				tlStartedCount--;
			}
			tlLiveActivities.remove(this);
		}
		Dialog.tlTokenDestroyed(this);
		synchronized (mManagedCursors) {
			for (android.database.Cursor c : mManagedCursors)
				c.close();
			mManagedCursors.clear();
		}
		tlReportUsageTime();
	}

	/** finish(): the result goes to the caller (before its next onResume) and a close override becomes the pending transition */
	private void tlOnFinish() {
		if (resultActivity != null && resultRequestCode >= 0 && !mChangingConfigurations) {
			int code;
			Intent data;
			synchronized (this) {
				code = mResultCode;
				data = mResultData;
			}
			resultActivity.tlQueueActivityResult(resultRequestCode, code, data);
		}
		if (!tlHasPendingTransition && mOverrideCloseTransition != null)
			tlSetPendingTransition(this, mOverrideCloseTransition[0], mOverrideCloseTransition[1]);
	}

	void tlQueueActivityResult(int requestCode, int resultCode, Intent data) {
		synchronized (tlPendingResults) {
			tlPendingResults.add(new Object[] {requestCode, resultCode, data});
		}
	}

	private void tlDispatchPendingResults() {
		Object[][] results;
		synchronized (tlPendingResults) {
			if (tlPendingResults.isEmpty())
				return;
			results = tlPendingResults.toArray(new Object[0][]);
			tlPendingResults.clear();
		}
		for (Object[] r : results)
			onActivityResult((Integer) r[0], (Integer) r[1], (Intent) r[2]);
	}

	/** AOSP AppTimeTracker: the PendingIntent of ActivityOptions.requestUsageTimeReport gets the time spent here. */
	private void tlReportUsageTime() {
		PendingIntent receiver = tlLaunchOptions != null ? tlLaunchOptions.getUsageTimeReport() : null;
		if (receiver == null || tlResumeUptime == 0)
			return;
		long ms = android.os.SystemClock.uptimeMillis() - tlResumeUptime;
		Intent fillIn = new Intent();
		fillIn.putExtra(ActivityOptions.EXTRA_USAGE_TIME_REPORT, ms);
		Bundle pkgs = new Bundle();
		pkgs.putLong(getPackageName(), ms);
		fillIn.putExtra(ActivityOptions.EXTRA_USAGE_TIME_REPORT_PACKAGES, pkgs);
		try {
			receiver.send(this, 0, fillIn);
		} catch (Exception e) {
			android.util.Log.w(TAG, "usage time report: " + e);
		}
	}

	/* ---- AOSP instance state ---- */

	/** AOSP: what this instance passes to the one recreate() makes (getLastNonConfigurationInstance there). */
	public Object onRetainNonConfigurationInstance() {
		return null;
	}

	/** AOSP: restores the window's view hierarchy state saved by onSaveInstanceState. */
	protected void onRestoreInstanceState(Bundle savedInstanceState) {
		Bundle windowState = savedInstanceState.getBundle(WINDOW_HIERARCHY_TAG);
		if (windowState != null)
			tlRestoreHierarchyState(windowState);
	}

	public void onRestoreInstanceState(Bundle savedInstanceState, android.os.PersistableBundle persistentState) {
		if (savedInstanceState != null) {
			onRestoreInstanceState(savedInstanceState);
		}
	}

	public void onSaveInstanceState(Bundle outState, android.os.PersistableBundle outPersistentState) {
		onSaveInstanceState(outState);
	}

	public void onCreate(Bundle savedInstanceState, android.os.PersistableBundle persistentState) {
		onCreate(savedInstanceState);
	}

	public void onPostCreate(Bundle savedInstanceState, android.os.PersistableBundle persistentState) {
		onPostCreate(savedInstanceState);
	}

	/* AOSP PhoneWindow.saveHierarchyState / restoreHierarchyState */
	private Bundle tlSaveHierarchyState() {
		Bundle outState = new Bundle();
		View decor = window != null ? window.peekDecorView() : null;
		if (decor == null)
			return outState;
		android.util.SparseArray<android.os.Parcelable> states = new android.util.SparseArray<>();
		decor.saveHierarchyState(states);
		outState.putSparseParcelableArray(VIEWS_TAG, states);
		final View focusedView = decor.findFocus();
		if (focusedView != null && focusedView.getId() != View.NO_ID)
			outState.putInt(FOCUSED_ID_TAG, focusedView.getId());
		return outState;
	}

	/** The focused view comes back; the views' own states need View.restoreHierarchyState, which ATL's View lacks. */
	private void tlRestoreHierarchyState(Bundle savedInstanceState) {
		View decor = window != null ? window.peekDecorView() : null;
		if (decor == null)
			return;
		int focusedViewId = savedInstanceState.getInt(FOCUSED_ID_TAG, View.NO_ID);
		if (focusedViewId != View.NO_ID) {
			View needsFocus = decor.findViewById(focusedViewId);
			if (needsFocus != null)
				needsFocus.requestFocus();
			else
				android.util.Log.w(TAG, "Previously focused view reported id " + focusedViewId + " during save, but can't be found during restore.");
		}
	}

	/* ---- title ---- */

	/** AOSP: the title reaches the window once the activity is started (mTitleReady). */
	protected void onTitleChanged(CharSequence title, int color) {
		if (mTitleReady && window != null) {
			window.setTitle(title);
		}
	}

	protected void onChildTitleChanged(Activity childActivity, CharSequence title) {
	}

	@Deprecated
	public void setTitleColor(int textColor) {
		mTitleColor = textColor;
		onTitleChanged(title, textColor);
	}

	@Deprecated
	public final int getTitleColor() {
		return mTitleColor;
	}

	/* ---- input that reaches the activity (Window.Callback) ---- */

	/** AOSP: the window's views first, then onTouchEvent. */
	public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
		if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
			onUserInteraction();
		}
		if (getWindow().superDispatchTouchEvent(ev)) {
			return true;
		}
		return onTouchEvent(ev);
	}

	/** AOSP: a touch outside the window of an activity with setFinishOnTouchOutside(true) finishes it. */
	public boolean onTouchEvent(android.view.MotionEvent event) {
		if (tlShouldCloseOnTouch(event)) {
			finish();
			return true;
		}
		return false;
	}

	private boolean tlShouldCloseOnTouch(android.view.MotionEvent event) {
		View decor = window != null ? window.peekDecorView() : null;
		if (!mFinishOnTouchOutside || decor == null)
			return false;
		final int action = event.getAction();
		if (action == android.view.MotionEvent.ACTION_OUTSIDE)
			return true;
		if (action != android.view.MotionEvent.ACTION_UP)
			return false;
		final int x = (int) event.getX();
		final int y = (int) event.getY();
		final int slop = android.view.ViewConfiguration.get(this).getScaledWindowTouchSlop();
		return (x < -slop) || (y < -slop) || (x > (decor.getWidth() + slop)) || (y > (decor.getHeight() + slop));
	}

	public boolean dispatchTrackballEvent(android.view.MotionEvent ev) {
		onUserInteraction();
		if (getWindow().superDispatchTrackballEvent(ev)) {
			return true;
		}
		return onTrackballEvent(ev);
	}

	public boolean onTrackballEvent(android.view.MotionEvent event) {
		return false;
	}

	public boolean dispatchGenericMotionEvent(android.view.MotionEvent ev) {
		onUserInteraction();
		if (getWindow().superDispatchGenericMotionEvent(ev)) {
			return true;
		}
		return onGenericMotionEvent(ev);
	}

	public boolean onGenericMotionEvent(android.view.MotionEvent event) {
		return false;
	}

	/** AOSP: called when the activity goes to the background because of the user (HOME); never on this kiosk, which has no other task to go to. */
	protected void onUserLeaveHint() {
	}

	/** AOSP View.OnCreateContextMenuListener: the base implementation adds nothing. */
	public void onCreateContextMenu(android.view.ContextMenu menu, View v, android.view.ContextMenu.ContextMenuInfo menuInfo) {
	}

	/* ---- managed cursors (AOSP, deprecated) ---- */
	private final ArrayList<android.database.Cursor> mManagedCursors = new ArrayList<>();

	@Deprecated
	public final android.database.Cursor managedQuery(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		android.database.Cursor c = getContentResolver().query(uri, projection, selection, selectionArgs, sortOrder);
		if (c != null) {
			startManagingCursor(c);
		}
		return c;
	}

	@Deprecated
	public void startManagingCursor(android.database.Cursor c) {
		synchronized (mManagedCursors) {
			mManagedCursors.add(c);
		}
	}

	@Deprecated
	public void stopManagingCursor(android.database.Cursor c) {
		synchronized (mManagedCursors) {
			mManagedCursors.remove(c);
		}
	}

	/** AOSP performRestart: managed cursors are requeried when a stopped activity starts again. */
	private void tlRequeryManagedCursors() {
		synchronized (mManagedCursors) {
			for (android.database.Cursor c : mManagedCursors) {
				if (!c.isClosed() && !c.requery() && getApplicationInfo().targetSdkVersion >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
					throw new IllegalStateException("trying to requery an already closed cursor  " + c);
				}
			}
		}
	}

	/* ---- task ---- */

	/** AOSP: finishes every activity of the task (this runtime has the one task). */
	public void finishAndRemoveTask() {
		List<Activity> all = tlActivities();
		for (int i = all.size() - 1; i >= 0; i--)
			all.get(i).finish();
		if (!all.contains(this))
			finish();
	}

	/* ---- multi-window and picture-in-picture ----
	 * This platform shows one app fullscreen on one display: no split screen, no freeform windows and
	 * no PiP (PackageManager has no android.software.picture_in_picture), so the mode callbacks never
	 * fire and the requests are refused the way AOSP refuses them on such a device. */
	private PictureInPictureParams mPictureInPictureParams;

	public void onMultiWindowModeChanged(boolean isInMultiWindowMode, android.content.res.Configuration newConfig) {
		onMultiWindowModeChanged(isInMultiWindowMode);
	}

	@Deprecated
	public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
	}

	public boolean isInPictureInPictureMode() {
		return false;
	}

	public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, android.content.res.Configuration newConfig) {
		onPictureInPictureModeChanged(isInPictureInPictureMode);
	}

	@Deprecated
	public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
	}

	public void onPictureInPictureUiStateChanged(PictureInPictureUiState pipState) {
	}

	/** AOSP: called when the system asks the activity to enter PiP; the default does not enter. */
	public boolean onPictureInPictureRequested() {
		return false;
	}

	private boolean tlDeviceSupportsPictureInPictureMode() {
		return getPackageManager().hasSystemFeature("android.software.picture_in_picture");
	}

	@Deprecated
	public void enterPictureInPictureMode() {
		enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
	}

	public boolean enterPictureInPictureMode(PictureInPictureParams params) {
		if (!tlDeviceSupportsPictureInPictureMode()) {
			return false;
		}
		if (params == null) {
			throw new IllegalArgumentException("Expected non-null picture-in-picture params");
		}
		throw new IllegalStateException("Activity must be resumed to enter picture-in-picture");
	}

	public void setPictureInPictureParams(PictureInPictureParams params) {
		if (!tlDeviceSupportsPictureInPictureMode()) {
			return;
		}
		if (params == null) {
			throw new IllegalArgumentException("Expected non-null picture-in-picture params");
		}
		mPictureInPictureParams = params;
	}

	public int getMaxNumPictureInPictureActions() {
		return 0;
	}

	/* ---- launching ---- */

	/** AOSP: like startActivity(intent), with the launch options (custom animation, usage time report). */
	@Override
	public void startActivity(Intent intent, Bundle options) {
		tlApplyLaunchOptions(this, options);
		Bundle previous = ContextImpl.tlNextLaunchOptions;
		ContextImpl.tlNextLaunchOptions = options;
		try {
			startActivity(intent);
		} finally {
			ContextImpl.tlNextLaunchOptions = previous;
		}
	}

	/** The launch options' custom animation (ActivityOptions.makeCustomAnimation) is the next activity transition. */
	static void tlApplyLaunchOptions(Context context, Bundle options) {
		ActivityOptions opts = ActivityOptions.fromBundle(options);
		if (opts != null && opts.getAnimationType() == ActivityOptions.ANIM_CUSTOM)
			tlSetPendingTransition(context, opts.getCustomEnterResId(), opts.getCustomExitResId());
	}

	/* ---- tl_activity_transition: AOSP app transitions on ATL's page stack (native-app-transition.sh) ----
	 * AOSP plays the enter animation on the incoming activity and the exit animation on the outgoing
	 * one. Here every activity is a page of the main window's GtkStack, and GTK animates the page
	 * change natively; the anim resources are mapped to the GtkStackTransitionType that moves the
	 * pages the same way (slide / cover / uncover in the animations' direction, a crossfade for
	 * fades and for scale or rotate animations, which GtkStack cannot do) for the animations' length.
	 * Without an override ATL's activity switch has no animation (the theme's default activity
	 * animations are not played). */
	public static final int OVERRIDE_TRANSITION_OPEN = 0;
	public static final int OVERRIDE_TRANSITION_CLOSE = 1;

	private static boolean tlHasPendingTransition;
	private static int tlPendingEnterAnim, tlPendingExitAnim;
	private static Context tlPendingTransitionContext;
	private static long tlTransitionEndsAt;
	private int[] mOverrideOpenTransition;
	private int[] mOverrideCloseTransition;
	private boolean tlOpenTransitionUsed;

	private static native void nativeSetPageTransition(long nativeWindow, int gtkTransitionType, int durationMs);

	static void tlSetPendingTransition(Context context, int enterAnim, int exitAnim) {
		tlHasPendingTransition = true;
		tlPendingEnterAnim = enterAnim;
		tlPendingExitAnim = exitAnim;
		tlPendingTransitionContext = context;
	}

	/** AOSP (API 34): the animations of this activity's own open or close transitions. */
	public void overrideActivityTransition(int overrideType, int enterAnim, int exitAnim) {
		overrideActivityTransition(overrideType, enterAnim, exitAnim, 0);
	}

	public void overrideActivityTransition(int overrideType, int enterAnim, int exitAnim, int backgroundColor) {
		if (overrideType != OVERRIDE_TRANSITION_OPEN && overrideType != OVERRIDE_TRANSITION_CLOSE) {
			throw new IllegalArgumentException("Override type must be either open or close");
		}
		if (overrideType == OVERRIDE_TRANSITION_OPEN)
			mOverrideOpenTransition = new int[] {enterAnim, exitAnim};
		else
			mOverrideCloseTransition = new int[] {enterAnim, exitAnim};
	}

	public void clearOverrideActivityTransition(int overrideType) {
		if (overrideType != OVERRIDE_TRANSITION_OPEN && overrideType != OVERRIDE_TRANSITION_CLOSE) {
			throw new IllegalArgumentException("Override type must be either open or close");
		}
		if (overrideType == OVERRIDE_TRANSITION_OPEN)
			mOverrideOpenTransition = null;
		else
			mOverrideCloseTransition = null;
	}

	/** {GtkStackTransitionType, duration ms} for the page change this onStart makes, or null for none. */
	private int[] tlTakePageTransition() {
		int enter, exit;
		Context ctx;
		if (tlHasPendingTransition) {
			enter = tlPendingEnterAnim;
			exit = tlPendingExitAnim;
			ctx = tlPendingTransitionContext != null ? tlPendingTransitionContext : this;
			tlHasPendingTransition = false;
			tlPendingTransitionContext = null;
		} else if (!tlOpenTransitionUsed && mOverrideOpenTransition != null) {
			enter = mOverrideOpenTransition[0];
			exit = mOverrideOpenTransition[1];
			ctx = this;
		} else {
			tlOpenTransitionUsed = true;
			return null;
		}
		tlOpenTransitionUsed = true;
		int[] t = tlPageTransitionFor(ctx, enter, exit);
		tlTransitionEndsAt = t[0] != 0 ? android.os.SystemClock.uptimeMillis() + t[1] : 0;
		return t;
	}

	/* GtkStackTransitionType */
	private static final int GTK_NONE = 0, GTK_CROSSFADE = 1, GTK_SLIDE_RIGHT = 2, GTK_SLIDE_LEFT = 3,
	    GTK_SLIDE_UP = 4, GTK_SLIDE_DOWN = 5, GTK_OVER_UP = 8, GTK_OVER_DOWN = 9, GTK_OVER_LEFT = 10,
	    GTK_OVER_RIGHT = 11, GTK_UNDER_UP = 12, GTK_UNDER_DOWN = 13, GTK_UNDER_LEFT = 14, GTK_UNDER_RIGHT = 15;

	static int[] tlPageTransitionFor(Context ctx, int enterRes, int exitRes) {
		if (enterRes == 0 && exitRes == 0)
			return new int[] {GTK_NONE, 0};
		float[] in = tlAnimMotion(ctx, enterRes, true);
		float[] out = tlAnimMotion(ctx, exitRes, false);
		int duration = (int) Math.max(in[2], out[2]);
		if (duration <= 0)
			return new int[] {GTK_NONE, 0};
		int type;
		/* in[0]/in[1] > 0: the entering page comes from the right / from below;
		 * out[0]/out[1] > 0: the leaving page goes to the right / downwards */
		if (in[0] > 0)
			type = out[0] < 0 ? GTK_SLIDE_LEFT : GTK_OVER_LEFT;
		else if (in[0] < 0)
			type = out[0] > 0 ? GTK_SLIDE_RIGHT : GTK_OVER_RIGHT;
		else if (in[1] > 0)
			type = out[1] < 0 ? GTK_SLIDE_UP : GTK_OVER_UP;
		else if (in[1] < 0)
			type = out[1] > 0 ? GTK_SLIDE_DOWN : GTK_OVER_DOWN;
		else if (out[0] > 0)
			type = GTK_UNDER_RIGHT;
		else if (out[0] < 0)
			type = GTK_UNDER_LEFT;
		else if (out[1] > 0)
			type = GTK_UNDER_DOWN;
		else if (out[1] < 0)
			type = GTK_UNDER_UP;
		else
			type = GTK_CROSSFADE;
		return new int[] {type, duration};
	}

	/** {dx sign, dy sign, duration ms} of an anim resource: the translate motion (from -> to) and its total length. */
	private static float[] tlAnimMotion(Context ctx, int res, boolean entering) {
		float[] m = new float[3];
		if (res == 0)
			return m;
		try {
			android.view.animation.Animation anim = android.view.animation.AnimationUtils.loadAnimation(ctx, res);
			if (anim != null)
				m[2] = anim.computeDurationHint();
		} catch (RuntimeException e) {
			android.util.Log.w(TAG, "activity transition: cannot load animation 0x" + Integer.toHexString(res) + ": " + e);
			return m;
		}
		final String ns = "http://schemas.android.com/apk/res/android";
		android.content.res.XmlResourceParser p = null;
		try {
			p = ctx.getResources().getAnimation(res);
			int ev;
			while ((ev = p.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
				if (ev != org.xmlpull.v1.XmlPullParser.START_TAG || !"translate".equals(p.getName()))
					continue;
				float fx = tlDelta(p.getAttributeValue(ns, "fromXDelta")), tx = tlDelta(p.getAttributeValue(ns, "toXDelta"));
				float fy = tlDelta(p.getAttributeValue(ns, "fromYDelta")), ty = tlDelta(p.getAttributeValue(ns, "toYDelta"));
				/* entering: where it comes from (from - to); leaving: where it goes (to - from) */
				m[0] += entering ? fx - tx : tx - fx;
				m[1] += entering ? fy - ty : ty - fy;
			}
		} catch (Exception e) {
			android.util.Log.w(TAG, "activity transition: cannot read animation 0x" + Integer.toHexString(res) + ": " + e);
		} finally {
			if (p != null)
				p.close();
		}
		m[0] = Math.signum(m[0]);
		m[1] = Math.signum(m[1]);
		return m;
	}

	/** A translate delta as AAPT writes it ("100.0%", "-50.0%p", "48.0dip", "12.0"); 0 for references or nothing. */
	private static float tlDelta(String v) {
		if (v == null || v.isEmpty() || v.charAt(0) == '@' || v.charAt(0) == '?')
			return 0;
		int end = 0;
		while (end < v.length() && "+-.0123456789eE".indexOf(v.charAt(end)) >= 0)
			end++;
		try {
			return end > 0 ? Float.parseFloat(v.substring(0, end)) : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** onDestroy: the page leaves the stack; while a transition still draws it, once that has run. */
	private void tlRemoveDecorPage(final Window w, final long nativeWindow, final long widget) {
		long delay = tlTransitionEndsAt - android.os.SystemClock.uptimeMillis();
		boolean othersAlive;
		synchronized (tlLiveActivities) {
			othersAlive = tlLiveActivities.size() > 1;
		}
		if (delay > 0 && othersAlive) {
			new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
				@Override
				public void run() {
					synchronized (tlLiveActivities) {
						if (tlLiveActivities.isEmpty())
							return; /* the window is gone with the last activity */
					}
					w.tlRemoveRoot(nativeWindow, widget);
				}
			}, delay);
		} else {
			w.tlRemoveRoot(nativeWindow, widget);
		}
	}

	/* ---- manifest ---- */

	/** This activity's manifest entry. */
	android.content.pm.ActivityInfo tlActivityInfo() {
		return tlActivityInfo(getClass().getName());
	}

	android.content.pm.ActivityInfo tlActivityInfo(String className) {
		android.atl.ATLLoadedApp app = android.atl.ATLLoadedApp.getPrimaryApplication();
		if (app == null || app.pkg == null || className == null)
			return null;
		if (className.startsWith("."))
			className = app.pkg.packageName + className;
		for (PackageParser.Activity a : app.pkg.activities) {
			if (className.equals(a.className) || className.equals(a.info.name))
				return a.info;
		}
		return null;
	}

	/* ---- dump ---- */

	/** AOSP: the activity's state and its view hierarchy (dumpsys activity). */
	public void dump(String prefix, java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
		writer.print(prefix);
		writer.print("Local Activity ");
		writer.print(Integer.toHexString(System.identityHashCode(this)));
		writer.println(" State:");
		String innerPrefix = prefix + "  ";
		writer.print(innerPrefix);
		writer.print("mResumed=");
		writer.print(!paused);
		writer.print(" mStopped=");
		writer.print(mStopped);
		writer.print(" mFinished=");
		writer.println(finishing);
		writer.print(innerPrefix);
		writer.print("mIsInMultiWindowMode=");
		writer.print(isInMultiWindowMode());
		writer.print(" mIsInPictureInPictureMode=");
		writer.println(isInPictureInPictureMode());
		writer.print(innerPrefix);
		writer.print("mChangingConfigurations=");
		writer.println(mChangingConfigurations);
		writer.print(innerPrefix);
		writer.print("mCurrentConfig=");
		writer.println(getResources().getConfiguration());
		View decor = window != null ? window.peekDecorView() : null;
		if (decor != null) {
			writer.print(prefix);
			writer.println("View Hierarchy:");
			tlDumpView(innerPrefix, writer, decor);
		}
	}

	private static void tlDumpView(String prefix, java.io.PrintWriter writer, View view) {
		writer.print(prefix);
		writer.println(view);
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++)
				tlDumpView(prefix + "  ", writer, group.getChildAt(i));
		}
	}

	/**
	 * AOSP: hierarchical Up navigation from the manifest's parent activity. A target in another task
	 * affinity rebuilds the parent stack (TaskStackBuilder) and finishes this affinity; otherwise
	 * navigateUpTo returns to the parent.
	 */
	public boolean onNavigateUp() {
		Intent upIntent = getParentActivityIntent();
		if (upIntent != null) {
			android.content.pm.ActivityInfo info = tlActivityInfo();
			if (info == null || info.taskAffinity == null) {
				// Activities with a null affinity are special; they really shouldn't
				// specify a parent activity intent in the first place. Just finish
				// the current activity and call it a day.
				finish();
			} else if (shouldUpRecreateTask(upIntent)) {
				TaskStackBuilder b = TaskStackBuilder.create(this);
				onCreateNavigateUpTaskStack(b);
				onPrepareNavigateUpTaskStack(b);
				b.startActivities();

				// We can't finishAffinity if we have a result.
				// Fall back and simply finish the current activity instead.
				boolean haveResult;
				synchronized (this) {
					haveResult = mResultCode != RESULT_CANCELED || mResultData != null;
				}
				if (haveResult) {
					Slog.i(TAG, "onNavigateUp only finishing topmost activity to return a result");
					finish();
				} else {
					finishAffinity();
				}
			} else {
				navigateUpTo(upIntent);
			}
			return true;
		}
		return false;
	}

	/** AOSP: the default up task stack is the parent stack of this activity. */
	public void onCreateNavigateUpTaskStack(TaskStackBuilder builder) {
		builder.addParentStack(this);
	}

	/** AOSP: a hook to adjust the up task stack before it is started; the base class adds nothing. */
	public void onPrepareNavigateUpTaskStack(TaskStackBuilder builder) {
	}
}
