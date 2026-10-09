package android.app;

import android.app.ActionBar;
import android.atl.ATLLoadedApp;
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

public class Activity extends ContextThemeWrapper implements Window.Callback, LayoutInflater.Factory2, android.content.ComponentCallbacks2 {
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

	public String getCallingPackage() {
		return null; // [from api reference] Note: if the calling activity is not expecting a result (that is it did not use the startActivityForResult(Intent, int) form that includes a request code), then the calling package will be null.
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

	public final boolean requestWindowFeature(int featureId) {
		return false; // whatever feature it is, it's probably not supported
	}

	public final void setVolumeControlStream(int streamType) {}

	protected void onCreate(Bundle savedInstanceState) {
		Slog.i(TAG, "- onCreate - yay!");

		for (Fragment fragment : fragments) {
			fragment.onCreate(savedInstanceState);
		}

		dispatchActivityCreated(savedInstanceState); // AOSP: ActivityLifecycleCallbacks.onActivityCreated
		return;
	}

	protected void onPostCreate(Bundle savedInstanceState) {
		Slog.i(TAG, "- onPostCreate - yay!");
		return;
	}

	protected void onStart() {
		Slog.i(TAG, "- onStart - yay!");
		window.set_widget_as_root(window.native_window, window.getDecorView().widget);
		window.setTitle(title);

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

	protected void onPostResume() {
		Slog.i(TAG, "- onPostResume - yay!");
		return;
	}

	protected void onPause() {
		dispatchActivityPaused(); // AOSP dispatches Paused first
		Slog.i(TAG, "- onPause - yay!");

		for (Fragment fragment : fragments) {
			fragment.onPause();
		}

		paused = true;
		return;
	}

	protected void onStop() {
		Slog.i(TAG, "- onStop - yay!");

		for (Fragment fragment : fragments) {
			fragment.onStop();
		}

		dispatchActivityStopped();
		return;
	}

	protected void onDestroy() {
		Slog.i(TAG, "- onDestroy - yay!");

		for (Fragment fragment : fragments) {
			fragment.onDestroy();
		}

		destroyed = true;
		dispatchActivityDestroyed();
		return;
	}

	public void onWindowFocusChanged(boolean hasFocus) {
		Slog.i(TAG, "- onWindowFocusChanged - yay! (hasFocus: " + hasFocus + ")");

		return;
	}

	protected void onSaveInstanceState(Bundle outState) {
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
	protected void fileChooserResultCallback(int requestCode, int resultCode, int action, String uri) {
		onActivityResult(requestCode, resultCode, new Intent(FILE_CHOOSER_ACTIONS.get(action), uri != null ? Uri.parse(uri) : null));
	}

	public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
		Slog.i(TAG, "startActivityForResult(" + intent + ", " + requestCode + "," + options + ") called");
		if (intent.getComponent() != null) {
			try {
				final Activity activity = internalCreateActivity(intent.getComponent().getClassName(), getWindow().native_window, intent);
				activity.resultRequestCode = requestCode;
				activity.resultActivity = this;
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

	public void setResult(int resultCode, Intent data) {
		if (resultActivity != null) {
			resultActivity.onActivityResult(resultRequestCode, resultCode, data);
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
		Slog.i(TAG, "Activity.showDialog(" + id + ") called");
		Dialog dialog = dialogs.get(id);
		if (dialog == null)
			dialogs.put(id, dialog = onCreateDialog(id));
		if (dialog == null) {
			Slog.w(TAG, "Dialog " + id + " was not created");
			return;
		}
		onPrepareDialog(id, dialog);
		dialog.show();
	}

	public boolean showDialog(int id, Bundle args) {
		return false;
	}

	public void removeDialog(int id) {
		Dialog dialog = dialogs.remove(id);
		if (dialog != null)
			dialog.dismiss();
	}

	public void finish() {
		finishing = true;
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

	public Object getLastNonConfigurationInstance() {
		return null;
	}

	public FragmentManager getFragmentManager() {
		return new FragmentManager(this);
	}

	public LayoutInflater getLayoutInflater() {
		return (LayoutInflater)getSystemService("layout_inflater");
	}

	public boolean isChangingConfigurations() { return false; }

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
	}

	public void setTitle(int titleId) {
		this.title = getText(titleId);
	}

	public CharSequence getTitle() {
		return title;
	}

	public void onBackPressed() {
		System.out.println("onBackPressed() called");
		finish();
	}

	public void setIntent(Intent newIntent) {
		this.intent = newIntent;
	}

	public Intent getParentActivityIntent() {
		return null;
	}

	@Override
	public boolean onMenuOpened(int featureId, Menu menu) {
		Slog.i(TAG, "onMenuOpened(" + featureId + ", " + menu + ") called");
		return false;
	}

	public void recreate() {
		finishing = true;
		try {
			/* TODO: check if this is a toplevel activity */
			Activity activity = internalCreateActivity(this.getClass().getName(), getWindow().native_window, intent);
			new Handler().post(new Runnable() {
				@Override
				public void run() {
					nativeFinish(0);
					nativeStartActivity(activity);
				}
			});
		} catch (ReflectiveOperationException e) {
			Slog.i(TAG, "exception in Activity.recreate, this is kinda sus");
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

	protected void onNewIntent(Intent intent) {}

	public final Activity getParent() {
		return null;
	}

	public boolean hasWindowFocus() {
		return true; // FIXME?
	}

	public boolean isDestroyed() {
		return destroyed;
	}

	public void finishAffinity() {
		finish();
	}

	public void overridePendingTransition(int enterAnim, int exitAnim) {}

	public native boolean isTaskRoot();

	public void postponeEnterTransition() {}

	public void startPostponedEnterTransition() {}

	public boolean isChild() {
		return false;
	}

	public void setTaskDescription(ActivityManager.TaskDescription description) {}

	private native void nativeFinish(long native_window);
	public static native void nativeStartActivity(Activity activity);
	public static native boolean nativeResumeActivity(Class<? extends Activity> activityClass, Intent intent);
	public static native void nativeOpenURI(String uri);
	public native void nativeFileChooser(int action, String type, String title, int requestCode);
	public void reportFullyDrawn() {}
	public void setVisible(boolean visible) {}
	public Uri getReferrer() { return null; }
	public void setDefaultKeyMode(int flag) {}
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

	public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
		return null;
	}

	public boolean onSearchRequested() {
		return false;
	}

	public View getCurrentFocus() {
		return null;
	}

	public void setProgressBarIndeterminateVisibility(boolean indeterminate) {}

	public int getChangingConfigurations() {
		return 0;
	}

	public int getTaskId() {
		/* we don't support multiple activity stacks, so this is probably fine? */
		return System.identityHashCode(this.getApplicationContext());
	}

	boolean moveTaskToBack(boolean nonroot) {
		return true;
	}

	void setFinishOnTouchOutside(boolean finish) {
	}

	public void closeOptionsMenu() {
	}

	public void finishActivity(int requestCode) {
		/* TODO: track started activities so we can finish the right one here */
		Slog.w(TAG, "finishActivity: stub");
	}

	public void finishAfterTransition() {
		finish();
	}

	public boolean dispatchKeyEvent(KeyEvent event) {
		return false;
	}

	public void requestPermissions(String[] permissions, int requestCode) {
		Slog.w(TAG, "requestPermissions(" + Arrays.toString(permissions) + "): not handled");
	}

	public boolean shouldShowRequestPermissionRationale(String permission) {
		return true;
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
	public android.window.OnBackInvokedDispatcher getOnBackInvokedDispatcher() { return android.window.OnBackInvokedDispatcher.STUB; }
	public void addContentView(View view, ViewGroup.LayoutParams params) { setContentView(view, params); }
	public boolean isLaunchedFromBubble() { return false; }
	public void onUserInteraction() {}
	public boolean navigateUpTo(Intent upIntent) { return false; }
	public boolean shouldUpRecreateTask(Intent targetIntent) { return false; }
	public void setEnterSharedElementCallback(SharedElementCallback callback) {}
	public void setExitSharedElementCallback(SharedElementCallback callback) {}
	public boolean dispatchKeyShortcutEvent(android.view.KeyEvent event) { return false; }
	public void startIntentSenderForResult(android.content.IntentSender intent, int requestCode, Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags) {}
	public void startIntentSenderForResult(android.content.IntentSender intent, int requestCode, Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags, android.os.Bundle options) {}
	public void onTrimMemory(int level) {}

	/* ---- AOSP activity-level lifecycle callbacks (API 29) and the perform*() entry points ----
	 * ATL's native side calls performCreate/Start/Resume/Pause/Stop/Destroy (handle_cache.c, patched by
	 * native-lifecycle.sh); each wraps the virtual on*() with the Pre/Post dispatch exactly as AOSP's
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
	public final void performCreate(android.os.Bundle savedInstanceState) { dispatchActivityPreCreated(savedInstanceState); onCreate(savedInstanceState); dispatchActivityPostCreated(savedInstanceState); }
	/** AOSP: a stopped activity gets onRestart before onStart. */
	private boolean mStopped;
	public final void performStart() { if (mStopped) { mStopped = false; onRestart(); } dispatchActivityPreStarted(); onStart(); dispatchActivityPostStarted(); }
	public final void performResume() { dispatchActivityPreResumed(); onResume(); dispatchActivityPostResumed(); }
	public final void performPause() { dispatchActivityPrePaused(); onPause(); dispatchActivityPostPaused(); }
	public final void performStop() { dispatchActivityPreStopped(); onStop(); mStopped = true; dispatchActivityPostStopped(); }
	public final void performDestroy() { dispatchActivityPreDestroyed(); onDestroy(); dispatchActivityPostDestroyed(); }
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
		return null;
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
}
