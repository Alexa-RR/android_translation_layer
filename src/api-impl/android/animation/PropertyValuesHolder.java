package android.animation;

import android.util.Log;
import android.util.Property;
import java.lang.reflect.Method;

public class PropertyValuesHolder {

	private float values_float[];
	private int values_int[];
	private Object values_object[];
	private Object value;
	private Keyframe values_keyframe[];

	private String property_name;
	private Method setter;
	Property property;
	private TypeEvaluator<Object> evaluator;

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
		propertyValuesHolder.value = values[0];
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
		propertyValuesHolder.value = values[0];
		return propertyValuesHolder;
	}

	public void setIntValues(int... values) {
		values_int = values;
	}

	public void setFloatValues(float... values) {
		values_float = values;
	}

	public void setObjectValues(Object... values) {
		values_object = values;
	}

	public void setKeyframes(Keyframe... values) {
		values_keyframe = values;
	}

	public String getProperty_name() {
		return property_name;
	}

	public void setProperty_name(String propertyName) {
		this.property_name = propertyName;
		property = null;
	}

	public void setProperty(Property property) {
		property_name = property.getName();
		this.property = property;
	}

	public void init() {}

	public Object getAnimatedValue() {
		return value;
	}

	public void setEvaluator(TypeEvaluator<Object> evaluator) {
		this.evaluator = evaluator;
	}

	public void calculateValue(float fraction) {
		if (fraction < 0f)
			fraction = 0f;
		if (fraction > 1f)
			fraction = 1f;
		if (values_object != null && evaluator != null) {
			int i = (int)(fraction * (values_object.length - 1));
			float f = fraction * (values_object.length - 1) - i;
			value = evaluator.evaluate(f, values_object[i], values_object[i >= values_object.length - 1 ? i : i + 1]);
		} else if (values_object != null) {
			value = values_object[(int)(fraction * (values_object.length - 1) + 0.5f)];
		} else if (values_float != null) {
			int i = (int)(fraction * (values_float.length - 1));
			float f = fraction * (values_float.length - 1) - i;
			value = values_float[i] * (1 - f) + ((f != 0.f) ? values_float[i + 1] * f : 0.f);
		} else if (values_int != null) {
			int i = (int)(fraction * (values_int.length - 1));
			float f = fraction * (values_int.length - 1) - i;
			value = (int)(values_int[i] * (1 - f) + ((f != 0.f) ? values_int[i + 1] * f : 0.f) + 0.5f);
		} else {
			Log.e("PropertyValuesHolder", "No values set");
		}
	}

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
		return propertyValuesHolder;
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
			Class<?> clazz;
			if (values_float != null) {
				clazz = float.class;
			} else if (values_int != null) {
				clazz = int.class;
			} else {
				clazz = values_object[0].getClass();
			}
			String capitalized = property_name.substring(0, 1).toUpperCase() + property_name.substring(1);
			setter = target.getClass().getMethod("set" + capitalized, clazz);
			setter.setAccessible(true); // AOSP: public setters of non-public classes (VectorDrawable groups)
			/* AOSP: the getter is only consulted when a single value was given (the start value comes from the target) */
			if ((values_int != null && values_int.length == 1) || (values_float != null && values_float.length == 1)
					|| (values_object != null && values_object.length == 1)) {
				Method getter = target.getClass().getMethod("get" + capitalized);
				getter.setAccessible(true);
				if (values_int != null) {
					values_int = new int[] {(Integer)getter.invoke(target), values_int[0]};
				} else if (values_float != null) {
					values_float = new float[] {(Float)getter.invoke(target), values_float[0]};
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
				setter.invoke(target, value);
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
		return false;
	}
}
