package android.atl.carhost;

import android.os.IBinder;
import android.os.IInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A readable one-line form of a Car App Library model object (a template, a trip, an alert): the
 * class name and the values of its public getters, recursively, read by reflection from the app's
 * own model classes. Getters that return nothing (null, false, empty) are left out, callbacks and
 * delegates (binders to the app) are named but not followed, lists show their first items.
 */
final class CarModelDump {
	private static final int MAX_DEPTH = 8;
	private static final int MAX_LIST_ITEMS = 12;
	private static final int MAX_LENGTH = 16000;

	private CarModelDump() {}

	static String describe(Object model) {
		StringBuilder sb = new StringBuilder();
		append(sb, model, 0, new IdentityHashMap<Object, Boolean>());
		if (sb.length() > MAX_LENGTH) {
			sb.setLength(MAX_LENGTH);
			sb.append("...");
		}
		return sb.toString();
	}

	private static boolean isModel(Class<?> c) {
		String name = c.getName();
		return name.startsWith("androidx.car.app.") || name.startsWith("androidx.core.graphics.drawable.IconCompat");
	}

	private static void append(StringBuilder sb, Object o, int depth, IdentityHashMap<Object, Boolean> seen) {
		if (sb.length() > MAX_LENGTH)
			return;
		if (o == null) {
			sb.append("null");
			return;
		}
		Class<?> c = o.getClass();
		if (o instanceof CharSequence) {
			sb.append('"').append(o.toString().replace("\n", "\\n")).append('"');
			return;
		}
		if (o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof Enum) {
			sb.append(o);
			return;
		}
		if (o instanceof Class) {
			sb.append(((Class<?>)o).getSimpleName()).append(".class");
			return;
		}
		if (o instanceof IBinder || o instanceof IInterface) {
			sb.append('<').append(c.getSimpleName()).append('>');
			return;
		}
		if (c.isArray()) {
			if (o instanceof int[]) {
				sb.append(Arrays.toString((int[])o));
			} else if (o instanceof Object[]) {
				appendList(sb, Arrays.asList((Object[])o), depth, seen);
			} else {
				sb.append(c.getSimpleName());
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
					sb.append(", ");
				first = false;
				append(sb, e.getKey(), depth + 1, seen);
				sb.append('=');
				append(sb, e.getValue(), depth + 1, seen);
			}
			sb.append('}');
			return;
		}
		String simple = c.getSimpleName();
		if (!isModel(c)) {
			sb.append(simple.isEmpty() ? c.getName() : simple);
			return;
		}
		/* text and callbacks of the library: their own string form, or just their kind */
		if (simple.equals("CarText")) {
			sb.append('"').append(String.valueOf(o).replace("\n", "\\n")).append('"');
			return;
		}
		if (simple.endsWith("Delegate") || simple.endsWith("DelegateImpl") || simple.endsWith("Listener") || simple.endsWith("Callback")) {
			sb.append('<').append(simple).append('>');
			return;
		}
		if (seen.containsKey(o) || depth >= MAX_DEPTH) {
			sb.append(simple).append("{...}");
			return;
		}
		seen.put(o, Boolean.TRUE);
		sb.append(simple).append('{');
		boolean first = true;
		for (Method m : getters(c)) {
			Object value;
			try {
				value = m.invoke(o);
			} catch (ReflectiveOperationException | RuntimeException e) {
				continue;
			}
			if (value == null || Boolean.FALSE.equals(value) || (value instanceof Collection && ((Collection<?>)value).isEmpty()))
				continue;
			if (!first)
				sb.append(", ");
			first = false;
			sb.append(propertyName(m.getName())).append('=');
			append(sb, value, depth + 1, seen);
		}
		sb.append('}');
		seen.remove(o);
	}

	private static void appendList(StringBuilder sb, Collection<?> items, int depth, IdentityHashMap<Object, Boolean> seen) {
		sb.append('[');
		int i = 0;
		for (Object item : items) {
			if (i > 0)
				sb.append(", ");
			if (i == MAX_LIST_ITEMS) {
				sb.append("... ").append(items.size() - i).append(" more");
				break;
			}
			append(sb, item, depth + 1, seen);
			i++;
		}
		sb.append(']');
	}

	/* the public no-argument get*()/is*() methods, in name order */
	private static Method[] getters(Class<?> c) {
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
		return arr;
	}

	private static String propertyName(String getter) {
		String p = getter.startsWith("get") ? getter.substring(3) : getter.substring(2);
		return Character.toLowerCase(p.charAt(0)) + p.substring(1);
	}
}
