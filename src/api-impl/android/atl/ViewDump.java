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
					String command = readTrigger();
					TRIGGER.delete();
					View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
					if (command.startsWith("textprobe")) {
						// "textprobe [size]": log what the text measurement API reports for a few strings
						String[] parts = command.trim().split(" ");
						float size = parts.length > 1 ? Float.parseFloat(parts[1]) : 44f;
						textProbe(size);
					} else if (command.startsWith("setvis ")) {
						// "setvis <id hex> <0|4|8>": change a view's visibility (debugging the stacking of views)
						String[] parts = command.trim().split(" ");
						int id = (int) Long.parseLong(parts[1].replace("0x", ""), 16);
						View target = decor != null ? decor.findViewById(id) : null;
						Log.i(TAG, "setvis " + parts[1] + " -> " + (target == null ? "not found" : target.getClass().getName()));
						if (target != null)
							target.setVisibility(Integer.parseInt(parts[2]));
					} else {
						Log.i(TAG, "=== view hierarchy of " + activity.getClass().getName() + " ===");
						if (decor != null) {
							dump(decor, 0);
							nativeDumpGtk(decor.widget);
						}
						Log.i(TAG, "=== end ===");
					}
				}
				handler.postDelayed(this, 1000);
			}
		}, 1000);
	}

	/** Logs Paint/StaticLayout measurements (tag ViewDump) for "H", "Hey Wazer!" and the empty string. */
	private static void textProbe(float size) {
		android.text.TextPaint paint = new android.text.TextPaint();
		paint.setTextSize(size);
		android.graphics.Paint.FontMetricsInt fmi = paint.getFontMetricsInt();
		android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
		Log.i(TAG, "textprobe size=" + size + " ascent=" + paint.ascent() + " descent=" + paint.descent()
				+ " fmi top/asc/desc/bottom/lead=" + fmi.top + "/" + fmi.ascent + "/" + fmi.descent + "/" + fmi.bottom + "/" + fmi.leading
				+ " fm=" + fm.top + "/" + fm.ascent + "/" + fm.descent + "/" + fm.bottom + " spacing=" + paint.getFontSpacing()
				+ " letterSpacing=" + paint.getLetterSpacing() + " scaleX=" + paint.getTextScaleX());
		for (String t : new String[] { "H", "Hey Wazer!", "" }) {
			android.graphics.Rect r = new android.graphics.Rect();
			paint.getTextBounds(t, 0, t.length(), r);
			float[] widths = new float[Math.max(1, t.length())];
			int n = paint.getTextWidths(t, widths);
			StringBuilder w = new StringBuilder();
			for (int i = 0; i < n; i++)
				w.append(i > 0 ? "," : "").append(widths[i]);
			android.text.StaticLayout l = android.text.StaticLayout.Builder.obtain(t, 0, t.length(), paint, 100000).setIncludePad(false).build();
			Log.i(TAG, "textprobe \"" + t + "\": measureText=" + paint.measureText(t) + " bounds=" + r + " widths=[" + w + "]"
					+ " layout h=" + l.getHeight() + " lines=" + l.getLineCount() + " top0=" + l.getLineTop(0) + " bottom0=" + l.getLineBottom(0)
					+ " asc0=" + l.getLineAscent(0) + " desc0=" + l.getLineDescent(0) + " base0=" + l.getLineBaseline(0) + " w0=" + l.getLineWidth(0)
					+ " desired=" + android.text.Layout.getDesiredWidth(t, paint) + " runAdvance=" + paint.getRunAdvance(t, 0, t.length(), 0, t.length(), false, t.length()));
		}
	}

	private static String readTrigger() {
		try {
			java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(TRIGGER));
			String line = r.readLine();
			r.close();
			return line == null ? "" : line;
		} catch (java.io.IOException e) {
			return "";
		}
	}

	/** Writes the GTK widget tree containing the widget to stderr (allocations, mapping, child order). */
	private static native void nativeDumpGtk(long widget);

	/**
	 * Walks a Compose owner view's LayoutNode tree through reflection (the names carry the
	 * androidx_compose_ui_ui visibility suffix of the Kotlin internals): size, placement and the
	 * modifier classes of every node, so Compose content shows up next to the View tree.
	 */
	private static void dumpComposeTree(View composeView, int depth) {
		try {
			Object root = composeView.getClass().getMethod("getRoot").invoke(composeView);
			dumpLayoutNode(root, depth, 0);
		} catch (Throwable t) {
			Log.i(TAG, indent(depth) + "(compose tree unavailable: " + t + ")");
		}
	}

	private static String indent(int depth) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < depth; i++)
			sb.append("  ");
		return sb.toString();
	}

	private static Object call(Object o, String... names) throws Exception {
		for (String n : names) {
			try {
				java.lang.reflect.Method m = o.getClass().getMethod(n);
				m.setAccessible(true);
				return m.invoke(o);
			} catch (NoSuchMethodException e) {
				// try the next name
			}
		}
		throw new NoSuchMethodException(names[0]);
	}

	private static void dumpLayoutNode(Object node, int depth, int count) throws Exception {
		if (count > 400)
			return;
		StringBuilder sb = new StringBuilder(indent(depth)).append("~node");
		try {
			sb.append(' ').append(call(node, "getWidth")).append('x').append(call(node, "getHeight"));
		} catch (Exception e) {
		}
		try {
			Object placed = call(node, "isPlaced");
			if (Boolean.FALSE.equals(placed))
				sb.append(" NOT-PLACED");
		} catch (Exception e) {
		}
		try {
			java.util.List<?> infos = (java.util.List<?>) call(node, "getModifierInfo");
			StringBuilder mods = new StringBuilder();
			for (Object info : infos) {
				Object mod = call(info, "getModifier");
				String n = mod.getClass().getSimpleName();
				if (n.isEmpty())
					n = mod.getClass().getName();
				if (mods.length() < 160)
					mods.append(n).append(',');
			}
			if (mods.length() > 0)
				sb.append(" [").append(mods).append(']');
		} catch (Exception e) {
		}
		Log.i(TAG, sb.toString());
		java.util.List<?> children = (java.util.List<?>) call(node, "getChildren$androidx_compose_ui_ui", "getChildren$ui_release", "getChildren");
		for (Object c : children)
			dumpLayoutNode(c, depth + 1, count + 1);
	}

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
		if (v.getClass().getName().equals("androidx.compose.ui.platform.AndroidComposeView"))
			dumpComposeTree(v, depth + 1);
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++)
				dump(g.getChildAt(i), depth + 1);
		}
	}
}
