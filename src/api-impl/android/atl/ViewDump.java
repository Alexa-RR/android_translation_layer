package android.atl;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;

/**
 * teslalinux debugging aid: ATL_DEBUG_DUMPVIEWS=1 makes every resumed Activity poll (once a second)
 * for the trigger file /tmp/atl-dumpviews; when it appears the file is removed and the window's view
 * hierarchy is written to the log (tag ViewDump), one line per view: class, id, visibility, alpha,
 * position and size, attachment state and, for text views, the text. tap.sh dump creates the trigger.
 */
public final class ViewDump {
	private static final String TAG = "ViewDump";
	private static final File TRIGGER = new File("/tmp/atl-dumpviews");
	private static final boolean ENABLED = System.getenv("ATL_DEBUG_DUMPVIEWS") != null;

	private ViewDump() {
	}

	/** Starts the trigger poller for an activity (no-op unless ATL_DEBUG_DUMPVIEWS is set). */
	public static void installPoller(final Activity activity) {
		if (!ENABLED)
			return;
		final Handler handler = new Handler(Looper.getMainLooper());
		handler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (activity.isDestroyed())
					return;
				if (TRIGGER.exists()) {
					TRIGGER.delete();
					View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
					Log.i(TAG, "=== view hierarchy of " + activity.getClass().getName() + " ===");
					if (decor != null) {
						dump(decor, 0);
						nativeDumpGtk(decor.widget);
					}
					Log.i(TAG, "=== end ===");
				}
				handler.postDelayed(this, 1000);
			}
		}, 1000);
	}

	/** Writes the GTK widget tree containing the widget to stderr (allocations, mapping, child order). */
	private static native void nativeDumpGtk(long widget);

	public static void dump(View v, int depth) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < depth; i++)
			sb.append("  ");
		sb.append(v.getClass().getName());
		if (v.getId() != View.NO_ID)
			sb.append(" id=0x").append(Integer.toHexString(v.getId()));
		sb.append(" vis=").append(v.getVisibility() == View.VISIBLE ? "V" : v.getVisibility() == View.INVISIBLE ? "I" : "G");
		sb.append(" alpha=").append(v.getAlpha());
		sb.append(" at ").append(v.getLeft()).append(',').append(v.getTop());
		sb.append(" size ").append(v.getWidth()).append('x').append(v.getHeight());
		if (v.getTranslationX() != 0 || v.getTranslationY() != 0)
			sb.append(" tr ").append(v.getTranslationX()).append(',').append(v.getTranslationY());
		sb.append(v.isAttachedToWindow() ? " attached" : " DETACHED");
		if (v instanceof TextView) {
			CharSequence t = ((TextView) v).getText();
			if (t != null && t.length() > 0)
				sb.append(" text=\"").append(t.length() > 40 ? t.subSequence(0, 40) + "..." : t).append('"');
		}
		Log.i(TAG, sb.toString());
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++)
				dump(g.getChildAt(i), depth + 1);
		}
	}
}
