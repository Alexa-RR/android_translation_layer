#include <gsk/gsk.h>
#include <math.h>

#include "../defines.h"

#include "../generated_headers/android_graphics_Path.h"

JNIEXPORT jlong JNICALL Java_android_graphics_Path_native_1create_1builder(JNIEnv *env, jclass this, jlong path_ptr, jlong builder_ptr)
{
	GskPathBuilder *builder = _PTR(builder_ptr);
	if (!builder)
		builder = gsk_path_builder_new();
	if (path_ptr) {
		GskPath *path = _PTR(path_ptr);
		gsk_path_builder_add_path(builder, path);
		gsk_path_unref(path);
	}
	return _INTPTR(builder);
}

JNIEXPORT jlong JNICALL Java_android_graphics_Path_native_1create_1path(JNIEnv *env, jclass this, jlong builder_ptr)
{
	GskPathBuilder *builder = _PTR(builder_ptr);
	if (!builder) /* teslalinux-gfx: empty path without leaking a builder */
		return _INTPTR(gsk_path_builder_free_to_path(gsk_path_builder_new()));
	return _INTPTR(gsk_path_builder_to_path(builder));
}

JNIEXPORT jlong JNICALL Java_android_graphics_Path_native_1ref_1path(JNIEnv *env, jclass this, jlong path_ptr)
{
	return _INTPTR(gsk_path_ref(_PTR(path_ptr)));
}

JNIEXPORT void JNICALL Java_android_graphics_Path_native_1reset(JNIEnv *env, jclass this, jlong path_ptr, jlong builder_ptr)
{
	if (path_ptr)
		gsk_path_unref(_PTR(path_ptr));
	if (builder_ptr)
		gsk_path_builder_unref(_PTR(builder_ptr));
}

JNIEXPORT void JNICALL Java_android_graphics_Path_native_1close(JNIEnv *env, jclass this, jlong builder_ptr)
{
	gsk_path_builder_close(_PTR(builder_ptr));
}

JNIEXPORT void JNICALL Java_android_graphics_Path_native_1move_1to(JNIEnv *env, jclass this, jlong builder_ptr, jfloat x, jfloat y)
{
	gsk_path_builder_move_to(_PTR(builder_ptr), x, y);
}

JNIEXPORT void JNICALL Java_android_graphics_Path_native_1line_1to(JNIEnv *env, jclass this, jlong builder_ptr, jfloat x, jfloat y)
{
	gsk_path_builder_line_to(_PTR(builder_ptr), x, y);
}

/* translated to gsk_path_builder_svg_arc_to, maybe there are more performant ways to implement this? */
void Java_android_graphics_Path_native_1arc_1to(JNIEnv *env, jclass this, jlong builder_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom,
                                                jfloat start_angle_deg, jfloat sweep_angle_deg, jboolean force_move_to)
{
	GskPathBuilder *builder = _PTR(builder_ptr);

	/* compute ellipse center and radii */
	const graphene_point_t center = GRAPHENE_POINT_INIT((left + right) / 2.0,
	                                                    (top + bottom) / 2.0);
	const double rx = fabs(right - left) / 2.0;
	const double ry = fabs(bottom - top) / 2.0;

	/* compute points on the ellipse from angles */
	const double start_angle = DEG2RAD(start_angle_deg);
	const double end_angle = DEG2RAD(start_angle_deg + sweep_angle_deg);

	graphene_point_t p1;
	graphene_point_t p2;
	graphene_point_init(&p1,
	                    center.x + rx * cos(start_angle),
	                    center.y + ry * sin(start_angle));
	graphene_point_init(&p2,
	                    center.x + rx * cos(end_angle),
	                    center.y + ry * sin(end_angle));

	/* handle force_move_to */
	if (force_move_to)
		gsk_path_builder_move_to(builder, p1.x, p1.y);
	else if (!graphene_point_equal(gsk_path_builder_get_current_point(builder), &p1))
		gsk_path_builder_line_to(builder, p1.x, p1.y);

	/* compute flags */
	const gboolean large_arc_flag = (fabs(sweep_angle_deg) >= 180.0);
	const gboolean sweep_flag = (sweep_angle_deg > 0.0);

	gsk_path_builder_svg_arc_to(builder, rx, ry, 0.0, large_arc_flag, sweep_flag, p2.x, p2.y);
}

JNIEXPORT void JNICALL Java_android_graphics_Path_native_1cubic_1to(JNIEnv *env, jclass this, jlong builder_ptr, jfloat x1, jfloat y1, jfloat x2, jfloat y2, jfloat x3, jfloat y3)
{
	gsk_path_builder_cubic_to(_PTR(builder_ptr), x1, y1, x2, y2, x3, y3);
}

JNIEXPORT void JNICALL Java_android_graphics_Path_native_1quad_1to(JNIEnv *env, jclass this, jlong builder_ptr, jfloat x1, jfloat y1, jfloat x2, jfloat y2)
{
	gsk_path_builder_quad_to(_PTR(builder_ptr), x1, y1, x2, y2);
}

JNIEXPORT void JNICALL Java_android_graphics_Path_native_1rel_1move_1to(JNIEnv *env, jclass this, jlong builder_ptr, jfloat x, jfloat y)
{
	gsk_path_builder_rel_move_to(_PTR(builder_ptr), x, y);
}

JNIEXPORT void JNICALL Java_android_graphics_Path_native_1rel_1line_1to(JNIEnv *env, jclass this, jlong builder_ptr, jfloat x, jfloat y)
{
	gsk_path_builder_rel_line_to(_PTR(builder_ptr), x, y);
}

JNIEXPORT void JNICALL Java_android_graphics_Path_native_1rel_1cubic_1to(JNIEnv *env, jclass this, jlong builder_ptr, jfloat x1, jfloat y1, jfloat x2, jfloat y2, jfloat x3, jfloat y3)
{
	gsk_path_builder_rel_cubic_to(_PTR(builder_ptr), x1, y1, x2, y2, x3, y3);
}

JNIEXPORT void JNICALL Java_android_graphics_Path_native_1rel_1quad_1to(JNIEnv *env, jclass this, jlong builder_ptr, jfloat x1, jfloat y1, jfloat x2, jfloat y2)
{
	gsk_path_builder_rel_quad_to(_PTR(builder_ptr), x1, y1, x2, y2);
}

struct path_foreach_data {
	GskPathBuilder *builder;
	graphene_matrix_t *matrix;
	graphene_point_t tmp_pts[4];
};
static gboolean path_foreach_transform(GskPathOperation op, const graphene_point_t *pts, gsize n_pts, float weight, gpointer user_data)
{
	struct path_foreach_data *data = user_data;
	for (gsize i = 0; i < n_pts; i++) {
		graphene_matrix_transform_point(data->matrix, &pts[i], &data->tmp_pts[i]);
	}
	switch (op) {
		case GSK_PATH_MOVE:
			gsk_path_builder_move_to(data->builder, data->tmp_pts[0].x, data->tmp_pts[0].y);
			break;
		case GSK_PATH_CLOSE:
			gsk_path_builder_close(data->builder);
			break;
		case GSK_PATH_LINE:
			gsk_path_builder_line_to(data->builder, data->tmp_pts[1].x, data->tmp_pts[1].y);
			break;
		case GSK_PATH_QUAD:
			gsk_path_builder_quad_to(data->builder, data->tmp_pts[1].x, data->tmp_pts[1].y, data->tmp_pts[2].x, data->tmp_pts[2].y);
			break;
		case GSK_PATH_CUBIC:
			gsk_path_builder_cubic_to(data->builder, data->tmp_pts[1].x, data->tmp_pts[1].y, data->tmp_pts[2].x, data->tmp_pts[2].y, data->tmp_pts[3].x, data->tmp_pts[3].y);
			break;
		case GSK_PATH_CONIC:
			gsk_path_builder_conic_to(data->builder, data->tmp_pts[1].x, data->tmp_pts[1].y, data->tmp_pts[2].x, data->tmp_pts[2].y, weight);
			break;
	}
	return TRUE;
}

void Java_android_graphics_Path_native_1add_1arc(JNIEnv *env, jclass this, jlong builder_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom,
                                                 jfloat start_angle_deg, jfloat sweep_angle_deg)
{
	GskPathBuilder *builder = _PTR(builder_ptr);
	GskPath *path;
	GskPathBuilder *arc_builder = gsk_path_builder_new();
	Java_android_graphics_Path_native_1arc_1to(env, this, _INTPTR(arc_builder), left, top, right, bottom, start_angle_deg, sweep_angle_deg, true);
	path = gsk_path_builder_free_to_path(arc_builder);
	gsk_path_builder_add_path(builder, path);
}

JNIEXPORT void JNICALL Java_android_graphics_Path_native_1add_1path(JNIEnv *env, jclass this, jlong builder_ptr, jlong path_ptr, jlong matrix_ptr)
{
	GskPathBuilder *builder = _PTR(builder_ptr);
	GskPath *path = _PTR(path_ptr);
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	if (graphene_matrix_is_identity(matrix)) {
		gsk_path_builder_add_path(builder, path);
	} else {
		struct path_foreach_data data = {
			.builder = builder,
			.matrix = matrix,
		};
		gsk_path_foreach(path, GSK_PATH_FOREACH_ALLOW_QUAD | GSK_PATH_FOREACH_ALLOW_CUBIC | GSK_PATH_FOREACH_ALLOW_CONIC, path_foreach_transform, &data);
	}
}

JNIEXPORT jlong JNICALL Java_android_graphics_Path_native_1transform(JNIEnv *env, jclass this, jlong path_ptr, jlong matrix_ptr)
{
	GskPath *path = _PTR(path_ptr);
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	struct path_foreach_data data = {
		.builder = gsk_path_builder_new(),
		.matrix = matrix,
	};
	gsk_path_foreach(path, GSK_PATH_FOREACH_ALLOW_QUAD | GSK_PATH_FOREACH_ALLOW_CUBIC | GSK_PATH_FOREACH_ALLOW_CONIC, path_foreach_transform, &data);
	gsk_path_unref(path);
	return _INTPTR(data.builder);
}

JNIEXPORT void JNICALL Java_android_graphics_Path_native_1add_1rect(JNIEnv *env, jclass this, jlong builder_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom)
{
	gsk_path_builder_add_rect(_PTR(builder_ptr), &GRAPHENE_RECT_INIT(left, top, right - left, bottom - top));
}

JNIEXPORT void JNICALL Java_android_graphics_Path_native_1add_1round_1rect(JNIEnv *env, jclass this, jlong builder_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom, jfloatArray radii_jobj)
{
	jfloat *radii = (*env)->GetFloatArrayElements(env, radii_jobj, NULL);
	GskRoundedRect round_rect = {
		.bounds = GRAPHENE_RECT_INIT(left, top, right - left, bottom - top),
		.corner = {{radii[0], radii[1]}, {radii[2], radii[3]}, {radii[4], radii[5]}, {radii[6], radii[7]}},
	};
	(*env)->ReleaseFloatArrayElements(env, radii_jobj, radii, 0);
	gsk_path_builder_add_rounded_rect(_PTR(builder_ptr), &round_rect);
}

struct tl_bounds {
	gboolean any;
	float l, t, r, b;
};

static gboolean tl_bounds_cb(GskPathOperation op, const graphene_point_t *pts, gsize n_pts, float weight, gpointer data)
{
	struct tl_bounds *bb = data;
	/* every point of the operation (a conic's weight is not one of them) */
	for (gsize i = 0; i < n_pts; i++) {
		if (!bb->any) {
			bb->l = bb->r = pts[i].x;
			bb->t = bb->b = pts[i].y;
			bb->any = TRUE;
		} else {
			bb->l = MIN(bb->l, pts[i].x);
			bb->r = MAX(bb->r, pts[i].x);
			bb->t = MIN(bb->t, pts[i].y);
			bb->b = MAX(bb->b, pts[i].y);
		}
	}
	return TRUE;
}

/* AOSP computeBounds: the bounds of the path's points, control points included (Skia's
 * SkPath::getBounds); an empty path has empty bounds at 0. Computed from the operations: GSK's own
 * bounds of a contour with conics take the conic weight in as a point (a circle drawn with conics
 * away from the origin reached to (0, 0)). */
JNIEXPORT void JNICALL Java_android_graphics_Path_native_1get_1bounds(JNIEnv *env, jclass this, jlong path_ptr, jobject bounds)
{
	struct tl_bounds bb = { 0 };
	gsk_path_foreach(_PTR(path_ptr), GSK_PATH_FOREACH_ALLOW_QUAD | GSK_PATH_FOREACH_ALLOW_CUBIC | GSK_PATH_FOREACH_ALLOW_CONIC, tl_bounds_cb, &bb);
	_SET_FLOAT_FIELD(bounds, "left", bb.l);
	_SET_FLOAT_FIELD(bounds, "top", bb.t);
	_SET_FLOAT_FIELD(bounds, "right", bb.r);
	_SET_FLOAT_FIELD(bounds, "bottom", bb.b);
}

/* ---- teslalinux: Path.addCircle ---------------------------------------------------------------- */
JNIEXPORT void JNICALL Java_android_graphics_Path_native_1add_1circle(JNIEnv *env, jclass this, jlong builder_ptr, jfloat x, jfloat y, jfloat radius, jboolean clockwise)
{
	GskPathBuilder *builder = _PTR(builder_ptr);
	if (clockwise) {
		gsk_path_builder_add_circle(builder, &GRAPHENE_POINT_INIT(x, y), radius);
	} else {
		GskPathBuilder *circle = gsk_path_builder_new();
		gsk_path_builder_add_circle(circle, &GRAPHENE_POINT_INIT(x, y), radius);
		GskPath *path = gsk_path_builder_free_to_path(circle);
		gsk_path_builder_add_reverse_path(builder, path);
		gsk_path_unref(path);
	}
}

/* ---- teslalinux: Path.op ------------------------------------------------------------------------ */
GskPath *atl_path_op(GskPath *path1, GskPath *path2, int op);

/* returns a new GskPath (owned by the caller) with path1 <op> path2, or 0 */
JNIEXPORT jlong JNICALL Java_android_graphics_Path_native_1op(JNIEnv *env, jclass this, jlong path1_ptr, jlong path2_ptr, jint op)
{
	GskPath *path1 = _PTR(path1_ptr), *path2 = _PTR(path2_ptr);
	if (!path1 || !path2)
		return 0;
	return _INTPTR(atl_path_op(path1, path2, op));
}

/* AOSP Path.isEmpty: no lines or curves in the path */
JNIEXPORT jboolean JNICALL Java_android_graphics_Path_native_1is_1empty(JNIEnv *env, jclass this, jlong path_ptr)
{
	GskPath *path = _PTR(path_ptr);
	return path == NULL || gsk_path_is_empty(path);
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Path_native_1contains(JNIEnv *env, jclass class, jlong path_ptr, jint fill_rule, jboolean inverse, jfloat x, jfloat y)
{
	return gsk_path_in_fill(_PTR(path_ptr), &GRAPHENE_POINT_INIT(x, y), fill_rule) ^ inverse;
}

/*
 * AOSP Path.approximate (libs/hwui/jni/Path.cpp): the path as line segments within acceptableError
 * of it, as (fraction of the length, x, y) triples. Lines and closes add their end point (a zero
 * length line adds nothing), a move adds its point at the length so far (a jump), curves are
 * subdivided until the midpoint of each piece lies within the error of its chord; cubics are
 * checked once more at the quarter point, which catches an inflection between the ends.
 */
typedef struct {
	GArray *pts;  /* graphene_point_t */
	GArray *lens; /* float */
	float error_squared;
	float error_conic;
} Approx;

static void approx_move(Approx *a, graphene_point_t p)
{
	float len = a->lens->len ? g_array_index(a->lens, float, a->lens->len - 1) : 0;
	g_array_append_val(a->pts, p);
	g_array_append_val(a->lens, len);
}

static void approx_line(Approx *a, graphene_point_t p)
{
	if (!a->pts->len) {
		graphene_point_t zero = GRAPHENE_POINT_INIT(0, 0);
		float l0 = 0;
		g_array_append_val(a->pts, zero);
		g_array_append_val(a->lens, l0);
	} else {
		graphene_point_t last = g_array_index(a->pts, graphene_point_t, a->pts->len - 1);
		if (last.x == p.x && last.y == p.y)
			return; /* empty line */
	}
	graphene_point_t last = g_array_index(a->pts, graphene_point_t, a->pts->len - 1);
	float len = g_array_index(a->lens, float, a->lens->len - 1) + hypotf(p.x - last.x, p.y - last.y);
	g_array_append_val(a->pts, p);
	g_array_append_val(a->lens, len);
}

typedef graphene_point_t (*BezierFn)(float t, const graphene_point_t *pts, float weight);

static graphene_point_t quad_at(float t, const graphene_point_t *p, float weight)
{
	float mt = 1 - t;
	return GRAPHENE_POINT_INIT(mt * mt * p[0].x + 2 * mt * t * p[1].x + t * t * p[2].x,
	                           mt * mt * p[0].y + 2 * mt * t * p[1].y + t * t * p[2].y);
}

static graphene_point_t cubic_at(float t, const graphene_point_t *p, float weight)
{
	float mt = 1 - t, mt2 = mt * mt, mt3 = mt2 * mt, t2 = t * t, t3 = t2 * t;
	return GRAPHENE_POINT_INIT(mt3 * p[0].x + 3 * mt2 * t * p[1].x + 3 * mt * t2 * p[2].x + t3 * p[3].x,
	                           mt3 * p[0].y + 3 * mt2 * t * p[1].y + 3 * mt * t2 * p[2].y + t3 * p[3].y);
}

/* a conic is the rational quadratic Bezier with its middle point weighted */
static graphene_point_t conic_at(float t, const graphene_point_t *p, float w)
{
	float mt = 1 - t;
	float a = mt * mt, b = 2 * w * mt * t, c = t * t, d = a + b + c;
	return GRAPHENE_POINT_INIT((a * p[0].x + b * p[1].x + c * p[2].x) / d, (a * p[0].y + b * p[1].y + c * p[2].y) / d);
}

/* sets the midpoint of [t0, t1]; true if the chord is further than the error from it */
static gboolean approx_subdivide(const graphene_point_t *pts, float weight, BezierFn fn, float t0, graphene_point_t p0,
                                 float t1, graphene_point_t p1, float *mid_t, graphene_point_t *mid, float error_squared)
{
	*mid_t = (t1 + t0) / 2;
	float mx = (p1.x + p0.x) / 2, my = (p1.y + p0.y) / 2;
	*mid = fn(*mid_t, pts, weight);
	float ex = mid->x - mx, ey = mid->y - my;
	return ex * ex + ey * ey > error_squared;
}

typedef struct {
	float t;
	graphene_point_t p;
} TPoint;

static void approx_bezier(Approx *a, const graphene_point_t *pts, float weight, BezierFn fn, float error_squared, gboolean double_check)
{
	/* the sorted (t, point) list of AOSP's std::map */
	GArray *tp = g_array_new(FALSE, FALSE, sizeof(TPoint));
	TPoint first = { 0, fn(0, pts, weight) }, last = { 1, fn(1, pts, weight) };
	g_array_append_val(tp, first);
	g_array_append_val(tp, last);
	guint i = 0;
	while (i + 1 < tp->len) {
		gboolean subdivide;
		do {
			TPoint cur = g_array_index(tp, TPoint, i), next = g_array_index(tp, TPoint, i + 1);
			TPoint mid;
			subdivide = approx_subdivide(pts, weight, fn, cur.t, cur.p, next.t, next.p, &mid.t, &mid.p, error_squared);
			if (!subdivide && double_check) {
				TPoint quarter;
				subdivide = approx_subdivide(pts, weight, fn, cur.t, cur.p, mid.t, mid.p, &quarter.t, &quarter.p, error_squared);
				if (subdivide)
					double_check = FALSE; /* found an inflection point */
			}
			if (subdivide) {
				if (mid.t <= cur.t || mid.t >= next.t || tp->len > (1 << 16))
					break; /* float resolution reached */
				g_array_insert_val(tp, i + 1, mid);
			}
		} while (subdivide);
		i++;
	}
	for (i = 0; i < tp->len; i++)
		approx_line(a, g_array_index(tp, TPoint, i).p);
	g_array_free(tp, TRUE);
}

static gboolean approx_op(GskPathOperation op, const graphene_point_t *pts, gsize n_pts, float weight, gpointer data)
{
	Approx *a = data;
	switch (op) {
	case GSK_PATH_MOVE:
		approx_move(a, pts[0]);
		break;
	case GSK_PATH_CLOSE:
		approx_line(a, pts[1]);
		break;
	case GSK_PATH_LINE:
		approx_line(a, pts[1]);
		break;
	case GSK_PATH_QUAD:
		approx_bezier(a, pts, 0, quad_at, a->error_squared, FALSE);
		break;
	case GSK_PATH_CUBIC:
		approx_bezier(a, pts, 0, cubic_at, a->error_squared, TRUE);
		break;
	case GSK_PATH_CONIC:
		/* AOSP turns the conic into quads with half the error and subdivides those with that
		 * (unsquared) error; here the conic itself is subdivided with the same bound */
		approx_bezier(a, pts, weight, conic_at, a->error_conic, FALSE);
		break;
	}
	return TRUE;
}

JNIEXPORT jfloatArray JNICALL Java_android_graphics_Path_native_1approximate(JNIEnv *env, jclass class, jlong path_ptr, jfloat acceptable_error)
{
	Approx a = {
		.pts = g_array_new(FALSE, FALSE, sizeof(graphene_point_t)),
		.lens = g_array_new(FALSE, FALSE, sizeof(float)),
		.error_squared = acceptable_error * acceptable_error,
		.error_conic = acceptable_error / 2,
	};
	GskPath *path = _PTR(path_ptr);
	if (path)
		gsk_path_foreach(path, GSK_PATH_FOREACH_ALLOW_QUAD | GSK_PATH_FOREACH_ALLOW_CUBIC | GSK_PATH_FOREACH_ALLOW_CONIC, approx_op, &a);
	if (!a.pts->len) {
		/* an empty path falls back to the point (0, 0) (a lone move is kept by the foreach) */
		graphene_point_t zero = GRAPHENE_POINT_INIT(0, 0);
		approx_move(&a, zero);
	}
	float total = g_array_index(a.lens, float, a.lens->len - 1);
	if (total == 0) {
		/* lone moves still animate at the same value */
		graphene_point_t last = g_array_index(a.pts, graphene_point_t, a.pts->len - 1);
		float one = 1;
		g_array_append_val(a.pts, last);
		g_array_append_val(a.lens, one);
		total = 1;
	}
	jsize n = a.pts->len;
	float *out = g_malloc((gsize)n * 3 * sizeof(float));
	for (jsize i = 0; i < n; i++) {
		graphene_point_t p = g_array_index(a.pts, graphene_point_t, i);
		out[i * 3] = g_array_index(a.lens, float, i) / total;
		out[i * 3 + 1] = p.x;
		out[i * 3 + 2] = p.y;
	}
	jfloatArray result = (*env)->NewFloatArray(env, n * 3);
	if (result)
		(*env)->SetFloatArrayRegion(env, result, 0, n * 3, out);
	g_free(out);
	g_array_free(a.pts, TRUE);
	g_array_free(a.lens, TRUE);
	return result;
}
