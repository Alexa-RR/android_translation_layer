package android.animation;

import android.graphics.Path;
import android.graphics.PointF;
import android.util.Log;
import android.util.Property;
import java.lang.reflect.Method;

/**
 * A property and the values it is animated between (AOSP API). The values are a keyframe set as in
 * AOSP's KeyframeSet: evenly spaced keyframes for ofFloat/ofInt/ofObject, the given keyframes for
 * ofKeyframe, or keyframes that compute their own values (PathKeyframes for the Path variants). The
 * fraction is not clamped, so an overshooting interpolator extrapolates the first and last
 * intervals as AOSP does. A TypeEvaluator, when set, computes every value (ArgbEvaluator over int
 * colours); a TypeConverter converts the animated value to the property's type.
 */
public class PropertyValuesHolder implements Cloneable {

	private float values_float[];
	private int values_int[];
	private Object values_object[];
	private Object value;
	private Keyframe values_keyframe[];

	private String property_name;
	private Method setter;
	Property property;
	private TypeEvaluator<Object> evaluator;

	/** keyframes that compute their own values (AOSP Keyframes: PathKeyframes and its x/y halves) */
	private Keyframes tlKeyframes;
	/** AOSP: converts the animated value to the type the property takes */
	private TypeConverter tlConverter;
	/** ofMultiFloat/ofMultiInt: the setter takes this many float or int arguments (0: one value) */
	private int tlMultiArgs;
	private boolean tlMultiInt;
	/** the keyframes built from the value arrays, and the array they were built from */
	private Keyframe[] tlKfCache;
	private Object tlKfSource;

	public static PropertyValuesHolder ofFloat(String propertyName, float... values) {
		PropertyValuesHolder propertyValuesHolder = new PropertyValuesHolder();
		propertyValuesHolder.values_float = values;
		propertyValuesHolder.property_name = propertyName;
		propertyValuesHolder.value = values[0];
		return propertyValuesHolder;
	}

	public static PropertyValuesHolder ofObject(String propertyName, TypeEvaluator evaluator, Object... values) {
		PropertyValuesHolder propertyValuesHolder = new PropertyValuesHolder();
		propertyValuesHolder.values_object = values;
		propertyValuesHolder.property_name = propertyName;
		propertyValuesHolder.value = values[0];
		propertyValuesHolder.evaluator = evaluator;
		return propertyValuesHolder;
	}

	public static PropertyValuesHolder ofInt(String propertyName, int... values) {
		PropertyValuesHolder propertyValuesHolder = new PropertyValuesHolder();
		propertyValuesHolder.values_int = values;
		propertyValuesHolder.property_name = propertyName;
		propertyValuesHolder.value = values[0];
		return propertyValuesHolder;
	}

	public static PropertyValuesHolder ofKeyframe(String propertyName, Keyframe... values) {
		PropertyValuesHolder propertyValuesHolder = new PropertyValuesHolder();
		propertyValuesHolder.values_keyframe = values;
		propertyValuesHolder.property_name = propertyName;
		propertyValuesHolder.value = values[0].getValue();
		return propertyValuesHolder;
	}

	public static PropertyValuesHolder ofFloat(Property property, float... values) {
		PropertyValuesHolder propertyValuesHolder = new PropertyValuesHolder();
		propertyValuesHolder.values_float = values;
		propertyValuesHolder.property_name = property.getName();
		propertyValuesHolder.property = property;
		propertyValuesHolder.value = values[0];
		return propertyValuesHolder;
	}

	public static PropertyValuesHolder ofObject(Property property, TypeEvaluator evaluator, Object... values) {
		PropertyValuesHolder propertyValuesHolder = new PropertyValuesHolder();
		propertyValuesHolder.values_object = values;
		propertyValuesHolder.property_name = property.getName();
		propertyValuesHolder.property = property;
		propertyValuesHolder.value = values[0];
		propertyValuesHolder.evaluator = evaluator;
		return propertyValuesHolder;
	}

	public static PropertyValuesHolder ofInt(Property property, int... values) {
		PropertyValuesHolder propertyValuesHolder = new PropertyValuesHolder();
		propertyValuesHolder.values_int = values;
		propertyValuesHolder.property_name = property.getName();
		propertyValuesHolder.property = property;
		propertyValuesHolder.value = values[0];
		return propertyValuesHolder;
	}

	public static PropertyValuesHolder ofKeyframe(Property property, Keyframe... values) {
		PropertyValuesHolder propertyValuesHolder = new PropertyValuesHolder();
		propertyValuesHolder.values_keyframe = values;
		propertyValuesHolder.property_name = property.getName();
		propertyValuesHolder.property = property;
		propertyValuesHolder.value = values[0].getValue();
		return propertyValuesHolder;
	}

	/** AOSP (hidden): values from a Keyframes object (PathKeyframes' x or y half, ...). */
	static PropertyValuesHolder ofKeyframes(String propertyName, Keyframes keyframes) {
		PropertyValuesHolder pvh = new PropertyValuesHolder();
		pvh.property_name = propertyName;
		pvh.tlKeyframes = keyframes;
		return pvh;
	}

	static PropertyValuesHolder ofKeyframes(Property property, Keyframes keyframes) {
		PropertyValuesHolder pvh = ofKeyframes(property.getName(), keyframes);
		pvh.property = property;
		return pvh;
	}

	/** AOSP API 21: the point along the path, converted to the property's type. */
	public static PropertyValuesHolder ofObject(String propertyName, TypeConverter<PointF, ?> converter, Path path) {
		PropertyValuesHolder pvh = ofKeyframes(propertyName, new PathKeyframes(path));
		pvh.tlConverter = converter;
		return pvh;
	}

	public static <V> PropertyValuesHolder ofObject(Property<?, V> property, TypeConverter<PointF, V> converter, Path path) {
		PropertyValuesHolder pvh = ofKeyframes(property, new PathKeyframes(path));
		pvh.tlConverter = converter;
		return pvh;
	}

	/** AOSP API 23: values of one type evaluated, then converted to the property's type. */
	@SafeVarargs
	public static <T, V> PropertyValuesHolder ofObject(Property<?, V> property, TypeConverter<T, V> converter, TypeEvaluator<T> evaluator, T... values) {
		PropertyValuesHolder pvh = ofObject(property, evaluator, (Object[]) values);
		pvh.tlConverter = converter;
		return pvh;
	}

	/** AOSP API 21: the point along the path set through a setter of two float arguments (setXY(float, float)). */
	public static PropertyValuesHolder ofMultiFloat(String propertyName, Path path) {
		PropertyValuesHolder pvh = ofKeyframes(propertyName, new PathKeyframes(path));
		pvh.tlMultiArgs = 2;
		return pvh;
	}

	/** AOSP API 21: the point along the path, rounded, set through a setter of two int arguments. */
	public static PropertyValuesHolder ofMultiInt(String propertyName, Path path) {
		PropertyValuesHolder pvh = ofMultiFloat(propertyName, path);
		pvh.tlMultiInt = true;
		return pvh;
	}

	/** AOSP API 21: float arrays (one per value) set through a setter of that many float arguments. */
	public static PropertyValuesHolder ofMultiFloat(String propertyName, float[][] values) {
		if (values.length < 2)
			throw new IllegalArgumentException("At least 2 values must be supplied");
		PropertyValuesHolder pvh = ofObject(propertyName, new FloatArrayEvaluator(), (Object[]) values);
		pvh.tlMultiArgs = values[0].length;
		return pvh;
	}

	public static PropertyValuesHolder ofMultiInt(String propertyName, int[][] values) {
		if (values.length < 2)
			throw new IllegalArgumentException("At least 2 values must be supplied");
		PropertyValuesHolder pvh = ofObject(propertyName, new IntArrayEvaluator(), (Object[]) values);
		pvh.tlMultiArgs = values[0].length;
		pvh.tlMultiInt = true;
		return pvh;
	}

	/** AOSP: converts the animated values to the property's type. */
	public void setConverter(TypeConverter converter) {
		tlConverter = converter;
	}

	public void setIntValues(int... values) {
		tlClearValues();
		values_int = values;
	}

	public void setFloatValues(float... values) {
		tlClearValues();
		values_float = values;
	}

	public void setObjectValues(Object... values) {
		tlClearValues();
		values_object = values;
	}

	public void setKeyframes(Keyframe... values) {
		tlClearValues();
		values_keyframe = values;
	}

	private void tlClearValues() {
		values_float = null;
		values_int = null;
		values_object = null;
		values_keyframe = null;
		tlKeyframes = null;
	}

	public String getProperty_name() {
		return property_name;
	}

	public void setProperty_name(String propertyName) {
		this.property_name = propertyName;
		property = null;
	}

	/** AOSP: the name of the property animated. */
	public String getPropertyName() {
		return property_name;
	}

	public void setPropertyName(String propertyName) {
		setProperty_name(propertyName);
	}

	public void setProperty(Property property) {
		property_name = property.getName();
		this.property = property;
	}

	public void init() {}

	public Object getAnimatedValue() {
		return value;
	}

	@SuppressWarnings("unchecked")
	public void setEvaluator(TypeEvaluator evaluator) {
		this.evaluator = (TypeEvaluator<Object>) evaluator;
		if (tlKeyframes != null)
			tlKeyframes.setEvaluator(evaluator);
	}

	/* ---- AOSP KeyframeSet ---- */

	/** The keyframes of the value arrays (evenly spaced; one value animates from the type's zero). */
	private Keyframe[] tlKeyframeArray() {
		Object source = values_keyframe != null ? values_keyframe : values_float != null ? values_float : values_int != null ? values_int : values_object;
		if (source == null)
			return null;
		if (source == tlKfSource && tlKfCache != null)
			return tlKfCache;
		Keyframe[] kf;
		if (values_keyframe != null) {
			kf = values_keyframe;
		} else if (values_float != null) {
			int n = values_float.length;
			kf = new Keyframe[Math.max(n, 2)];
			if (n == 1) {
				kf[0] = Keyframe.ofFloat(0f);
				kf[1] = Keyframe.ofFloat(1f, values_float[0]);
			} else {
				for (int i = 0; i < n; i++)
					kf[i] = Keyframe.ofFloat((float) i / (n - 1), values_float[i]);
			}
		} else if (values_int != null) {
			int n = values_int.length;
			kf = new Keyframe[Math.max(n, 2)];
			if (n == 1) {
				kf[0] = Keyframe.ofInt(0f);
				kf[1] = Keyframe.ofInt(1f, values_int[0]);
			} else {
				for (int i = 0; i < n; i++)
					kf[i] = Keyframe.ofInt((float) i / (n - 1), values_int[i]);
			}
		} else {
			int n = values_object.length;
			kf = new Keyframe[Math.max(n, 2)];
			if (n == 1) {
				kf[0] = Keyframe.ofObject(0f);
				kf[1] = Keyframe.ofObject(1f, values_object[0]);
			} else {
				for (int i = 0; i < n; i++)
					kf[i] = Keyframe.ofObject((float) i / (n - 1), values_object[i]);
			}
		}
		tlKfSource = source;
		tlKfCache = kf;
		return kf;
	}

	/** The value between two keyframe values: the evaluator's, else linear for numbers. */
	private Object tlInterpolate(float fraction, Object start, Object end) {
		if (evaluator != null)
			return evaluator.evaluate(fraction, start, end);
		if (start instanceof Float && end instanceof Float) {
			float s = (Float) start;
			return s + fraction * ((Float) end - s);
		}
		if (start instanceof Integer && end instanceof Integer) {
			int s = (Integer) start;
			return s + (int) (fraction * ((Integer) end - s));
		}
		// no evaluator for object values: the nearer of the two
		return fraction < 0.5f ? start : end;
	}

	/** AOSP KeyframeSet.getValue: the interval around the fraction, its interpolator, then the evaluator. */
	private Object tlKeyframeValue(Keyframe[] kf, float fraction) {
		final int n = kf.length;
		final Keyframe first = kf[0], last = kf[n - 1];
		if (n == 2) {
			TimeInterpolator interp = last.getInterpolator();
			if (interp != null)
				fraction = interp.getInterpolation(fraction);
			return tlInterpolate(fraction, first.getValue(), last.getValue());
		}
		if (fraction <= 0f) {
			final Keyframe next = kf[1];
			TimeInterpolator interp = next.getInterpolator();
			if (interp != null)
				fraction = interp.getInterpolation(fraction);
			float prevFraction = first.getFraction();
			float intervalFraction = (fraction - prevFraction) / (next.getFraction() - prevFraction);
			return tlInterpolate(intervalFraction, first.getValue(), next.getValue());
		} else if (fraction >= 1f) {
			final Keyframe prev = kf[n - 2];
			TimeInterpolator interp = last.getInterpolator();
			if (interp != null)
				fraction = interp.getInterpolation(fraction);
			float prevFraction = prev.getFraction();
			float intervalFraction = (fraction - prevFraction) / (last.getFraction() - prevFraction);
			return tlInterpolate(intervalFraction, prev.getValue(), last.getValue());
		}
		Keyframe prev = first;
		for (int i = 1; i < n; ++i) {
			Keyframe next = kf[i];
			if (fraction < next.getFraction()) {
				TimeInterpolator interp = next.getInterpolator();
				float prevFraction = prev.getFraction();
				float intervalFraction = (fraction - prevFraction) / (next.getFraction() - prevFraction);
				if (interp != null)
					intervalFraction = interp.getInterpolation(intervalFraction);
				return tlInterpolate(intervalFraction, prev.getValue(), next.getValue());
			}
			prev = next;
		}
		return last.getValue();
	}

	public void calculateValue(float fraction) {
		Object v;
		if (tlKeyframes != null) {
			v = tlKeyframes.getValue(fraction);
		} else {
			Keyframe[] kf = tlKeyframeArray();
			if (kf == null) {
				Log.e("PropertyValuesHolder", "No values set");
				return;
			}
			v = tlKeyframeValue(kf, fraction);
		}
		if (tlMultiArgs > 0 && v instanceof PointF) {
			PointF p = (PointF) v;
			v = tlMultiInt ? (Object) new int[] { Math.round(p.x), Math.round(p.y) } : (Object) new float[] { p.x, p.y };
		}
		value = tlConverter != null ? tlConverter.convert(v) : v;
	}

	@Override
	public PropertyValuesHolder clone() {
		PropertyValuesHolder propertyValuesHolder = new PropertyValuesHolder();
		propertyValuesHolder.property = property;
		propertyValuesHolder.property_name = property_name;
		propertyValuesHolder.setter = setter;
		propertyValuesHolder.value = value;
		propertyValuesHolder.values_float = values_float;
		propertyValuesHolder.values_int = values_int;
		propertyValuesHolder.values_object = values_object;
		propertyValuesHolder.values_keyframe = values_keyframe;
		propertyValuesHolder.evaluator = evaluator;
		propertyValuesHolder.tlKeyframes = tlKeyframes != null ? tlKeyframes.clone() : null;
		propertyValuesHolder.tlConverter = tlConverter;
		propertyValuesHolder.tlMultiArgs = tlMultiArgs;
		propertyValuesHolder.tlMultiInt = tlMultiInt;
		return propertyValuesHolder;
	}

	/** The type the setter takes (AOSP: the converter's target, the keyframes' type, the values' type). */
	private Class<?> tlValueType() {
		if (tlConverter != null)
			return tlConverter.getTargetType();
		if (tlKeyframes != null)
			return tlKeyframes.getType();
		if (values_float != null)
			return float.class;
		if (values_int != null)
			return int.class;
		if (values_keyframe != null) {
			Class<?> t = values_keyframe[values_keyframe.length - 1].getType();
			return t != null ? t : Object.class;
		}
		return values_object[0].getClass();
	}

	/** AOSP getPropertyFunction: the setter for the type, else one for its boxed/unboxed or numeric relatives. */
	private static Method tlFindSetter(Class<?> targetClass, String name, Class<?> type) {
		Class<?>[] candidates;
		if (type == float.class || type == Float.class)
			candidates = new Class<?>[] { float.class, Float.class, double.class, int.class, Double.class, Integer.class };
		else if (type == int.class || type == Integer.class)
			candidates = new Class<?>[] { int.class, Integer.class, float.class, double.class, Float.class, Double.class };
		else if (type == double.class || type == Double.class)
			candidates = new Class<?>[] { double.class, Double.class, float.class, int.class, Float.class, Integer.class };
		else
			candidates = new Class<?>[] { type };
		for (Class<?> c : candidates) {
			try {
				return targetClass.getMethod(name, c);
			} catch (NoSuchMethodException e) {
			}
		}
		// a setter of a supertype of the value's class
		for (Method m : targetClass.getMethods())
			if (m.getName().equals(name) && m.getParameterTypes().length == 1 && m.getParameterTypes()[0].isAssignableFrom(type))
				return m;
		return null;
	}

	/** ofMultiFloat/ofMultiInt: a setter with the given number of float (or int) arguments. */
	private Method tlFindMultiSetter(Class<?> targetClass, String name) {
		Class<?>[] args = new Class<?>[tlMultiArgs];
		java.util.Arrays.fill(args, tlMultiInt ? int.class : float.class);
		try {
			return targetClass.getMethod(name, args);
		} catch (NoSuchMethodException e) {
			java.util.Arrays.fill(args, tlMultiInt ? float.class : int.class);
			try {
				return targetClass.getMethod(name, args);
			} catch (NoSuchMethodException e2) {
				return null;
			}
		}
	}

	public void setupSetterAndGetter(Object target) {
		if (property != null) {
			setter = null;
			if (values_int != null && values_int.length == 1) {
				values_int = new int[] {(Integer)property.get(target), values_int[0]};
			} else if (values_float != null && values_float.length == 1) {
				values_float = new float[] {(Float)property.get(target), values_float[0]};
			} else if (values_object != null && values_object.length == 1) {
				values_object = new Object[] {property.get(target), values_object[0]};
			}
			return;
		}
		try {
			String capitalized = property_name.substring(0, 1).toUpperCase() + property_name.substring(1);
			if (tlMultiArgs > 0)
				setter = tlFindMultiSetter(target.getClass(), "set" + capitalized);
			else
				setter = tlFindSetter(target.getClass(), "set" + capitalized, tlValueType());
			if (setter == null)
				throw new NoSuchMethodException("set" + capitalized + " on " + target.getClass().getName());
			setter.setAccessible(true); // AOSP: public setters of non-public classes (VectorDrawable groups)
			/* AOSP: the getter is only consulted when a single value was given (the start value comes from the target) */
			if ((values_int != null && values_int.length == 1) || (values_float != null && values_float.length == 1)
					|| (values_object != null && values_object.length == 1)) {
				Method getter = target.getClass().getMethod("get" + capitalized);
				getter.setAccessible(true);
				if (values_int != null) {
					values_int = new int[] {((Number)getter.invoke(target)).intValue(), values_int[0]};
				} else if (values_float != null) {
					values_float = new float[] {((Number)getter.invoke(target)).floatValue(), values_float[0]};
				} else {
					values_object = new Object[] {getter.invoke(target), values_object[0]};
				}
			}
		} catch (ReflectiveOperationException e) {
			Log.e("PropertyValuesHolder", "failed to setup setter and getter", e);
		}
	}

	/** AOSP: the first value becomes the target's current value of the property (LayoutTransition, setupStartValues). */
	public void setupStartValue(Object target) {
		tlSetupValue(target, true);
	}

	/** AOSP: the last value becomes the target's current value of the property. */
	public void setupEndValue(Object target) {
		tlSetupValue(target, false);
	}

	public void setAnimatedValue(Object target) {
		if (property != null) {
			property.set(target, value);
			return;
		} else if (setter != null && value != null) {
			try {
				if (tlMultiArgs > 0) {
					Object[] args = new Object[tlMultiArgs];
					Class<?> t = setter.getParameterTypes()[0];
					for (int i = 0; i < tlMultiArgs; i++) {
						float f = value instanceof int[] ? ((int[]) value)[i] : ((float[]) value)[i];
						args[i] = t == int.class ? (Object) Math.round(f) : (Object) f;
					}
					setter.invoke(target, args);
					return;
				}
				Object v = value;
				Class<?> t = setter.getParameterTypes()[0];
				if (v instanceof Number) {
					// the setter found for a numeric relative of the value's type
					if (t == float.class || t == Float.class)
						v = ((Number) v).floatValue();
					else if (t == int.class || t == Integer.class)
						v = ((Number) v).intValue();
					else if (t == double.class || t == Double.class)
						v = ((Number) v).doubleValue();
				}
				setter.invoke(target, v);
			} catch (ReflectiveOperationException e) {
				Log.e("PropertyValuesHolder", "failed to invoke setter", e);
			}
		} else {
			Log.e("PropertyValuesHolder", "no setter or value set");
		}
	}

	/* ---- teslalinux (gfx): start/end values from the target (AOSP setupStartValue/setupEndValue) ---- */

	private void tlSetupValue(Object target, boolean start) {
		Object v;
		try {
			if (property != null) {
				v = property.get(target);
			} else {
				String capitalized = property_name.substring(0, 1).toUpperCase() + property_name.substring(1);
				Method getter = target.getClass().getMethod("get" + capitalized);
				getter.setAccessible(true);
				v = getter.invoke(target);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			Log.e("PropertyValuesHolder", "no getter for " + property_name + " on " + target, e);
			return;
		}
		if (values_int != null && v instanceof Number) {
			values_int = values_int.clone();
			values_int[start ? 0 : values_int.length - 1] = ((Number) v).intValue();
		} else if (values_float != null && v instanceof Number) {
			values_float = values_float.clone();
			values_float[start ? 0 : values_float.length - 1] = ((Number) v).floatValue();
		} else if (values_object != null) {
			values_object = values_object.clone();
			values_object[start ? 0 : values_object.length - 1] = v;
		} else if (values_keyframe != null) {
			values_keyframe = values_keyframe.clone();
			int i = start ? 0 : values_keyframe.length - 1;
			Keyframe k = values_keyframe[i].clone();
			k.setValue(v);
			values_keyframe[i] = k;
		}
	}

	/** True when the first and last values differ (LayoutTransition skips unchanged layouts). */
	boolean tlStartDiffersFromEnd() {
		if (values_int != null)
			return values_int.length > 1 && values_int[0] != values_int[values_int.length - 1];
		if (values_float != null)
			return values_float.length > 1 && values_float[0] != values_float[values_float.length - 1];
		if (values_object != null)
			return values_object.length > 1 && !java.util.Objects.equals(values_object[0], values_object[values_object.length - 1]);
		if (values_keyframe != null)
			return values_keyframe.length > 1 && !java.util.Objects.equals(values_keyframe[0].getValue(), values_keyframe[values_keyframe.length - 1].getValue());
		return tlKeyframes != null;
	}

	@Override
	public String toString() {
		return property_name + ": " + (tlKeyframes != null ? tlKeyframes.toString() : String.valueOf(tlKeyframeArray() != null ? tlKeyframeArray().length + " keyframes" : "no values"));
	}
}
