package android.atl;

import android.graphics.*;
import android.view.DisplayListCanvas;
import android.view.RenderNode;
import java.util.Arrays;
import java.util.Stack;

/**
 * GskCanvas:
 *   - implements Canvas for onscreen rendering inside GTKs snapshot function
 */
public class GskCanvas extends DisplayListCanvas {
	public long snapshot;
	private int[] push_history = null;
	private Stack<Matrix> state_stack = new Stack<>();

	private static Paint default_paint = new Paint();

	public GskCanvas(long snapshot) {
		this.snapshot = snapshot;
		state_stack.push(new Matrix());
	}

	@Override
	public int save() {
		native_save(snapshot);
		state_stack.push(new Matrix(state_stack.peek()));
		tlClipPush();
		return getSaveCount() - 1;
	}

	@Override
	public void restore() {
		int save_count = getSaveCount();
		if (save_count <= 1)
			throw new IllegalStateException("No more saves to restore");
		if (push_history != null && push_history.length > save_count && push_history[save_count] > 0) {
			native_pop(snapshot, push_history[save_count]);
			push_history[save_count] = 0;
		}
		state_stack.pop();
		tlClipPop();
		native_restore(snapshot);
	}

	@Override
	public int getSaveCount() {
		return state_stack.size();
	}

	@Override
	public void translate(float dx, float dy) {
		native_translate(snapshot, dx, dy);
		state_stack.peek().preTranslate(dx, dy);
	}

	@Override
	public void rotate(float degrees) {
		native_rotate(snapshot, degrees);
		state_stack.peek().preRotate(degrees);
	}

	@Override
	public void scale(float sx, float sy) {
		native_scale(snapshot, sx, sy);
		state_stack.peek().preScale(sx, sy);
	}

	@Override
	public void concat(Matrix matrix) {
		if (matrix != null) {
			native_concat(snapshot, matrix.native_instance);
			state_stack.peek().preConcat(matrix);
		}
	}

	@Override
	public void getMatrix(Matrix matrix) {
		matrix.set(state_stack.peek());
	}

	@Override
	public boolean clipRect(float left, float top, float right, float bottom) {
		native_clipRect(snapshot, left, top, right, bottom);
		tlClipIntersect(left, top, right, bottom);
		int save_count = getSaveCount();
		if (push_history == null)
			push_history = new int[save_count + 1];
		else if (push_history.length <= save_count)
			push_history = Arrays.copyOf(push_history, save_count + 1);
		push_history[save_count]++;
		return right > left && bottom > top;
	}

	@Override
	public void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) {
		if (src == null)
			native_drawBitmap(snapshot, bitmap.getTexture(), dst.left, dst.top, dst.width(), dst.height(), paint != null ? paint.paint : default_paint.paint);
		else
			native_drawBitmap(snapshot, bitmap.getTexture(), dst.left, dst.top, dst.width(), dst.height(), src.left, src.top, src.width(), src.height(), paint != null ? paint.paint : default_paint.paint);
	}

	@Override
	public void drawPath(Path path, Paint paint) {
		if (path != null)
			native_drawPath(snapshot, path.getGskPath(), paint != null ? paint.paint : default_paint.paint);
	}

	@Override
	public void drawRect(float left, float top, float right, float bottom, Paint paint) {
		if (System.getenv("ATL_DEBUG_RED") != null && paint != null && paint.getColor() == 0xFFFF0000) {
			if (android.graphics.Canvas.tlRedTraces++ < 3)
				new Throwable("[red] drawRect " + left + "," + top + "-" + right + "," + bottom).printStackTrace(System.out);
		}
		// AOSP honours Paint.Style: STROKE outlines the rectangle (stroke centred on the edge, hairline
		// when the width is 0), FILL_AND_STROKE does both. The native call only fills.
		Paint.Style style = paint != null ? paint.getStyle() : Paint.Style.FILL;
		long p = paint != null ? paint.paint : default_paint.paint;
		if (style != Paint.Style.STROKE)
			native_drawRect(snapshot, left, top, right, bottom, p);
		if (style != Paint.Style.FILL) {
			float w = paint != null ? paint.getStrokeWidth() : 0;
			float h = w > 0 ? w / 2f : 0.5f;
			native_drawRect(snapshot, left - h, top - h, right + h, top + h, p);
			native_drawRect(snapshot, left - h, bottom - h, right + h, bottom + h, p);
			native_drawRect(snapshot, left - h, top + h, left + h, bottom - h, p);
			native_drawRect(snapshot, right - h, top + h, right + h, bottom - h, p);
		}
	}

	@Override
	public void drawText(String text, float x, float y, Paint paint) {
		if (text == null) {
			new Exception("drawText: text is null; stack trace:").printStackTrace();
			return;
		}
		native_drawText(snapshot, text, x, y, paint != null ? paint.paint : default_paint.paint);
	}

	@Override
	public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {
		native_drawLine(snapshot, startX, startY, stopX, stopY, paint != null ? paint.paint : default_paint.paint);
	}

	@Override
	public void drawLines(float[] points, int offset, int count, Paint paint) {
		if (offset + count < 0 /* overflow */ || offset + count > points.length)
			throw new ArrayIndexOutOfBoundsException();
		native_drawLines(snapshot, points, offset, count, paint != null ? paint.paint : default_paint.paint);
	}

	@Override
	public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry, Paint paint) {
		native_drawRoundRect(snapshot, left, top, right, bottom, rx, ry, paint != null ? paint.paint : default_paint.paint);
	}

	@Override
	public void drawCircle(float cx, float cy, float radius, Paint paint) {
		drawRoundRect(cx - radius, cy - radius, cx + radius, cy + radius, radius, radius, paint);
	}

	@Override
	public void drawOval(float left, float top, float right, float bottom, Paint paint) {
		drawRoundRect(left, top, right, bottom, (right - left) / 2, (bottom - top) / 2, paint);
	}

	@Override
	public void drawRenderNode(RenderNode node) {
		native_drawRenderNode(snapshot, node.getGskNode());
	}

	protected native void native_drawBitmap(long snapshot, long texture, int x, int y, int width, int height, long paint);
	protected native void native_drawBitmap(long snapshot, long texture, int x, int y, int width, int height, int src_x, int src_y, int src_width, int src_height, long paint);
	protected native void native_drawRect(long snapshot, float left, float top, float right, float bottom, long paint);
	protected native void native_drawPath(long snapshot, long path, long paint);
	protected native void native_translate(long snapshot, float dx, float dy);
	protected native void native_rotate(long snapshot, float degrees);
	protected native void native_save(long snapshot);
	protected native void native_restore(long snapshot);
	protected native void native_drawLine(long snapshot, float startX, float startY, float stopX, float stopY, long paint);
	protected native void native_drawLines(long snapshot, float[] points, int offset, int count, long paint);
	protected native void native_drawText(long snapshot, String text, float x, float y, long paint);
	protected native void native_drawRoundRect(long snapshot, float left, float top, float right, float bottom, float rx, float ry, long paint);
	protected native void native_scale(long snapshot, float sx, float sy);
	protected native void native_concat(long snapshot, long matrix);
	protected native void native_clipRect(long snapshot, float left, float top, float right, float bottom);
	protected native void native_pop(long snapshot, int pop_count);
	protected native void native_drawRenderNode(long snapshot, long render_node);

	/* ---- teslalinux: path clipping and clip bounds (AOSP Canvas semantics on GSK) ---- */

	/** Device-space clip bounds per save level (null entry: unclipped); kept next to state_stack. */
	private java.util.ArrayList<RectF> clip_stack;

	private void tlEnsureClipStack() {
		if (clip_stack == null) {
			clip_stack = new java.util.ArrayList<>();
			while (clip_stack.size() < getSaveCount())
				clip_stack.add(null);
		}
	}

	/** Called by save(): the new level starts with the clip of the level below. */
	void tlClipPush() {
		tlEnsureClipStack();
		RectF top = clip_stack.isEmpty() ? null : clip_stack.get(clip_stack.size() - 1);
		clip_stack.add(top == null ? null : new RectF(top));
	}

	/** Called by restore(): drop the level. */
	void tlClipPop() {
		if (clip_stack != null && !clip_stack.isEmpty())
			clip_stack.remove(clip_stack.size() - 1);
	}

	/** Bounding box in device space of a local-space rectangle under the current matrix. */
	private RectF tlToDevice(float left, float top, float right, float bottom) {
		Matrix m = state_stack.peek();
		if (m == null || m.isIdentity())
			return new RectF(left, top, right, bottom);
		float[] pts = { left, top, right, top, right, bottom, left, bottom };
		m.mapPoints(pts);
		float l = pts[0], t = pts[1], r = pts[0], b = pts[1];
		for (int i = 2; i < 8; i += 2) {
			l = Math.min(l, pts[i]);
			r = Math.max(r, pts[i]);
			t = Math.min(t, pts[i + 1]);
			b = Math.max(b, pts[i + 1]);
		}
		return new RectF(l, t, r, b);
	}

	/** Intersects the current level's clip with a local-space rectangle. */
	void tlClipIntersect(float left, float top, float right, float bottom) {
		tlEnsureClipStack();
		RectF dev = tlToDevice(left, top, right, bottom);
		int i = clip_stack.size() - 1;
		RectF cur = clip_stack.get(i);
		if (cur == null) {
			clip_stack.set(i, dev);
		} else if (!cur.intersect(dev)) {
			cur.setEmpty();
		}
	}

	private RectF tlCurrentClip() {
		tlEnsureClipStack();
		return clip_stack.get(clip_stack.size() - 1);
	}

	/** One more GSK container to pop at the current save level (clip, fill, mask). */
	private void tlNotePush() {
		int save_count = getSaveCount();
		if (push_history == null)
			push_history = new int[save_count + 1];
		else if (push_history.length <= save_count)
			push_history = Arrays.copyOf(push_history, save_count + 1);
		push_history[save_count]++;
	}

	private static int tlFillRule(Path path) {
		Path.FillType ft = path.getFillType();
		return (ft == Path.FillType.EVEN_ODD || ft == Path.FillType.INVERSE_EVEN_ODD) ? 1 : 0;
	}

	private static boolean tlIsInverse(Path path) {
		Path.FillType ft = path.getFillType();
		return ft == Path.FillType.INVERSE_WINDING || ft == Path.FillType.INVERSE_EVEN_ODD;
	}

	@Override
	public boolean clipPath(Path path) {
		return clipPath(path, Region.Op.INTERSECT);
	}

	/** AOSP (API 28+): only INTERSECT and DIFFERENCE are valid for a hardware-accelerated canvas. */
	@Override
	public boolean clipPath(Path path, Region.Op op) {
		if (op == Region.Op.INTERSECT) {
			if (tlIsInverse(path))
				return tlClipOut(path);
			native_clipPath(snapshot, path.getGskPath(), tlFillRule(path));
			tlNotePush();
			RectF bounds = new RectF();
			path.computeBounds(bounds, true);
			tlClipIntersect(bounds.left, bounds.top, bounds.right, bounds.bottom);
			RectF clip = tlCurrentClip();
			return clip == null || !clip.isEmpty();
		} else if (op == Region.Op.DIFFERENCE) {
			if (tlIsInverse(path)) {
				native_clipPath(snapshot, path.getGskPath(), tlFillRule(path));
				tlNotePush();
				return true;
			}
			return tlClipOut(path);
		}
		throw new IllegalArgumentException("Invalid Region.Op - only INTERSECT and DIFFERENCE are allowed");
	}

	private boolean tlClipOut(Path path) {
		native_clipOutPath(snapshot, path.getGskPath(), tlFillRule(path));
		tlNotePush();
		RectF clip = tlCurrentClip();
		return clip == null || !clip.isEmpty();
	}

	@Override
	public boolean clipOutPath(Path path) {
		return clipPath(path, Region.Op.DIFFERENCE);
	}

	@Override
	public boolean clipOutRect(float left, float top, float right, float bottom) {
		Path p = new Path();
		p.addRect(left, top, right, bottom, Path.Direction.CW);
		return tlClipOut(p);
	}

	@Override
	public boolean clipOutRect(int left, int top, int right, int bottom) {
		return clipOutRect((float) left, (float) top, (float) right, (float) bottom);
	}

	@Override
	public boolean clipOutRect(Rect rect) {
		return clipOutRect(rect.left, rect.top, rect.right, rect.bottom);
	}

	@Override
	public boolean clipOutRect(RectF rect) {
		return clipOutRect(rect.left, rect.top, rect.right, rect.bottom);
	}

	/** AOSP: the clip bounds in local coordinates; false if the clip is empty. */
	@Override
	public boolean getClipBounds(Rect outRect) {
		RectF clip = tlCurrentClip();
		if (clip == null) {
			// unclipped: the whole canvas as the framework reports it for an unclipped layer; a widget
			// snapshot canvas has no bitmap, its extent is the display it is drawn on
			if (outRect != null) {
				int w = getWidth(), h = getHeight();
				if (w <= 0 || h <= 0) {
					android.util.DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
					w = dm.widthPixels;
					h = dm.heightPixels;
				}
				outRect.set(0, 0, w, h);
			}
			return true;
		}
		if (clip.isEmpty()) {
			if (outRect != null)
				outRect.setEmpty();
			return false;
		}
		if (outRect != null) {
			Matrix inv = new Matrix();
			Matrix m = state_stack.peek();
			RectF local = new RectF(clip);
			if (m != null && !m.isIdentity() && m.invert(inv)) {
				float[] pts = { clip.left, clip.top, clip.right, clip.top, clip.right, clip.bottom, clip.left, clip.bottom };
				inv.mapPoints(pts);
				local.set(pts[0], pts[1], pts[0], pts[1]);
				for (int i = 2; i < 8; i += 2)
					local.union(pts[i], pts[i + 1]);
			}
			outRect.set(Math.round(local.left), Math.round(local.top), Math.round(local.right), Math.round(local.bottom));
		}
		return true;
	}

	/** AOSP quickReject: true when the rectangle (after the current matrix) lies completely outside the clip. */
	@Override
	public boolean quickReject(float left, float top, float right, float bottom) {
		RectF clip = tlCurrentClip();
		if (clip == null)
			return false;
		if (clip.isEmpty())
			return true;
		RectF dev = tlToDevice(left, top, right, bottom);
		return !RectF.intersects(clip, dev);
	}

	@Override
	public boolean quickReject(float left, float top, float right, float bottom, EdgeType type) {
		return quickReject(left, top, right, bottom);
	}

	@Override
	public boolean quickReject(RectF rect) {
		return quickReject(rect.left, rect.top, rect.right, rect.bottom);
	}

	@Override
	public boolean quickReject(RectF rect, EdgeType type) {
		return quickReject(rect);
	}

	@Override
	public boolean quickReject(Path path) {
		RectF bounds = new RectF();
		path.computeBounds(bounds, true);
		return quickReject(bounds);
	}

	@Override
	public boolean quickReject(Path path, EdgeType type) {
		return quickReject(path);
	}

	private static native void native_clipPath(long snapshot, long path, int fillRule);
	private static native void native_clipOutPath(long snapshot, long path, int fillRule);

	/* ---- teslalinux: arcs and points through GskPath (fill and stroke follow the Paint) ---- */

	/** AOSP drawArc: an arc of the oval, optionally closed through the centre as a wedge. */
	@Override
	public void drawArc(float left, float top, float right, float bottom, float startAngle, float sweepAngle, boolean useCenter, Paint paint) {
		Path p = new Path();
		if (useCenter) {
			p.moveTo((left + right) / 2f, (top + bottom) / 2f);
			p.arcTo(left, top, right, bottom, startAngle, sweepAngle, false);
			p.close();
		} else {
			p.arcTo(left, top, right, bottom, startAngle, sweepAngle, true);
		}
		drawPath(p, paint);
	}

	@Override
	public void drawArc(RectF oval, float startAngle, float sweepAngle, boolean useCenter, Paint paint) {
		if (oval == null)
			throw new NullPointerException();
		drawArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, useCenter, paint);
	}

	/** AOSP drawPoint: a dot of the stroke width, round or square after the stroke cap (hairline: one pixel). */
	@Override
	public void drawPoint(float x, float y, Paint paint) {
		Paint p = paint != null ? paint : default_paint;
		float w = p.getStrokeWidth();
		if (w <= 0)
			w = 1f;
		float h = w / 2f;
		Paint fill = new Paint(p);
		fill.setStyle(Paint.Style.FILL);
		if (p.getStrokeCap() == Paint.Cap.ROUND)
			drawRoundRect(x - h, y - h, x + h, y + h, h, h, fill);
		else
			drawRect(x - h, y - h, x + h, y + h, fill);
	}

	@Override
	public void drawPoints(float[] pts, int offset, int count, Paint paint) {
		for (int i = offset; i + 1 < offset + count; i += 2)
			drawPoint(pts[i], pts[i + 1], paint);
	}

	@Override
	public void drawPoints(float[] pts, Paint paint) {
		drawPoints(pts, 0, pts.length, paint);
	}

	/* ---- teslalinux: colour filter groups for drawables (PorterDuff tints as GSK colour matrices) ---- */
	private Paint tlFilterPaint;

	/** Pushes a colour-matrix group for the filter; returns false (nothing pushed) for filters GSK cannot express. */
	public boolean tlPushColorFilter(ColorFilter filter) {
		if (!(filter instanceof PorterDuffColorFilter))
			return false;
		if (tlFilterPaint == null)
			tlFilterPaint = new Paint();
		tlFilterPaint.setColorFilter(filter);
		native_pushColorMatrix(snapshot, tlFilterPaint.paint);
		return true;
	}

	/** Closes the group opened by tlPushColorFilter. */
	public void tlPopGroup() {
		native_pop(snapshot, 1);
	}

	private static native void native_pushColorMatrix(long snapshot, long paint);
}
