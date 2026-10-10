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

	private static Thread sStackWatcher;

	/** Starts the trigger poller for an activity (no-op unless ATL_DEBUG_DUMPVIEWS is set). */
	public static void installPoller(final Activity activity) {
		if (!ENABLED)
			return;
		installStackWatcher();
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
					if (command.startsWith("config")) {
						// "config": the Configuration and DisplayMetrics the app sees
						android.content.res.Resources res = activity.getResources();
						Log.i(TAG, "config " + res.getConfiguration());
						Log.i(TAG, "metrics " + res.getDisplayMetrics());
						android.view.Display disp = activity.getWindowManager().getDefaultDisplay();
						android.graphics.Point p = new android.graphics.Point();
						disp.getSize(p);
						Log.i(TAG, "display size " + p.x + "x" + p.y + " rotation=" + disp.getRotation());
					} else if (command.startsWith("textprobe")) {
						// "textprobe [size]": log what the text measurement API reports for a few strings
						String[] parts = command.trim().split(" ");
						float size = parts.length > 1 ? Float.parseFloat(parts[1]) : 44f;
						textProbe(size);
					} else if (command.startsWith("hit ")) {
						// "hit X Y": what Compose's own hit test returns at a window position (per compose root)
						String[] parts = command.trim().split(" ");
						composeHitTest(decor, Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
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

	/**
	 * A daemon thread that logs the stack of every Java thread when /tmp/atl-dumpstacks appears
	 * (created by native debugging hooks, e.g. the thread-exit trace), independent of the main thread.
	 */
	private static synchronized void installStackWatcher() {
		if (sStackWatcher != null)
			return;
		sStackWatcher = new Thread("atl-stack-watcher") {
			@Override
			public void run() {
				final File trigger = new File("/tmp/atl-dumpstacks");
				while (true) {
					try {
						Thread.sleep(150);
					} catch (InterruptedException e) {
						return;
					}
					if (!trigger.exists())
						continue;
					trigger.delete();
					Log.i(TAG, "=== java thread stacks ===");
					for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
						Thread t = e.getKey();
						Log.i(TAG, "\"" + t.getName() + "\" id=" + t.getId() + " state=" + t.getState());
						for (StackTraceElement f : e.getValue())
							Log.i(TAG, "    at " + f);
					}
					Log.i(TAG, "=== end stacks ===");
				}
			}
		};
		sStackWatcher.setDaemon(true);
		sStackWatcher.start();
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
				if (n.equals("BackgroundElement")) {
					try {
						java.lang.reflect.Field f = mod.getClass().getDeclaredField("color");
						f.setAccessible(true);
						long color = f.getLong(mod);
						mods.append("#").append(Long.toHexString(color >>> 32)).append(',');
					} catch (Exception e) {
					}
				}
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
		sb.append(lpString(v.getLayoutParams()));
		sb.append(" ms=").append(specString(v.tlLastWidthMeasureSpec())).append('x').append(specString(v.tlLastHeightMeasureSpec()));
		sb.append(" md=").append(v.getMeasuredWidth()).append('x').append(v.getMeasuredHeight());
		android.graphics.drawable.Drawable bg = v.getBackground();
		if (bg != null) {
			sb.append(" bg=");
			if (bg instanceof android.graphics.drawable.ColorDrawable)
				sb.append("#").append(Integer.toHexString(((android.graphics.drawable.ColorDrawable) bg).getColor()));
			else
				sb.append(bg.getClass().getSimpleName());
		}
		if (v instanceof android.widget.TextView)
			sb.append(" tg=0x").append(Integer.toHexString(((android.widget.TextView) v).getGravity()));
		if (v.getClass().getName().endsWith("AndroidComposeView")) {
			android.content.res.Resources r = v.getContext().getResources();
			sb.append(" res=").append(Integer.toHexString(System.identityHashCode(r))).append("/ui=0x").append(Integer.toHexString(r.getConfiguration().uiMode))
			  .append(" ctx=").append(v.getContext().getClass().getSimpleName());
		}
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

	/** " lp=WxH[ w=weight][ g=gravity][ m=l,t,r,b]" with MATCH_PARENT/WRAP_CONTENT spelled out */
	static String lpString(android.view.ViewGroup.LayoutParams lp) {
		if (lp == null)
			return " lp=null";
		StringBuilder sb = new StringBuilder(" lp=").append(lpDim(lp.width)).append('x').append(lpDim(lp.height));
		if (lp instanceof android.widget.LinearLayout.LayoutParams) {
			android.widget.LinearLayout.LayoutParams l = (android.widget.LinearLayout.LayoutParams) lp;
			if (l.weight != 0)
				sb.append(" w=").append(l.weight);
			if (l.gravity != -1)
				sb.append(" g=0x").append(Integer.toHexString(l.gravity));
		} else if (lp instanceof android.widget.FrameLayout.LayoutParams) {
			sb.append(" g=0x").append(Integer.toHexString(((android.widget.FrameLayout.LayoutParams) lp).gravity));
		}
		if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
			android.view.ViewGroup.MarginLayoutParams m = (android.view.ViewGroup.MarginLayoutParams) lp;
			if (m.leftMargin != 0 || m.topMargin != 0 || m.rightMargin != 0 || m.bottomMargin != 0)
				sb.append(" m=").append(m.leftMargin).append(',').append(m.topMargin).append(',').append(m.rightMargin).append(',').append(m.bottomMargin);
		}
		return sb.toString();
	}

	static String lpDim(int d) {
		return d == android.view.ViewGroup.LayoutParams.MATCH_PARENT ? "MP" : d == android.view.ViewGroup.LayoutParams.WRAP_CONTENT ? "WC" : Integer.toString(d);
	}

	static String specString(int spec) {
		int mode = android.view.View.MeasureSpec.getMode(spec);
		return (mode == android.view.View.MeasureSpec.EXACTLY ? "E" : mode == android.view.View.MeasureSpec.AT_MOST ? "A" : "U")
				+ android.view.View.MeasureSpec.getSize(spec);
	}

	/** Runs LayoutNode.hitTest of every attached compose root at a window position and logs the hit nodes. */
	static void composeHitTest(View root, float x, float y) {
		java.util.ArrayList<View> composeViews = new java.util.ArrayList<>();
		collectByName(root, "AndroidComposeView", composeViews);
		for (View cv : composeViews) {
			if (!cv.isAttachedToWindow() || cv.getWidth() == 0)
				continue;
			int[] loc = new int[2];
			cv.getLocationInWindow(loc);
			float lx = x - loc[0], ly = y - loc[1];
			try {
				Object layoutNode = call(cv, "getRoot");
				Class<?> hitResultClass = Class.forName("androidx.compose.ui.node.HitTestResult", true, cv.getClass().getClassLoader());
				Object result = hitResultClass.getDeclaredConstructor().newInstance();
				java.lang.reflect.Method hit = null;
				for (java.lang.reflect.Method m : layoutNode.getClass().getMethods()) {
					if (m.getName().startsWith("hitTest") && !m.getName().contains("Semantics") && m.getParameterCount() == 4
							&& m.getParameterTypes()[0] == long.class && m.getParameterTypes()[1] == hitResultClass) {
						hit = m;
					}
				}
				if (hit == null) {
					Log.i(TAG, "hit: no LayoutNode.hitTest on " + layoutNode.getClass());
					continue;
				}
				long packed = (((long) Float.floatToRawIntBits(lx)) << 32) | (Float.floatToRawIntBits(ly) & 0xffffffffL);
				Object[] args = new Object[4];
				args[0] = packed;
				args[1] = result;
				for (int i = 2; i < 4; i++) // Compose 1.7: (isTouchEvent, isInLayer); 1.8: (pointerType=Touch, isInLayer)
					args[i] = hit.getParameterTypes()[i] == int.class ? (Object) 1 : (Object) Boolean.TRUE;
				hit.invoke(layoutNode, args);
				StringBuilder sb = new StringBuilder("hit " + x + "," + y + " in " + cv.getWidth() + "x" + cv.getHeight() + " @" + loc[0] + "," + loc[1] + ": ");
				int n = 0;
				for (Object node : (java.util.List<?>) result) {
					if (n++ > 24)
						break;
					String nn = node.getClass().getSimpleName().length() <= 2 ? node.getClass().getName() : node.getClass().getSimpleName();
					sb.append(nn);
					try {
						java.lang.reflect.Field ef = node.getClass().getDeclaredField("element");
						ef.setAccessible(true);
						Object el = ef.get(node);
						if (el != null) {
							sb.append('(').append(el.getClass().getSimpleName());
							// an interop filter wraps a lambda that captured the AndroidViewHolder: show that view
							try {
								for (java.lang.reflect.Field lf : el.getClass().getDeclaredFields()) {
									lf.setAccessible(true);
									Object lv = lf.get(el);
									if (lv == null) continue;
									for (java.lang.reflect.Field cf : lv.getClass().getDeclaredFields()) {
										cf.setAccessible(true);
										Object cv2 = cf.get(lv);
										if (cv2 instanceof View) {
											View hv = (View) cv2;
											sb.append(" view=").append(hv.getClass().getSimpleName()).append(' ').append(hv.getWidth()).append('x').append(hv.getHeight())
											  .append(" md=").append(hv.getMeasuredWidth()).append('x').append(hv.getMeasuredHeight()).append(" vis=").append(hv.getVisibility());
											if (hv instanceof android.view.ViewGroup && ((android.view.ViewGroup) hv).getChildCount() > 0) {
												View c0 = ((android.view.ViewGroup) hv).getChildAt(0);
												sb.append(" child=").append(c0.getClass().getSimpleName()).append(" vis=").append(c0.getVisibility()).append(' ').append(c0.getWidth()).append('x').append(c0.getHeight());
											}
										}
									}
								}
							} catch (Exception e) {
							}
							sb.append(')');
						}
					} catch (Exception e) {
					}
					try {
						java.lang.reflect.Field cf = null;
						for (Class<?> c = node.getClass(); c != null && cf == null; c = c.getSuperclass()) {
							try {
								cf = c.getDeclaredField("coordinator");
							} catch (NoSuchFieldException ignored) {
							}
						}
						cf.setAccessible(true);
						Object coord = cf.get(node);
						Class<?> lc = Class.forName("androidx.compose.ui.layout.LayoutCoordinates", true, cv.getClass().getClassLoader());
						Object rect = Class.forName("androidx.compose.ui.layout.LayoutCoordinatesKt", true, cv.getClass().getClassLoader())
								.getMethod("boundsInRoot", lc).invoke(null, coord);
						sb.append('@').append(rect);
						try {
							java.lang.reflect.Field lf = null;
							for (Class<?> c = coord.getClass(); c != null && lf == null; c = c.getSuperclass()) {
								try {
									lf = c.getDeclaredField("layoutNode");
								} catch (NoSuchFieldException ignored) {
								}
							}
							lf.setAccessible(true);
							Object ln = lf.get(coord);
							StringBuilder chain = new StringBuilder();
							Object n2 = ln;
							for (int d = 0; d < 6 && n2 != null; d++) {
								chain.append(d == 0 ? "#" : "<").append(call(n2, "getSemanticsId"));
								n2 = call(n2, "getParent$androidx_compose_ui_ui", "getParent$ui_release", "getParent");
							}
							sb.append(" depth=").append(call(ln, "getDepth$androidx_compose_ui_ui", "getDepth$ui_release", "getDepth")).append(' ').append(chain);
						} catch (Exception e) {
							sb.append(" tree?").append(e.getClass().getSimpleName());
						}
					} catch (Exception e) {
						sb.append("@?").append(e.getClass().getSimpleName());
					}
					if (node.getClass().getSimpleName().equals("AnchoredDraggableNode")) {
						try {
							java.lang.reflect.Field sf = null;
							for (Class<?> c = node.getClass(); c != null && sf == null; c = c.getSuperclass()) {
								try {
									sf = c.getDeclaredField("state");
								} catch (NoSuchFieldException ignored) {
								}
							}
							sf.setAccessible(true);
							Object st = sf.get(node);
							sb.append(" state{cur=").append(call(st, "getCurrentValue")).append(" target=").append(call(st, "getTargetValue"))
							  .append(" settled=").append(call(st, "getSettledValue")).append(" offset=").append(call(st, "getOffset"))
							  .append(" animating=").append(call(st, "isAnimationRunning")).append('}');
						} catch (Exception e) {
							sb.append(" state?").append(e);
						}
					}
					sb.append(' ');
				}
				if (n == 0)
					sb.append("(nothing)");
				Log.i(TAG, sb.toString());
			} catch (Exception e) {
				Log.i(TAG, "hit failed: " + e);
			}
		}
	}

	static void collectByName(View v, String simpleName, java.util.List<View> out) {
		if (v.getClass().getSimpleName().equals(simpleName))
			out.add(v);
		if (v instanceof android.view.ViewGroup) {
			android.view.ViewGroup g = (android.view.ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++)
				collectByName(g.getChildAt(i), simpleName, out);
		}
	}
}
