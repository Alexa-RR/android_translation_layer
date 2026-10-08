package android.view;

import android.content.Context;

public class PointerIcon {

	public static PointerIcon getSystemIcon(Context context, int resId) {
		return new PointerIcon();
	}

	private android.graphics.Bitmap mBitmap;
	private float mHotSpotX, mHotSpotY;

	/** A custom pointer icon from a bitmap and a hot spot inside it (AOSP validation). */
	public static PointerIcon create(android.graphics.Bitmap bitmap, float hotSpotX, float hotSpotY) {
		if (bitmap == null)
			throw new IllegalArgumentException("bitmap must not be null");
		if (hotSpotX < 0 || hotSpotX >= bitmap.getWidth())
			throw new IllegalArgumentException("x hotspot lies outside of the bitmap area");
		if (hotSpotY < 0 || hotSpotY >= bitmap.getHeight())
			throw new IllegalArgumentException("y hotspot lies outside of the bitmap area");
		PointerIcon icon = new PointerIcon();
		icon.mBitmap = bitmap;
		icon.mHotSpotX = hotSpotX;
		icon.mHotSpotY = hotSpotY;
		return icon;
	}
}
