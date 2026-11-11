package android.graphics;

import android.atl.annotation.Export;
import android.os.Parcelable;
import android.util.DisplayMetrics;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.Buffer;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * Bitmap is implemented as GdkTexture or GtkSnapshot. It can only be one of the two at a time.
 * The methods getTexture() and getSnapshot() automatically convert between the two as needed.
 */
public final class Bitmap implements Parcelable {

	public enum Config {
		RGB_565(2, -1, /*ANDROID_BITMAP_FORMAT_RGB_565*/ 4),
		ARGB_8888(4, /*GDK_MEMORY_R8G8B8A8*/ 5, /**ANDROID_BITMAP_FORMAT_RGBA_8888*/ 1),
		ARGB_4444(2, -1, /*ANDROID_BITMAP_FORMAT_RGBA_4444*/ 7),
		ALPHA_8(1, /*GDK_MEMORY_A8*/ 24, /*ANDROID_BITMAP_FORMAT_A_8*/ 8),
		RGBA_F16(8, /*GDK_MEMORY_R16G16B16A16_FLOAT*/ 14, /*ANDROID_BITMAP_FORMAT_RGBA_F16*/ 9),
		HARDWARE(4, /*GDK_MEMORY_R8G8B8A8*/ 5, /*ANDROID_BITMAP_FORMAT_RGBA_8888*/ 1);

		private int bytes_per_pixel;
		private int gdk_memory_format;
		int android_memory_format; // used by native function AndroidBitmap_getInfo()

		private Config(int bytes_per_pixel, int gdk_memory_format, int android_memory_format) {
			this.bytes_per_pixel = bytes_per_pixel;
			this.gdk_memory_format = gdk_memory_format;
			this.android_memory_format = android_memory_format;
		}
	}

	public enum CompressFormat {
		JPEG,
		PNG,
		WEBP,
		WEBP_LOSSY,
		WEBP_LOSSLESS,
	}

	private int width;
	private int height;
	private int stride;
	private long texture;
	private long snapshot;
	private Config config = Config.ARGB_8888;
	private boolean hasAlpha = true;
	long bytes = 0; // used by native function AndroidBitmap_lockPixels()
	private boolean recycled = false;
	boolean mutable = true;
	private static final AtomicInteger nextGenerationId = new AtomicInteger(1);
	private int generationId = nextGenerationId.getAndIncrement();

	Bitmap(long texture) {
		this(native_get_width(texture), native_get_height(texture), Config.ARGB_8888);
		this.texture = texture;
	}

	private Bitmap(int width, int height, Config config) {
		this.config = config;
		this.width = width;
		this.height = height;
		int stride = width * config.bytes_per_pixel;
		this.stride = (stride + 3) & ~3; // 4-byte alignment
	}

	public static Bitmap createBitmap(int width, int height, Config config) {
		return new Bitmap(width, height, config);
	}

	public static Bitmap createBitmap(DisplayMetrics metrics, int width, int height, Config config) {
		return new Bitmap(width, height, config);
	}

	public static Bitmap createBitmap(DisplayMetrics metrics, int width, int height, Config config, boolean hasAlpha, ColorSpace colorSpace) {
		Bitmap bitmap = new Bitmap(width, height, config);
		bitmap.hasAlpha = hasAlpha;
		return bitmap;
	}

	public static Bitmap createBitmap(Bitmap src, int x, int y, int width, int height) {
		Bitmap dest = new Bitmap(width, height, src.getConfig());
		new Canvas(dest).drawBitmap(src, new Rect(x, y, x + width, y + height), new Rect(0, 0, width, height), null);
		return dest;
	}

	/** AOSP: the subset of src transformed by the matrix; the new bitmap is sized to the mapped bounds. */
	public static Bitmap createBitmap(Bitmap src, int x, int y, int width, int height, Matrix matrix, boolean filter) {
		if (x + width > src.getWidth())
			throw new IllegalArgumentException("x + width must be <= bitmap.width()");
		if (y + height > src.getHeight())
			throw new IllegalArgumentException("y + height must be <= bitmap.height()");
		if (width <= 0 || height <= 0)
			throw new IllegalArgumentException("width and height must be > 0");
		Rect srcR = new Rect(x, y, x + width, y + height);
		RectF dstR = new RectF(0, 0, width, height);
		if (matrix == null || matrix.isIdentity()) {
			Bitmap dest = new Bitmap(width, height, src.getConfig());
			dest.hasAlpha = src.hasAlpha;
			new Canvas(dest).drawBitmap(src, srcR, new Rect(0, 0, width, height), null);
			return dest;
		}
		RectF deviceR = new RectF();
		matrix.mapRect(deviceR, dstR);
		int neww = Math.round(deviceR.width()), newh = Math.round(deviceR.height());
		Bitmap dest = new Bitmap(Math.max(1, neww), Math.max(1, newh), src.getConfig());
		dest.hasAlpha = src.hasAlpha || !matrix.rectStaysRect();
		Canvas canvas = new Canvas(dest);
		canvas.translate(-deviceR.left, -deviceR.top);
		canvas.concat(matrix);
		Paint paint = new Paint();
		paint.setFilterBitmap(filter);
		canvas.drawBitmap(src, srcR, new Rect(0, 0, width, height), paint);
		dest.tlDensity = src.tlDensity;
		return dest;
	}

	/** AOSP: a bitmap with the given colours (unpremultiplied ARGB, rows of width). */
	public static Bitmap createBitmap(int[] colors, int width, int height, Config config) {
		return createBitmap(colors, 0, width, width, height, config);
	}

	public static Bitmap createBitmap(int[] colors, int offset, int stride, int width, int height, Config config) {
		if (width <= 0 || height <= 0)
			throw new IllegalArgumentException("width and height must be > 0");
		if (Math.abs(stride) < width)
			throw new IllegalArgumentException("abs(stride) must be >= width");
		int lastScanline = offset + (height - 1) * stride;
		if (offset < 0 || (offset + width > colors.length) || lastScanline < 0 || (lastScanline + width > colors.length))
			throw new ArrayIndexOutOfBoundsException();
		Bitmap bitmap = createBitmap(width, height, config);
		bitmap.setPixels(colors, offset, stride, 0, 0, width, height);
		bitmap.mutable = false;
		return bitmap;
	}

	public static Bitmap createBitmap(DisplayMetrics display, int[] colors, int offset, int stride, int width, int height, Config config) {
		Bitmap bitmap = createBitmap(colors, offset, stride, width, height, config);
		if (display != null)
			bitmap.tlDensity = display.densityDpi;
		return bitmap;
	}

	public static Bitmap createBitmap(DisplayMetrics display, int[] colors, int width, int height, Config config) {
		return createBitmap(display, colors, 0, width, width, height, config);
	}

	public static Bitmap createBitmap(Bitmap src) {
		return new Bitmap(native_ref_texture(src.getTexture()));
	}

	public static Bitmap createScaledBitmap(Bitmap src, int dstWidth, int dstHeight, boolean filter) {
		Bitmap dest = new Bitmap(dstWidth, dstHeight, src.getConfig());
		new Canvas(dest).drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()), new Rect(0, 0, dstWidth, dstHeight), null);
		return dest;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public Config getConfig() {
		return config;
	}

	@Export
	public synchronized long getTexture() {
		if (texture == 0) {
			texture = native_create_texture(snapshot, width, height, stride, config.gdk_memory_format);
			snapshot = 0;
		}
		return texture;
	}

	synchronized long getSnapshot() {
		notifyPixelsChanged();
		if (snapshot == 0) {
			snapshot = native_create_snapshot(texture);
			texture = 0;
		}
		return snapshot;
	}

	public void eraseColor(int color) {
		if (color == Color.TRANSPARENT) {
			native_recycle(texture, snapshot);
			snapshot = native_erase_color(color, width, height);
			texture = 0;
		} else {
			// AOSP: every pixel becomes the colour (not drawn over the old content)
			native_recycle(texture, snapshot);
			snapshot = native_erase_color(0, width, height);
			texture = 0;
			Paint paint = new Paint();
			paint.setColor(color);
			new Canvas(this).drawRect(0, 0, width, height, paint);
		}
		notifyPixelsChanged();
	}

	@Export
	public void recycle() {
		native_recycle(texture, snapshot);
		texture = 0;
		snapshot = 0;
		recycled = true;
		notifyPixelsChanged();
	}

	public int getRowBytes() {
		return stride;
	}

	// used by BitmapFactory to satify Options.inBitmap if set
	void transferFrom(Bitmap src) {
		native_recycle(this.texture, this.snapshot);
		this.texture = src.texture;
		this.snapshot = src.snapshot;
		this.width = src.width;
		this.height = src.height;
		this.stride = src.stride;
		this.config = src.config;
		this.hasAlpha = src.hasAlpha;
		this.mutable = src.mutable;
		this.recycled = false;
		src.texture = 0;
		src.snapshot = 0;
		src.recycled = true;
		notifyPixelsChanged();
	}

	public int getGenerationId() {
		return generationId;
	}

	private void notifyPixelsChanged() {
		generationId = nextGenerationId.getAndIncrement();
	}

	public int getAllocationByteCount() {
		return height * getRowBytes();
	}

	public void prepareToDraw() {
		getTexture();
	}

	/** AOSP: the density the bitmap is meant for (Canvas and getScaledWidth/Height read it). */
	public void setDensity(int density) {
		tlDensity = density;
	}

	/** AOSP: the width scaled from the bitmap's density to the target density. */
	public int getScaledWidth(int targetDensity) {
		return tlScale(width, getDensity(), targetDensity);
	}

	public int getScaledHeight(int targetDensity) {
		return tlScale(height, getDensity(), targetDensity);
	}

	public int getScaledWidth(DisplayMetrics metrics) {
		return getScaledWidth(metrics.densityDpi);
	}

	public int getScaledHeight(DisplayMetrics metrics) {
		return getScaledHeight(metrics.densityDpi);
	}

	public int getScaledWidth(Canvas canvas) {
		return getScaledWidth(canvas.getDensity());
	}

	public int getScaledHeight(Canvas canvas) {
		return getScaledHeight(canvas.getDensity());
	}

	/** AOSP scaleFromDensity: no scaling when either density is DENSITY_NONE or they are equal. */
	private static int tlScale(int size, int sdensity, int tdensity) {
		if (sdensity == DENSITY_NONE || tdensity == DENSITY_NONE || sdensity == tdensity)
			return size;
		return ((size * tdensity) + (sdensity >> 1)) / sdensity;
	}

	public boolean isRecycled() {
		return recycled;
	}

	public void setHasAlpha(boolean hasAlpha) {
		this.hasAlpha = hasAlpha;
	}

	public boolean hasAlpha() {
		return hasAlpha;
	}

	/** AOSP: a copy with the config and mutability asked for (the pixels are shared until either is drawn to). */
	public Bitmap copy(Bitmap.Config config, boolean isMutable) {
		if (recycled)
			throw new IllegalStateException("Can't copy a recycled bitmap");
		Bitmap bitmap = new Bitmap(width, height, config);
		bitmap.texture = native_ref_texture(getTexture());
		bitmap.mutable = isMutable;
		bitmap.hasAlpha = hasAlpha;
		bitmap.tlDensity = tlDensity;
		bitmap.tlPremultiplied = tlPremultiplied;
		return bitmap;
	}

	public void getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
		native_get_pixels(getTexture(), pixels, offset, stride, x, y, width, height);
	}

	/**
	 * AOSP: copies the pixels in the bitmap's own memory layout into the buffer (premultiplied
	 * unless setPremultiplied(false)), and advances its position.
	 */
	public void copyPixelsToBuffer(Buffer buffer) {
		int needed = getAllocationByteCount();
		int elements = buffer.remaining();
		int shift = buffer instanceof java.nio.ByteBuffer ? 0 : buffer instanceof java.nio.ShortBuffer ? 1 : buffer instanceof java.nio.IntBuffer ? 2 : -1;
		if (shift < 0)
			throw new RuntimeException("unsupported Buffer subclass");
		if (((long) elements << shift) < needed)
			throw new RuntimeException("Buffer not large enough for pixels");
		if (config == Config.RGB_565 || config == Config.ARGB_4444) {
			byte[] out = tlPack16();
			tlPut(buffer, out);
			return;
		}
		int format = config.gdk_memory_format;
		if (format == 5 && tlPremultiplied && hasAlpha)
			format = 2; // GDK_MEMORY_R8G8B8A8_PREMULTIPLIED
		native_copy_to_buffer(getTexture(), buffer, format, getRowBytes());
		buffer.position(buffer.position() + (needed >> shift));
	}

	public int getByteCount() {
		return getAllocationByteCount();
	}

	public boolean isMutable() {
		return mutable;
	}

	/**
	 * AOSP: writes a compressed version of the bitmap. PNG through GDK, JPEG and WebP through
	 * gdk-pixbuf (JPEG drops alpha as Skia's encoder does: premultiplied colour over black); false
	 * when the encoder is not available.
	 */
	public boolean compress(Bitmap.CompressFormat format, int quality, OutputStream stream) throws IOException {
		if (recycled)
			throw new IllegalStateException("Can't compress a recycled bitmap");
		if (stream == null)
			throw new NullPointerException();
		if (quality < 0 || quality > 100)
			throw new IllegalArgumentException("quality must be 0..100");
		byte[] data;
		if (format == CompressFormat.PNG) {
			data = native_save_to_png(getTexture());
		} else {
			String name = format == CompressFormat.JPEG ? "jpeg" : "webp";
			boolean lossless = format == CompressFormat.WEBP_LOSSLESS;
			data = native_compress(getTexture(), name, quality, lossless);
		}
		if (data == null)
			return false;
		stream.write(data);
		stream.flush();
		return true;
	}

	public void setPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
		native_set_pixels(getSnapshot(), pixels, offset, stride, x, y, width, height);
	}

	/**
	 * AOSP: changes the bitmap's size and config within its allocation (the pixel content is
	 * undefined afterwards; here it is cleared to transparent).
	 */
	public void reconfigure(int width, int height, Bitmap.Config config) {
		if (width <= 0 || height <= 0)
			throw new IllegalArgumentException("width and height must be > 0");
		if (!isMutable())
			throw new IllegalStateException("only mutables can be reconfigured");
		int stride = (width * config.bytes_per_pixel + 3) & ~3;
		if (tlAllocation == 0)
			tlAllocation = getAllocationByteCount();
		if ((long) stride * height > tlAllocation)
			throw new IllegalArgumentException("Bitmap not large enough to support new configuration");
		native_recycle(texture, snapshot);
		texture = 0;
		snapshot = 0;
		this.width = width;
		this.height = height;
		this.config = config;
		this.stride = stride;
		eraseColor(0);
	}

	/** AOSP: whether raw pixel access (copyPixelsToBuffer) sees premultiplied colours. */
	public void setPremultiplied(boolean premultiplied) {
		tlPremultiplied = premultiplied;
	}

	public boolean isPremultiplied() {
		return tlPremultiplied && hasAlpha && config != Config.RGB_565;
	}

	public Bitmap extractAlpha() {
		return this.copy(config, mutable);
	}

	@SuppressWarnings("deprecation")
	@Override
	protected void finalize() throws Throwable {
		try {
			recycle();
		} finally {
			super.finalize();
		}
	}

	private static native long native_create_snapshot(long texture);
	private static native long native_create_texture(long snapshot, int width, int height, int stride, int format);
	private static native int native_get_width(long texture);
	private static native int native_get_height(long texture);
	private static native long native_erase_color(int color, int width, int height);
	private static native void native_recycle(long texture, long snapshot);
	private static native long native_ref_texture(long texture);
	private static native void native_get_pixels(long texture, int[] pixels, int offset, int stride, int x, int y, int width, int height);
	private static native void native_copy_to_buffer(long texture, Buffer buffer, int memory_format, int stride);
	private static native byte[] native_save_to_png(long texture);
	private static native void native_set_pixels(long snapshot, int[] pixels, int offset, int stride, int x, int y, int width, int height);


	/* teslalinux: Bitmap additions */
	private boolean tlMipMap;
	public int getPixel(int x, int y) { int[] p = new int[1]; getPixels(p, 0, 1, x, y, 1, 1); return p[0]; }
	public void setPixel(int x, int y, int color) { setPixels(new int[] { color }, 0, 1, x, y, 1, 1); }
	public boolean hasMipMap() { return tlMipMap; }
	public void setHasMipMap(boolean hasMipMap) { tlMipMap = hasMipMap; }
	public Bitmap asShared() { return this; }

	/** API 28: a bitmap with the contents of a Picture at its recorded size. */
	public static Bitmap createBitmap(Picture source) {
		return createBitmap(source, source.getWidth(), source.getHeight(), Config.ARGB_8888);
	}

	public static Bitmap createBitmap(Picture source, int width, int height, Config config) {
		if (width <= 0 || height <= 0)
			throw new IllegalArgumentException("width & height must be > 0");
		if (config == null)
			throw new IllegalArgumentException("Config must not be null");
		Bitmap bitmap = createBitmap(width, height, config);
		Canvas canvas = new Canvas(bitmap);
		if (source.getWidth() > 0 && source.getHeight() > 0)
			canvas.scale(width / (float) source.getWidth(), height / (float) source.getHeight());
		source.draw(canvas);
		return bitmap;
	}

	/** API 26: with an explicit alpha flag and colour space (sRGB is the only space the pixels are kept in). */
	public static Bitmap createBitmap(int width, int height, Config config, boolean hasAlpha, ColorSpace colorSpace) {
		return createBitmap(width, height, config, hasAlpha);
	}

	public static Bitmap createBitmap(int width, int height, Config config, boolean hasAlpha) {
		Bitmap b = createBitmap(width, height, config);
		if (!hasAlpha)
			b.eraseColor(0xff000000);
		return b;
	}
	/**
	 * AOSP: indicates that the bitmap was created for an unknown pixel density; no density scaling is applied.
	 */
	public static final int DENSITY_NONE = 0;

	/* ---- teslalinux (gfx) ---- */

	/**
	 * The bitmap's content becomes what snapshot s records (GskCanvas composes a transfer-mode draw
	 * with the bitmap's previous content into a fresh snapshot). The snapshot is owned by the bitmap.
	 */
	public synchronized void tlSetSnapshot(long s) {
		native_recycle(texture, snapshot);
		texture = 0;
		snapshot = s;
	}

	/** AOSP: the density the bitmap was made for (the display density by default). */
	private int tlDensity = -1;
	private boolean tlPremultiplied = true;
	private int tlAllocation;
	private ColorSpace tlColorSpace;
	private Gainmap tlGainmap;

	public int getDensity() {
		if (tlDensity < 0)
			tlDensity = android.content.res.Resources.getSystem().getDisplayMetrics().densityDpi;
		return tlDensity;
	}

	/** AOSP: same size, config and pixels. */
	public boolean sameAs(Bitmap other) {
		if (other == this)
			return true;
		if (other == null || other.width != width || other.height != height || other.config != config)
			return false;
		int[] a = new int[width * height], b = new int[width * height];
		getPixels(a, 0, width, 0, 0, width, height);
		other.getPixels(b, 0, width, 0, 0, width, height);
		return java.util.Arrays.equals(a, b);
	}

	/**
	 * AOSP: the colour space of the pixels: sRGB for the 8-bit configs, extended linear sRGB for
	 * RGBA_F16, none for ALPHA_8, unless one was set with setColorSpace.
	 */
	public ColorSpace getColorSpace() {
		if (config == Config.ALPHA_8)
			return null;
		if (tlColorSpace != null)
			return tlColorSpace;
		return ColorSpace.get(config == Config.RGBA_F16 ? ColorSpace.Named.LINEAR_EXTENDED_SRGB : ColorSpace.Named.SRGB);
	}

	/**
	 * AOSP: changes how the pixels are interpreted (no conversion). AOSP accepts RGB spaces with an
	 * ICC parametric curve only; the GSK texture keeps its sRGB interpretation when drawn.
	 */
	public void setColorSpace(ColorSpace colorSpace) {
		if (colorSpace == null)
			throw new IllegalArgumentException("The colorSpace cannot be set to null");
		if (config == Config.ALPHA_8)
			throw new IllegalArgumentException("Cannot set a ColorSpace on ALPHA_8");
		if (colorSpace.getModel() != ColorSpace.Model.RGB)
			throw new IllegalArgumentException("Unsupported color model: " + colorSpace.getModel());
		tlColorSpace = colorSpace;
	}

	/** AOSP API 34: an HDR gain map attached to the bitmap (none unless set). */
	public boolean hasGainmap() {
		return tlGainmap != null;
	}

	public Gainmap getGainmap() {
		return tlGainmap;
	}

	/**
	 * AOSP API 34. The gain map is kept with the bitmap; as on Android with an SDR display, the base
	 * image is what is drawn.
	 */
	public void setGainmap(Gainmap gainmap) {
		tlGainmap = gainmap;
	}

	private static native byte[] native_compress(long texture, String format, int quality, boolean lossless);

	/** RGB_565 / ARGB_4444 pixels as little-endian 16-bit words (premultiplied 4444, as Skia stores it). */
	private byte[] tlPack16() {
		int[] px = new int[width * height];
		getPixels(px, 0, width, 0, 0, width, height);
		byte[] out = new byte[stride * height];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int c = px[y * width + x];
				int a = c >>> 24, r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
				int v;
				if (config == Config.RGB_565) {
					v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
				} else {
					if (tlPremultiplied) {
						r = r * a / 255;
						g = g * a / 255;
						b = b * a / 255;
					}
					v = ((r >> 4) << 12) | ((g >> 4) << 8) | ((b >> 4) << 4) | (a >> 4);
				}
				int o = y * stride + x * 2;
				out[o] = (byte) v;
				out[o + 1] = (byte) (v >> 8);
			}
		}
		return out;
	}

	private static void tlPut(Buffer buffer, byte[] data) {
		if (buffer instanceof java.nio.ByteBuffer) {
			((java.nio.ByteBuffer) buffer).put(data);
		} else {
			java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.nativeOrder());
			if (buffer instanceof java.nio.ShortBuffer)
				((java.nio.ShortBuffer) buffer).put(bb.asShortBuffer());
			else
				((java.nio.IntBuffer) buffer).put(bb.asIntBuffer());
		}
	}

	/** AOSP: replaces the pixels from the buffer, in the bitmap's memory layout; advances its position. */
	public void copyPixelsFromBuffer(Buffer src) {
		if (!isMutable())
			throw new IllegalStateException("Bitmap is immutable");
		int shift = src instanceof java.nio.ByteBuffer ? 0 : src instanceof java.nio.ShortBuffer ? 1 : src instanceof java.nio.IntBuffer ? 2 : -1;
		if (shift < 0)
			throw new RuntimeException("unsupported Buffer subclass");
		int needed = getAllocationByteCount();
		if (((long) src.remaining() << shift) < needed)
			throw new RuntimeException("Buffer not large enough for pixels");
		byte[] raw = new byte[needed];
		if (src instanceof java.nio.ByteBuffer) {
			((java.nio.ByteBuffer) src).get(raw);
		} else {
			java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.nativeOrder());
			if (src instanceof java.nio.ShortBuffer) {
				java.nio.ShortBuffer s = (java.nio.ShortBuffer) src;
				short[] tmp = new short[needed >> 1];
				s.get(tmp);
				bb.asShortBuffer().put(tmp);
			} else {
				java.nio.IntBuffer s = (java.nio.IntBuffer) src;
				int[] tmp = new int[needed >> 2];
				s.get(tmp);
				bb.asIntBuffer().put(tmp);
			}
		}
		int[] px = new int[width * height];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int c;
				if (config == Config.RGB_565 || config == Config.ARGB_4444) {
					int o = y * stride + x * 2;
					int v = (raw[o] & 0xff) | ((raw[o + 1] & 0xff) << 8);
					if (config == Config.RGB_565) {
						int r = (v >> 11) & 0x1f, g = (v >> 5) & 0x3f, b = v & 0x1f;
						c = 0xff000000 | ((r << 3 | r >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
					} else {
						int r = (v >> 12) & 0xf, g = (v >> 8) & 0xf, b = (v >> 4) & 0xf, a = v & 0xf;
						c = Color.argb(a * 17, r * 17, g * 17, b * 17);
						c = tlUnpremul(c);
					}
				} else if (config == Config.ALPHA_8) {
					c = (raw[y * stride + x] & 0xff) << 24;
				} else if (config == Config.RGBA_F16) {
					int o = y * stride + x * 8;
					java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw, o, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
					float r = android.util.Half.toFloat(bb.getShort()), g = android.util.Half.toFloat(bb.getShort());
					float b = android.util.Half.toFloat(bb.getShort()), a = android.util.Half.toFloat(bb.getShort());
					c = Color.argb(tlClamp(a), tlClamp(a > 0 && tlPremultiplied ? r / a : r), tlClamp(a > 0 && tlPremultiplied ? g / a : g), tlClamp(a > 0 && tlPremultiplied ? b / a : b));
				} else {
					int o = y * stride + x * 4;
					c = Color.argb(raw[o + 3] & 0xff, raw[o] & 0xff, raw[o + 1] & 0xff, raw[o + 2] & 0xff);
					if (tlPremultiplied && hasAlpha)
						c = tlUnpremul(c);
				}
				px[y * width + x] = c;
			}
		}
		native_recycle(texture, snapshot);
		texture = 0;
		snapshot = native_erase_color(0, width, height);
		setPixels(px, 0, width, 0, 0, width, height);
	}

	private static int tlClamp(float v) {
		return Math.max(0, Math.min(255, Math.round(v * 255f)));
	}

	private static int tlUnpremul(int c) {
		int a = c >>> 24;
		if (a == 0 || a == 255)
			return a == 0 ? 0 : c;
		int r = Math.min(255, ((c >> 16) & 0xff) * 255 / a), g = Math.min(255, ((c >> 8) & 0xff) * 255 / a), b = Math.min(255, (c & 0xff) * 255 / a);
		return Color.argb(a, r, g, b);
	}

	/** AOSP (hidden): a size scaled from one density to another, rounded (no scaling with DENSITY_NONE). */
	public static int scaleFromDensity(int size, int sdensity, int tdensity) {
		return tlScale(size, sdensity, tdensity);
	}

	/* ---- Parcelable ----
	 * AOSP writes the bitmap's mutability, config, size, density and pixels (in a shared memory
	 * blob); here the pixels go into the parcel as ARGB colours, which both sides of a binder call
	 * in this runtime read back the same way. */

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(android.os.Parcel p, int flags) {
		if (isRecycled())
			throw new IllegalStateException("Can't parcel a recycled bitmap");
		int w = getWidth(), h = getHeight();
		p.writeInt(isMutable() ? 1 : 0);
		p.writeString(getConfig() != null ? getConfig().name() : Config.ARGB_8888.name());
		p.writeInt(w);
		p.writeInt(h);
		p.writeInt(getDensity());
		p.writeInt(hasAlpha() ? 1 : 0);
		int[] pixels = new int[w * h];
		getPixels(pixels, 0, w, 0, 0, w, h);
		p.writeIntArray(pixels);
	}

	public static final Parcelable.Creator<Bitmap> CREATOR = new Parcelable.Creator<Bitmap>() {
		/** Rebuilds a Bitmap from a parcel written by writeToParcel. */
		@Override
		public Bitmap createFromParcel(android.os.Parcel p) {
			boolean mutable = p.readInt() != 0;
			Config config;
			try {
				config = Config.valueOf(p.readString());
			} catch (IllegalArgumentException | NullPointerException e) {
				config = Config.ARGB_8888;
			}
			int w = p.readInt();
			int h = p.readInt();
			int density = p.readInt();
			boolean alpha = p.readInt() != 0;
			int[] pixels = p.createIntArray();
			Bitmap b = createBitmap(pixels, w, h, config);
			b.setDensity(density);
			b.setHasAlpha(alpha);
			return mutable ? b : b.copy(config, false);
		}

		@Override
		public Bitmap[] newArray(int size) {
			return new Bitmap[size];
		}
	};
}
