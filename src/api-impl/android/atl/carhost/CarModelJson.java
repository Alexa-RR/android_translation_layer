package android.atl.carhost;

import android.os.IBinder;
import android.os.IInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A Car App Library model object (a template and everything in it) as JSON, for a renderer that
 * is not in this process (the car page). Read by reflection from the app's own model classes, the
 * values of their public getters, recursively:
 *
 * <ul>
 * <li>a model object: {"$": "NavigationTemplate", "actionStrip": {...}, ...}; values that carry
 *     nothing (null, false, empty lists) are left out, as the library's own defaults;</li>
 * <li>text (CarText): its string; numbers, booleans and enums as themselves;</li>
 * <li>a callback (OnClickDelegate, OnSelectedDelegate, SearchCallbackDelegate, ...): {"$cb":
 *     ID, "$": "OnClickDelegateImpl"}, an id in {@link #delegates} the renderer sends back to
 *     invoke it (see ATLCarAppChannel);</li>
 * <li>an icon (IconCompat): {"$": "IconCompat", "icon": ID, ...}, an id in {@link #icons}; the
 *     channel sends the image itself once.</li>
 * </ul>
 */
final class CarModelJson {
	private static final int MAX_DEPTH = 24;

	/** the callbacks of the models made into JSON, by id (the newest template's stay valid) */
	final HashMap<Integer, Object> delegates = new HashMap<>();
	/** the icons of the models made into JSON, by id */
	final HashMap<Integer, Object> icons = new HashMap<>();
	private final java.util.WeakHashMap<Object, Integer> iconIds = new java.util.WeakHashMap<>();
	/* image content to icon id (see iconKey) */
	private final HashMap<String, Integer> keyIds = new HashMap<>();

	/**
	 * What an IconCompat shows, as a key: the app's resource (package and id) for a resource icon,
	 * the pixels for a bitmap icon; null when neither can be read.
	 */
	private static String iconKey(Object icon) {
		try {
			int type = (Integer)icon.getClass().getMethod("getType").invoke(icon);
			if (type == 2 /* TYPE_RESOURCE */)
				return "res:" + icon.getClass().getMethod("getResPackage").invoke(icon) + ":" + icon.getClass().getMethod("getResId").invoke(icon);
		} catch (ReflectiveOperationException | RuntimeException e) {
			/* not a resource icon */
		}
		android.graphics.Bitmap bitmap = bitmapOf(icon);
		if (bitmap == null || bitmap.isRecycled())
			return null;
		int w = bitmap.getWidth(), h = bitmap.getHeight();
		int[] pixels = new int[w * h];
		bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
		return "bmp:" + w + "x" + h + ":" + Integer.toHexString(Arrays.hashCode(pixels));
	}

	/* the Bitmap an IconCompat of type BITMAP/ADAPTIVE_BITMAP holds (its field, whatever its name) */
	static android.graphics.Bitmap bitmapOf(Object icon) {
		for (Class<?> c = icon.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers()))
					continue;
				try {
					f.setAccessible(true);
					Object v = f.get(icon);
					if (v instanceof android.graphics.Bitmap)
						return (android.graphics.Bitmap)v;
				} catch (ReflectiveOperationException | RuntimeException e) {
					/* not readable: next */
				}
			}
		}
		return null;
	}
	private int nextId = 1;

	/* the callbacks of the template before the current one: a tap on what the renderer still
	 * shows while the next template is on its way reaches the app */
	private final HashMap<Integer, Object> previousDelegates = new HashMap<>();

	/** Starts a new template: the callbacks of the one before the previous are no longer offered. */
	void reset() {
		previousDelegates.clear();
		previousDelegates.putAll(delegates);
		delegates.clear();
	}

	/* callbacks of models shown apart from the templates (an alert): valid until released */
	private final HashMap<Integer, Object> keptDelegates = new HashMap<>();

	/** The callback with this id, of the current or the previous template or a kept model, or null. */
	Object delegate(int id) {
		Object d = delegates.get(id);
		if (d == null)
			d = previousDelegates.get(id);
		return d != null ? d : keptDelegates.get(id);
	}

	/** A model shown apart from the templates (an alert) as JSON; its callbacks stay valid until releaseKept. */
	String toJsonKept(Object model) {
		HashMap<Integer, Object> before = new HashMap<>(delegates);
		String json = toJson(model);
		for (java.util.Map.Entry<Integer, Object> e : new java.util.ArrayList<>(delegates.entrySet())) {
			if (!before.containsKey(e.getKey())) {
				keptDelegates.put(e.getKey(), e.getValue());
				delegates.remove(e.getKey());
			}
		}
		return json;
	}

	/** Forgets the kept callbacks (the alert went away). */
	void releaseKept() {
		keptDelegates.clear();
	}

	String toJson(Object model) {
		StringBuilder sb = new StringBuilder(4096);
		append(sb, model, 0, new IdentityHashMap<Object, Boolean>());
		return sb.toString();
	}

	static void quote(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			switch (ch) {
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					if (ch < 0x20)
						sb.append(String.format("\\u%04x", (int)ch));
					else
						sb.append(ch);
			}
		}
		sb.append('"');
	}

	private static boolean isModel(Class<?> c) {
		String name = c.getName();
		return name.startsWith("androidx.car.app.") || name.startsWith("androidx.core.graphics.drawable.IconCompat");
	}

	private static boolean isCallback(String simple) {
		return simple.endsWith("Delegate") || simple.endsWith("DelegateImpl") || simple.endsWith("Listener") || simple.endsWith("Callback");
	}

	private void append(StringBuilder sb, Object o, int depth, IdentityHashMap<Object, Boolean> seen) {
		if (o == null) {
			sb.append("null");
			return;
		}
		Class<?> c = o.getClass();
		if (o instanceof CharSequence) {
			quote(sb, o.toString());
			return;
		}
		if (o instanceof Boolean || o instanceof Number) {
			if (o instanceof Double && (((Double)o).isNaN() || ((Double)o).isInfinite()))
				sb.append("null");
			else if (o instanceof Float && (((Float)o).isNaN() || ((Float)o).isInfinite()))
				sb.append("null");
			else
				sb.append(o);
			return;
		}
		if (o instanceof Character || o instanceof Enum) {
			quote(sb, o.toString());
			return;
		}
		if (o instanceof IBinder || o instanceof IInterface) {
			sb.append("null");
			return;
		}
		if (c.isArray()) {
			if (o instanceof int[]) {
				sb.append(Arrays.toString((int[])o).replace(" ", ""));
			} else if (o instanceof Object[]) {
				appendList(sb, Arrays.asList((Object[])o), depth, seen);
			} else {
				sb.append("null");
			}
			return;
		}
		if (o instanceof Collection) {
			appendList(sb, (Collection<?>)o, depth, seen);
			return;
		}
		if (o instanceof Map) {
			sb.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : ((Map<?, ?>)o).entrySet()) {
				if (!first)
					sb.append(',');
				first = false;
				quote(sb, String.valueOf(e.getKey()));
				sb.append(':');
				append(sb, e.getValue(), depth + 1, seen);
			}
			sb.append('}');
			return;
		}
		String simple = c.getSimpleName();
		if (!isModel(c)) {
			sb.append("null");
			return;
		}
		if (simple.equals("CarText")) {
			/* a text with spans (a duration or distance in place of a placeholder character, an
			 * inline icon, a colour): {"$":"CarText","text":...,"spans":[{start,end,span}]}, which
			 * the renderer formats; a plain text: its string */
			CharSequence cs = null;
			try {
				Object v = c.getMethod("toCharSequence").invoke(o);
				if (v instanceof CharSequence)
					cs = (CharSequence)v;
			} catch (ReflectiveOperationException | RuntimeException e) {
				/* an older library: the plain text */
			}
			if (cs instanceof android.text.Spanned) {
				android.text.Spanned sp = (android.text.Spanned)cs;
				Object[] spans = sp.getSpans(0, sp.length(), Object.class);
				if (spans != null && spans.length > 0) {
					sb.append("{\"$\":\"CarText\",\"text\":");
					quote(sb, cs.toString());
					sb.append(",\"spans\":[");
					boolean first = true;
					for (Object span : spans) {
						if (!first)
							sb.append(',');
						first = false;
						sb.append("{\"start\":").append(sp.getSpanStart(span)).append(",\"end\":").append(sp.getSpanEnd(span)).append(",\"span\":");
						append(sb, span, depth + 1, seen);
						sb.append('}');
					}
					sb.append("]}");
					return;
				}
			}
			quote(sb, String.valueOf(o));
			return;
		}
		if (isCallback(simple)) {
			int id = nextId++;
			delegates.put(id, o);
			sb.append("{\"$\":");
			quote(sb, simple);
			sb.append(",\"$cb\":").append(id).append('}');
			return;
		}
		if (seen.containsKey(o) || depth >= MAX_DEPTH) {
			sb.append("null");
			return;
		}
		seen.put(o, Boolean.TRUE);
		sb.append("{\"$\":");
		quote(sb, simple);
		if (simple.equals("IconCompat")) {
			Integer id = iconIds.get(o);
			if (id == null) {
				/* the same image keeps its id across templates (the app makes new IconCompat
				 * objects for every template), so the renderer has it already */
				String key = iconKey(o);
				id = key != null ? keyIds.get(key) : null;
				if (id == null) {
					id = nextId++;
					if (key != null)
						keyIds.put(key, id);
					icons.put(id, o);
				}
				iconIds.put(o, id);
			}
			sb.append(",\"icon\":").append(id);
		}
		for (Method m : getters(c)) {
			Object value;
			try {
				value = m.invoke(o);
			} catch (ReflectiveOperationException | RuntimeException e) {
				continue;
			}
			if (value == null || Boolean.FALSE.equals(value) || (value instanceof Collection && ((Collection<?>)value).isEmpty()))
				continue;
			/* an IconCompat's drawable, bitmap and the like are sent as its image */
			if (simple.equals("IconCompat") && !(value instanceof Number || value instanceof CharSequence || value instanceof Boolean))
				continue;
			sb.append(',');
			quote(sb, propertyName(m.getName()));
			sb.append(':');
			append(sb, value, depth + 1, seen);
		}
		sb.append('}');
		seen.remove(o);
	}

	private void appendList(StringBuilder sb, Collection<?> items, int depth, IdentityHashMap<Object, Boolean> seen) {
		sb.append('[');
		boolean first = true;
		for (Object item : items) {
			if (!first)
				sb.append(',');
			first = false;
			append(sb, item, depth + 1, seen);
		}
		sb.append(']');
	}

	private static final HashMap<Class<?>, Method[]> sGetters = new HashMap<>();

	/* the public no-argument get*()/is*() methods, in name order */
	private static synchronized Method[] getters(Class<?> c) {
		Method[] cached = sGetters.get(c);
		if (cached != null)
			return cached;
		ArrayList<Method> out = new ArrayList<>();
		for (Method m : c.getMethods()) {
			if (m.getParameterTypes().length != 0 || Modifier.isStatic(m.getModifiers()) || m.getReturnType() == void.class)
				continue;
			if (m.getDeclaringClass() == Object.class)
				continue;
			String n = m.getName();
			if ((n.startsWith("get") && n.length() > 3) || (n.startsWith("is") && n.length() > 2 && m.getReturnType() == boolean.class))
				out.add(m);
		}
		Method[] arr = out.toArray(new Method[0]);
		Arrays.sort(arr, new Comparator<Method>() {
			@Override
			public int compare(Method a, Method b) {
				return a.getName().compareTo(b.getName());
			}
		});
		sGetters.put(c, arr);
		return arr;
	}

	private static String propertyName(String getter) {
		String p = getter.startsWith("get") ? getter.substring(3) : getter.substring(2);
		return Character.toLowerCase(p.charAt(0)) + p.substring(1);
	}
}
