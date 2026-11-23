package android.graphics;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A color filter can be used with a {@link Paint} to modify the color of each pixel drawn with
 * that paint.
 *
 * ATL: the paint takes the filter's state natively when the filter is set (Paint.setColorFilter);
 * AOSP's Skia filter object (ColorFilter.createNativeInstance, rebuilt on the next draw after the
 * filter changes: discardNativeInstance) has no counterpart, so a filter that changes (the hidden
 * PorterDuffColorFilter.setColor/setMode) gives its new state to the paints using it, which is
 * when AOSP's paints would see it.
 */
public class ColorFilter {
	/** the paints this filter is set on (weakly: a paint dropped by the app goes) */
	private final Map<Paint, Boolean> tlPaints = Collections.synchronizedMap(new WeakHashMap<Paint, Boolean>());

	void tlAttached(Paint paint) {
		tlPaints.put(paint, Boolean.TRUE);
	}

	void tlDetached(Paint paint) {
		tlPaints.remove(paint);
	}

	/** AOSP discardNativeInstance: the filter changed */
	void tlChanged() {
		Paint[] paints;
		synchronized (tlPaints) {
			paints = tlPaints.keySet().toArray(new Paint[0]);
		}
		for (Paint paint : paints) {
			if (paint.getColorFilter() == this)
				paint.setColorFilter(this);
		}
	}
}
