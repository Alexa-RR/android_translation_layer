/*
 * teslalinux: the outline of a stroked path (Skia's SkStroke, which AOSP's Paint.getFillPath uses),
 * built on GskPath. GSK strokes paths itself when drawing but has no API that returns the stroke as a
 * path, so this builds it: the path is flattened to polylines (curves within `tolerance`), and every
 * segment, join and cap becomes a piece of the outline (segment quadrilaterals, miter/bevel wedges,
 * round joins and caps as circles, square caps as rectangles). Every piece winds the same way
 * (clockwise on screen), so filling the result with the nonzero rule covers exactly what the
 * stroke covers: Skia's result has the same contract (its outer and inner contours overlap at sharp
 * joins too) and its fill type is WINDING as well.
 *
 * Skia's rules kept here:
 *  - a miter join becomes a bevel when 1/sin(half the angle between the segments) exceeds the
 *    miter limit, or when the limit is <= 1 (SkStrokerPriv's MiterJoiner);
 *  - a contour that is only a moveTo draws nothing; a zero-length segment draws its caps (a dot
 *    for ROUND, a square for SQUARE, nothing for BUTT), squares aligned to the x axis;
 *  - closed contours have no caps, open ones a cap at each end.
 * Joins between the short lines a curve is flattened into turn by tiny angles; there every join
 * style differs from a bevel by less than the tolerance, so they are bevels (no circle per vertex).
 */
#include <math.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <gsk/gsk.h>
#include <jni.h>

#include "../defines.h"

typedef struct {
	float x, y;
} tl_pt;

typedef struct {
	tl_pt *p;
	int n, alloc;
	bool closed;
	bool drawn; /* a line, curve or close followed the moveTo */
} tl_contour;

typedef struct {
	tl_contour *c;
	int n, alloc;
	float tol; /* flattening tolerance of the centre line */
	float h;   /* half the stroke width: the offset lines need finer steps on tight curves */
} tl_contours;

static tl_contour *cur_contour(tl_contours *cs)
{
	return cs->n ? &cs->c[cs->n - 1] : NULL;
}

static void contour_new(tl_contours *cs, float x, float y)
{
	if (cs->n == cs->alloc) {
		cs->alloc = cs->alloc ? cs->alloc * 2 : 8;
		cs->c = realloc(cs->c, cs->alloc * sizeof(tl_contour));
	}
	tl_contour *c = &cs->c[cs->n++];
	memset(c, 0, sizeof(*c));
	c->alloc = 16;
	c->p = malloc(c->alloc * sizeof(tl_pt));
	c->p[0] = (tl_pt){ x, y };
	c->n = 1;
}

static void contour_add(tl_contour *c, float x, float y)
{
	const tl_pt last = c->p[c->n - 1];
	c->drawn = true;
	if (fabsf(last.x - x) < 1e-5f && fabsf(last.y - y) < 1e-5f)
		return;
	if (c->n == c->alloc) {
		c->alloc *= 2;
		c->p = realloc(c->p, c->alloc * sizeof(tl_pt));
	}
	c->p[c->n++] = (tl_pt){ x, y };
}

/* how many lines a curve with control points p[0..n-1] (n 3 or 4) becomes: Wang's bound for the
 * chord error of the centre line, and a turning angle per line small enough that the offset lines
 * at h stay within the tolerance too (h * a^2 / 8 <= tol) */
static int curve_steps(const graphene_point_t *p, int n, float tol, float h)
{
	float m = 0;
	for (int i = 0; i + 2 < n; i++)
		m = fmaxf(m, hypotf(p[i].x - 2 * p[i + 1].x + p[i + 2].x, p[i].y - 2 * p[i + 1].y + p[i + 2].y));
	const float degree = n - 1;
	float steps = sqrtf(degree * (degree - 1) / 8.f * m / tol);
	float turn = 0; /* total turning of the control polygon, at least the curve's */
	for (int i = 0; i + 2 < n; i++) {
		const float ax = p[i + 1].x - p[i].x, ay = p[i + 1].y - p[i].y;
		const float bx = p[i + 2].x - p[i + 1].x, by = p[i + 2].y - p[i + 1].y;
		if ((ax == 0 && ay == 0) || (bx == 0 && by == 0))
			continue;
		turn += fabsf(atan2f(ax * by - ay * bx, ax * bx + ay * by));
	}
	if (h > 0)
		steps = fmaxf(steps, turn / sqrtf(8.f * tol / h));
	return (int)fminf(fmaxf(ceilf(steps), 1.f), 1000.f);
}

static gboolean collect(GskPathOperation op, const graphene_point_t *pts, gsize n_pts, float weight, gpointer data)
{
	tl_contours *cs = data;
	tl_contour *c = cur_contour(cs);
	if (op == GSK_PATH_MOVE) {
		contour_new(cs, pts[0].x, pts[0].y);
		return TRUE;
	}
	if (op == GSK_PATH_CLOSE) {
		if (c) {
			c->drawn = true;
			c->closed = true;
			/* the closing line ends where the contour started: no duplicate point */
			if (c->n > 1 && fabsf(c->p[c->n - 1].x - c->p[0].x) < 1e-5f && fabsf(c->p[c->n - 1].y - c->p[0].y) < 1e-5f)
				c->n--;
		}
		return TRUE;
	}
	if (!c) {
		contour_new(cs, pts[0].x, pts[0].y);
		c = cur_contour(cs);
	}
	if (op == GSK_PATH_LINE) {
		contour_add(c, pts[1].x, pts[1].y);
		return TRUE;
	}
	/* curves: evaluated at even parameter steps */
	const int n = curve_steps(pts, op == GSK_PATH_CUBIC ? 4 : 3, cs->tol, cs->h);
	/* the bound is the polynomial's; a conic (weight <= 1 for the arcs paths build) gets twice
	 * the steps (its parameter runs unevenly along the curve) */
	const int steps = op == GSK_PATH_CONIC ? 2 * n : n;
	for (int i = 1; i <= steps; i++) {
		const float t = (float)i / steps, u = 1.f - t;
		float x, y;
		if (op == GSK_PATH_CUBIC) {
			const float a = u * u * u, b = 3 * u * u * t, d = 3 * u * t * t, e = t * t * t;
			x = a * pts[0].x + b * pts[1].x + d * pts[2].x + e * pts[3].x;
			y = a * pts[0].y + b * pts[1].y + d * pts[2].y + e * pts[3].y;
		} else {
			const float w = op == GSK_PATH_CONIC ? weight : 1.f;
			const float a = u * u, b = 2 * w * u * t, d = t * t, den = a + b + d;
			x = (a * pts[0].x + b * pts[1].x + d * pts[2].x) / den;
			y = (a * pts[0].y + b * pts[1].y + d * pts[2].y) / den;
		}
		contour_add(c, x, y);
	}
	return TRUE;
}

/* a closed polygon, turned clockwise on screen (positive shoelace area with y down) */
static void emit_poly(GskPathBuilder *b, const tl_pt *p, int n)
{
	double area = 0;
	for (int i = 0; i < n; i++) {
		const tl_pt a = p[i], c = p[(i + 1) % n];
		area += (double)a.x * c.y - (double)c.x * a.y;
	}
	if (fabs(area) < 1e-9)
		return;
	if (area > 0) {
		gsk_path_builder_move_to(b, p[0].x, p[0].y);
		for (int i = 1; i < n; i++)
			gsk_path_builder_line_to(b, p[i].x, p[i].y);
	} else {
		gsk_path_builder_move_to(b, p[n - 1].x, p[n - 1].y);
		for (int i = n - 2; i >= 0; i--)
			gsk_path_builder_line_to(b, p[i].x, p[i].y);
	}
	gsk_path_builder_close(b);
}

/* a circle, clockwise on screen: four exact conic quarters */
static void emit_circle(GskPathBuilder *b, tl_pt c, float r)
{
	const float w = (float)M_SQRT1_2;
	gsk_path_builder_move_to(b, c.x + r, c.y);
	gsk_path_builder_conic_to(b, c.x + r, c.y + r, c.x, c.y + r, w);
	gsk_path_builder_conic_to(b, c.x - r, c.y + r, c.x - r, c.y, w);
	gsk_path_builder_conic_to(b, c.x - r, c.y - r, c.x, c.y - r, w);
	gsk_path_builder_conic_to(b, c.x + r, c.y - r, c.x + r, c.y, w);
	gsk_path_builder_close(b);
}

static tl_pt dir_of(tl_pt a, tl_pt b)
{
	float dx = b.x - a.x, dy = b.y - a.y, len = sqrtf(dx * dx + dy * dy);
	return (tl_pt){ dx / len, dy / len };
}

static tl_pt off(tl_pt v, tl_pt d, float s)
{
	return (tl_pt){ v.x + d.x * s, v.y + d.y * s };
}

typedef struct {
	float h; /* half the stroke width */
	int cap, join;
	float miter;
	float tol;
} tl_stroke_params;

/* the join at v between a segment in direction d0 and the next in direction d1 */
static void emit_join(GskPathBuilder *b, const tl_stroke_params *sp, tl_pt v, tl_pt d0, tl_pt d1)
{
	const float dot = d0.x * d1.x + d0.y * d1.y;
	const float cross = d0.x * d1.y - d0.y * d1.x;
	if (dot >= 1.f - 1e-6f && fabsf(cross) < 1e-6f)
		return; /* straight on */
	const tl_pt n0 = { -d0.y, d0.x }, n1 = { -d1.y, d1.x };
	/* the outer side: a turn towards +n (cross > 0, clockwise on screen) opens a gap on -n */
	const float s = cross > 0 ? -sp->h : sp->h;
	/* a bevel differs from a round or miter join by about h * (1 - dot) / 4 */
	const bool tiny = sp->h * (1.f - dot) * 0.25f < sp->tol;
	if (sp->join == 1 /* ROUND */ && !tiny) {
		emit_circle(b, v, sp->h);
		return;
	}
	if (fabsf(cross) < 1e-6f)
		return; /* a reversal: a bevel there is a line, a miter would be infinite */
	tl_pt pts[4];
	pts[0] = v;
	pts[1] = off(v, n0, s);
	if (sp->join == 0 /* MITER */ && !tiny && sp->miter > 1.f && sqrtf((1.f + dot) * 0.5f) >= 1.f / sp->miter) {
		const tl_pt bis = { n0.x + n1.x, n0.y + n1.y };
		pts[2] = off(v, bis, s / (1.f + dot));
		pts[3] = off(v, n1, s);
		emit_poly(b, pts, 4);
	} else {
		pts[2] = off(v, n1, s);
		emit_poly(b, pts, 3);
	}
}

/* the cap at end point a of a segment pointing outwards along d */
static void emit_cap(GskPathBuilder *b, const tl_stroke_params *sp, tl_pt a, tl_pt d)
{
	if (sp->cap == 1 /* ROUND */) {
		emit_circle(b, a, sp->h);
	} else if (sp->cap == 2 /* SQUARE */) {
		const tl_pt n = { -d.y, d.x };
		const tl_pt e = off(a, d, sp->h);
		tl_pt pts[4] = { off(a, n, sp->h), off(e, n, sp->h), off(e, n, -sp->h), off(a, n, -sp->h) };
		emit_poly(b, pts, 4);
	}
}

static void stroke_contour(GskPathBuilder *b, const tl_stroke_params *sp, const tl_contour *c)
{
	const int n = c->n;
	if (n == 1) {
		/* zero length: Skia draws the caps of a segment along the x axis */
		if (c->drawn) {
			emit_cap(b, sp, c->p[0], (tl_pt){ 1, 0 });
			if (sp->cap == 2)
				emit_cap(b, sp, c->p[0], (tl_pt){ -1, 0 });
		}
		return;
	}
	const bool closed = c->closed;
	const int segs = closed ? n : n - 1;
	for (int i = 0; i < segs; i++) {
		const tl_pt a = c->p[i], e = c->p[(i + 1) % n];
		const tl_pt d = dir_of(a, e), nn = { -d.y, d.x };
		tl_pt q[4] = { off(a, nn, sp->h), off(e, nn, sp->h), off(e, nn, -sp->h), off(a, nn, -sp->h) };
		emit_poly(b, q, 4);
	}
	for (int i = closed ? 0 : 1; i < (closed ? n : n - 1); i++) {
		const tl_pt prev = c->p[(i - 1 + n) % n], v = c->p[i], next = c->p[(i + 1) % n];
		emit_join(b, sp, v, dir_of(prev, v), dir_of(v, next));
	}
	if (!closed) {
		emit_cap(b, sp, c->p[0], dir_of(c->p[1], c->p[0]));
		emit_cap(b, sp, c->p[n - 1], dir_of(c->p[n - 2], c->p[n - 1]));
	}
}

static double first_contour_area(const tl_contours *cs)
{
	for (int k = 0; k < cs->n; k++) {
		const tl_contour *c = &cs->c[k];
		if (c->n < 3)
			continue;
		double area = 0;
		for (int i = 0; i < c->n; i++) {
			const tl_pt a = c->p[i], e = c->p[(i + 1) % c->n];
			area += (double)a.x * e.y - (double)e.x * a.y;
		}
		if (area != 0)
			return area;
	}
	return 0;
}

/*
 * The stroke outline of `path` (width > 0; cap/join as Paint.Cap/Paint.Join ordinals, which are
 * GSK's line cap/join values; miter: Paint's miter limit). With add_src (FILL_AND_STROKE) the source
 * path is added too, reversed when its first contour winds the other way (SkStroke::strokePath), so
 * the nonzero fill covers both. res_scale as Skia's: the flattening tolerance shrinks with it.
 */
GskPath *tl_stroke_to_path(GskPath *path, float width, int cap, int join, float miter, gboolean add_src, float res_scale)
{
	tl_stroke_params sp = {
		.h = width / 2.f,
		.cap = cap,
		.join = join,
		.miter = miter,
		.tol = 0.1f / (res_scale > 0.f ? res_scale : 1.f),
	};
	tl_contours cs = { .tol = sp.tol, .h = sp.h };
	gsk_path_foreach(path, GSK_PATH_FOREACH_ALLOW_QUAD | GSK_PATH_FOREACH_ALLOW_CUBIC | GSK_PATH_FOREACH_ALLOW_CONIC, collect, &cs);
	GskPathBuilder *b = gsk_path_builder_new();
	for (int k = 0; k < cs.n; k++)
		stroke_contour(b, &sp, &cs.c[k]);
	if (add_src) {
		if (first_contour_area(&cs) < 0)
			gsk_path_builder_add_reverse_path(b, path);
		else
			gsk_path_builder_add_path(b, path);
	}
	for (int k = 0; k < cs.n; k++)
		free(cs.c[k].p);
	free(cs.c);
	return gsk_path_builder_free_to_path(b);
}

/* Paint.getFillPath's stroke: a new GskPath (the caller's Path takes the reference) */
JNIEXPORT jlong JNICALL Java_android_graphics_Paint_native_1stroke_1to_1path(JNIEnv *env, jclass clazz, jlong path_ptr, jfloat width,
		jint cap, jint join, jfloat miter, jboolean add_src, jfloat res_scale)
{
	return _INTPTR(tl_stroke_to_path(_PTR(path_ptr), width, cap, join, miter, add_src, res_scale));
}
