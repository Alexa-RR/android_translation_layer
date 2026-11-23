#include <graphene.h>
#include <math.h>
#include <stdint.h>
#include <string.h>

#include "../defines.h"
#include "../util.h"

#include "../generated_headers/android_graphics_Matrix.h"

static void m3_get(graphene_matrix_t *matrix, float m[9]);

/* AOSP Matrix(Matrix src): a copy of src, or the identity (src 0). The copy was missing: every
 * new Matrix(m) was the identity (GskCanvas.save() lost the canvas matrix it tracks). */
JNIEXPORT jlong JNICALL Java_android_graphics_Matrix_native_1create(JNIEnv *env, jclass class, jlong src)
{
	graphene_matrix_t *matrix = graphene_matrix_alloc();
	if (src)
		graphene_matrix_init_from_matrix(matrix, (graphene_matrix_t *)_PTR(src));
	else
		graphene_matrix_init_identity(matrix);
	return _INTPTR(matrix);
}

JNIEXPORT void JNICALL Java_android_graphics_Matrix_native_1getValues(JNIEnv *env, jclass class, jlong src, jfloatArray values_ref)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(src);
	jfloat *value = (*env)->GetFloatArrayElements(env, values_ref, NULL);
	// add 0.f to all values to avoid failing CTS tests with "expected:<0.0> but was:<-0.0>"
	value[android_graphics_Matrix_MSCALE_X] = graphene_matrix_get_value(matrix, 0, 0) + 0.f;
	value[android_graphics_Matrix_MSKEW_X] = graphene_matrix_get_value(matrix, 1, 0) + 0.f;
	value[android_graphics_Matrix_MTRANS_X] = graphene_matrix_get_value(matrix, 3, 0) + 0.f;
	value[android_graphics_Matrix_MSKEW_Y] = graphene_matrix_get_value(matrix, 0, 1) + 0.f;
	value[android_graphics_Matrix_MSCALE_Y] = graphene_matrix_get_value(matrix, 1, 1) + 0.f;
	value[android_graphics_Matrix_MTRANS_Y] = graphene_matrix_get_value(matrix, 3, 1) + 0.f;
	value[android_graphics_Matrix_MPERSP_0] = graphene_matrix_get_value(matrix, 0, 3) + 0.f;
	value[android_graphics_Matrix_MPERSP_1] = graphene_matrix_get_value(matrix, 1, 3) + 0.f;
	value[android_graphics_Matrix_MPERSP_2] = graphene_matrix_get_value(matrix, 3, 3) + 0.f;
	(*env)->ReleaseFloatArrayElements(env, values_ref, value, 0);
}

JNIEXPORT void JNICALL Java_android_graphics_Matrix_native_1set(JNIEnv *env, jclass class, jlong dest_ptr, jlong src_ptr)
{
	graphene_matrix_t *dest = (graphene_matrix_t *)_PTR(dest_ptr);
	graphene_matrix_t *src = (graphene_matrix_t *)_PTR(src_ptr);
	graphene_matrix_init_from_matrix(dest, src);
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1isIdentity(JNIEnv *env, jclass class, jlong matrix_ptr)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	return graphene_matrix_is_identity(matrix);
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1preConcat(JNIEnv *env, jclass class, jlong matrix_ptr, jlong other_ptr)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_t *other = (graphene_matrix_t *)_PTR(other_ptr);
	graphene_matrix_multiply(other, matrix, matrix);

	return true;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1postConcat(JNIEnv *env, jclass class, jlong matrix_ptr, jlong other_ptr)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_t *other = (graphene_matrix_t *)_PTR(other_ptr);
	graphene_matrix_multiply(matrix, other, matrix);
	return true;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1mapRect(JNIEnv *env, jclass class, jlong matrix_ptr, jobject dest, jobject src)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_rect_t src_rect = GRAPHENE_RECT_INIT(
	    _GET_FLOAT_FIELD(src, "left"),
	    _GET_FLOAT_FIELD(src, "top"),
	    _GET_FLOAT_FIELD(src, "right") - _GET_FLOAT_FIELD(src, "left"),
	    _GET_FLOAT_FIELD(src, "bottom") - _GET_FLOAT_FIELD(src, "top"));

	graphene_quad_t dest_quad;
	graphene_matrix_transform_rect(matrix, &src_rect, &dest_quad);
	graphene_rect_t dest_rect;
	graphene_quad_bounds(&dest_quad, &dest_rect);

	_SET_FLOAT_FIELD(dest, "left", dest_rect.origin.x);
	_SET_FLOAT_FIELD(dest, "top", dest_rect.origin.y);
	_SET_FLOAT_FIELD(dest, "right", dest_rect.origin.x + dest_rect.size.width);
	_SET_FLOAT_FIELD(dest, "bottom", dest_rect.origin.y + dest_rect.size.height);
	return true;
}

JNIEXPORT void JNICALL Java_android_graphics_Matrix_native_1reset(JNIEnv *env, jclass class, jlong matrix_ptr)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_init_identity(matrix);
}

JNIEXPORT void JNICALL Java_android_graphics_Matrix_finalizer(JNIEnv *env, jclass class, jlong matrix_ptr)
{
	graphene_matrix_free((graphene_matrix_t *)_PTR(matrix_ptr));
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1postTranslate(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat x, jfloat y)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_point3d_t translation = GRAPHENE_POINT3D_INIT(x, y, 0);
	graphene_matrix_translate(matrix, &translation);
	return true;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1postScale__JFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat x, jfloat y)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_scale(matrix, x, y, 1.f);
	return true;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1postRotate__JFFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat degrees, jfloat px, jfloat py)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_translate(matrix, &GRAPHENE_POINT3D_INIT(-px, -py, 0));
	graphene_matrix_rotate_z(matrix, degrees);
	graphene_matrix_translate(matrix, &GRAPHENE_POINT3D_INIT(px, py, 0));
	return true;
}

JNIEXPORT void Java_android_graphics_Matrix_native_1setScale__JFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat x, jfloat y)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_init_scale(matrix, x, y, 1.f);
}

JNIEXPORT void JNICALL Java_android_graphics_Matrix_native_1setScale__JFFFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat x, jfloat y, jfloat px, jfloat py)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_init_translate(matrix, &GRAPHENE_POINT3D_INIT(-px, -py, 0));
	graphene_matrix_scale(matrix, x, y, 1.f);
	graphene_matrix_translate(matrix, &GRAPHENE_POINT3D_INIT(px, py, 0));
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1postScale__JFFFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat x, jfloat y, jfloat px, jfloat py)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_translate(matrix, &GRAPHENE_POINT3D_INIT(-px, -py, 0));
	graphene_matrix_scale(matrix, x, y, 1.f);
	graphene_matrix_translate(matrix, &GRAPHENE_POINT3D_INIT(px, py, 0));
	return true;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1postRotate__JF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat degrees)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_rotate_z(matrix, degrees);
	return true;
}

#define SCALE_TO_FIT_FILL   0
#define SCALE_TO_FIT_START  1
#define SCALE_TO_FIT_CENTER 2
#define SCALE_TO_FIT_END    3
JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1setRectToRect(JNIEnv *env, jclass class, jlong matrix_ptr, jobject src, jobject dest, jint stf)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_init_translate(matrix, &GRAPHENE_POINT3D_INIT(-_GET_FLOAT_FIELD(src, "left"), -_GET_FLOAT_FIELD(src, "top"), 0));
	float src_width = _GET_FLOAT_FIELD(src, "right") - _GET_FLOAT_FIELD(src, "left");
	float src_height = _GET_FLOAT_FIELD(src, "bottom") - _GET_FLOAT_FIELD(src, "top");
	float dest_width = _GET_FLOAT_FIELD(dest, "right") - _GET_FLOAT_FIELD(dest, "left");
	float dest_height = _GET_FLOAT_FIELD(dest, "bottom") - _GET_FLOAT_FIELD(dest, "top");
	float factor_x = dest_width / src_width;
	float factor_y = dest_height / src_height;
	if (stf != SCALE_TO_FIT_FILL) {
		factor_x = factor_y = (factor_x < factor_y) ? factor_x : factor_y;
	}
	graphene_matrix_scale(matrix, factor_x, factor_y, 1.f);
	graphene_point3d_t translation = GRAPHENE_POINT3D_INIT(_GET_FLOAT_FIELD(dest, "left"), _GET_FLOAT_FIELD(dest, "top"), 0);
	if (stf == SCALE_TO_FIT_CENTER) {
		translation.x += (dest_width - src_width * factor_x) / 2;
		translation.y += (dest_height - src_height * factor_y) / 2;
	} else if (stf == SCALE_TO_FIT_END) {
		translation.x += (dest_width - src_width * factor_x);
		translation.y += (dest_height - src_height * factor_y);
	}
	graphene_matrix_translate(matrix, &translation);
	return true;
}

JNIEXPORT void JNICALL Java_android_graphics_Matrix_native_1mapPoints(JNIEnv *env, jclass class, jlong matrix_ptr, jfloatArray dst_ref, jint dst_idx, jfloatArray src_ref, jint src_idx, jint count, jboolean is_pts)
{
	/* SkMatrix::mapPoints / mapVectors: with perspective a point is divided by its w (graphene's 2D
	 * transform ignored the perspective row); a vector is the mapped point minus the mapped origin */
	float m[9];
	m3_get((graphene_matrix_t *)_PTR(matrix_ptr), m);
	const int persp = m[6] != 0 || m[7] != 0 || m[8] != 1;
	jfloat *src = (*env)->GetFloatArrayElements(env, src_ref, NULL);
	jfloat *dst = (*env)->GetFloatArrayElements(env, dst_ref, NULL);
	for (int i = 0; i < count; i++) {
		float x = src[src_idx + i * 2], y = src[src_idx + i * 2 + 1];
		float rx = m[0] * x + m[1] * y, ry = m[3] * x + m[4] * y;
		if (is_pts || persp) {
			rx += m[2];
			ry += m[5];
		}
		if (persp) {
			float w = m[6] * x + m[7] * y + m[8];
			w = w ? 1 / w : 0;
			rx *= w;
			ry *= w;
			if (!is_pts) {
				float w0 = m[8] ? 1 / m[8] : 0;
				rx -= m[2] * w0;
				ry -= m[5] * w0;
			}
		}
		dst[dst_idx + i * 2] = rx;
		dst[dst_idx + i * 2 + 1] = ry;
	}
	(*env)->ReleaseFloatArrayElements(env, src_ref, src, 0);
	(*env)->ReleaseFloatArrayElements(env, dst_ref, dst, 0);
}

JNIEXPORT void JNICALL Java_android_graphics_Matrix_native_1setTranslate(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat x, jfloat y)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_init_translate(matrix, &GRAPHENE_POINT3D_INIT(x, y, 0));
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1preRotate__JF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat degrees)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_t rotation;
	graphene_vec3_t rotation_axis;
	graphene_vec3_init(&rotation_axis, 0, 0, 1);
	graphene_matrix_init_rotate(&rotation, degrees, &rotation_axis);
	graphene_matrix_multiply(&rotation, matrix, matrix);
	return true;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1invert(JNIEnv *env, jclass class, jlong matrix_ptr, jlong inverse_ptr)
{
	return graphene_matrix_inverse((graphene_matrix_t *)_PTR(matrix_ptr), (graphene_matrix_t *)_PTR(inverse_ptr));
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1preScale__JFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat x, jfloat y)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_t scale;
	graphene_matrix_init_scale(&scale, x, y, 1);
	graphene_matrix_multiply(&scale, matrix, matrix);
	return true;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1preScale__JFFFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat x, jfloat y, jfloat px, jfloat py)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_t scale;
	graphene_matrix_init_scale(&scale, x, y, 1);
	graphene_matrix_translate(&scale, &GRAPHENE_POINT3D_INIT(-px, -py, 0));
	graphene_matrix_multiply(&scale, matrix, matrix);
	graphene_matrix_translate(matrix, &GRAPHENE_POINT3D_INIT(px, py, 0));
	return true;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1preTranslate(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat x, jfloat y)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_t translation;
	graphene_matrix_init_translate(&translation, &GRAPHENE_POINT3D_INIT(x, y, 0));
	graphene_matrix_multiply(&translation, matrix, matrix);
	return true;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1preRotate__JFFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat degrees, jfloat px, jfloat py)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_t rotation;
	graphene_vec3_t rotation_axis;
	graphene_vec3_init(&rotation_axis, 0, 0, 1);
	graphene_matrix_init_rotate(&rotation, degrees, &rotation_axis);
	graphene_matrix_translate(&rotation, &GRAPHENE_POINT3D_INIT(-px, -py, 0));
	graphene_matrix_multiply(&rotation, matrix, matrix);
	graphene_matrix_translate(matrix, &GRAPHENE_POINT3D_INIT(px, py, 0));
	return true;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1equals(JNIEnv *env, jclass class, jlong matrix1_ptr, jlong matrix2_ptr)
{
	graphene_matrix_t *matrix1 = (graphene_matrix_t *)_PTR(matrix1_ptr);
	graphene_matrix_t *matrix2 = (graphene_matrix_t *)_PTR(matrix2_ptr);
	return graphene_matrix_equal(matrix1, matrix2);
}

JNIEXPORT void JNICALL Java_android_graphics_Matrix_native_1setValues(JNIEnv *env, jclass class, jlong matrix_ptr, jfloatArray values_ref)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	jfloat *values = (*env)->GetFloatArrayElements(env, values_ref, NULL);
	float values4x4[4][4] = {
		/* clang-format off */
		{values[android_graphics_Matrix_MSCALE_X],  values[android_graphics_Matrix_MSKEW_Y], 0, values[android_graphics_Matrix_MPERSP_0]},
		{ values[android_graphics_Matrix_MSKEW_X], values[android_graphics_Matrix_MSCALE_Y], 0, values[android_graphics_Matrix_MPERSP_1]},
		{                                       0,                                        0, 1,                                        0},
		{values[android_graphics_Matrix_MTRANS_X], values[android_graphics_Matrix_MTRANS_Y], 0, values[android_graphics_Matrix_MPERSP_2]},
		/* clang-format on */
	};
	graphene_matrix_init_from_float(matrix, *values4x4);
	(*env)->ReleaseFloatArrayElements(env, values_ref, values, 0);
}

JNIEXPORT void JNICALL Java_android_graphics_Matrix_native_1setRotate__JFFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat degrees, jfloat px, jfloat py)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_init_translate(matrix, &GRAPHENE_POINT3D_INIT(-px, -py, 0));
	graphene_matrix_rotate_z(matrix, degrees);
	graphene_matrix_translate(matrix, &GRAPHENE_POINT3D_INIT(px, py, 0));
}

JNIEXPORT void JNICALL Java_android_graphics_Matrix_native_1setRotate__JF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat degrees)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	graphene_matrix_rotate_z(matrix, degrees);
}

/* ---- the 3x3 operations Skia's SkMatrix has (they were declared in Matrix.java without natives) ----
 * In Skia's layout: m[0] scaleX, m[1] skewX, m[2] transX, m[3] skewY, m[4] scaleY, m[5] transY,
 * m[6] persp0, m[7] persp1, m[8] persp2; a point is mapped as a column vector (M * p). */

enum { SX, KX, TX, KY, SY, TY, P0, P1, P2 };

static void m3_get(graphene_matrix_t *matrix, float m[9])
{
	m[SX] = graphene_matrix_get_value(matrix, 0, 0);
	m[KX] = graphene_matrix_get_value(matrix, 1, 0);
	m[TX] = graphene_matrix_get_value(matrix, 3, 0);
	m[KY] = graphene_matrix_get_value(matrix, 0, 1);
	m[SY] = graphene_matrix_get_value(matrix, 1, 1);
	m[TY] = graphene_matrix_get_value(matrix, 3, 1);
	m[P0] = graphene_matrix_get_value(matrix, 0, 3);
	m[P1] = graphene_matrix_get_value(matrix, 1, 3);
	m[P2] = graphene_matrix_get_value(matrix, 3, 3);
}

static void m3_set(graphene_matrix_t *matrix, const float m[9])
{
	float values4x4[4][4] = {
		{m[SX], m[KY], 0, m[P0]},
		{m[KX], m[SY], 0, m[P1]},
		{    0,     0, 1,     0},
		{m[TX], m[TY], 0, m[P2]},
	};
	graphene_matrix_init_from_float(matrix, *values4x4);
}

/* r = a * b (b applied first) */
static void m3_mul(float r[9], const float a[9], const float b[9])
{
	float t[9];
	for (int i = 0; i < 3; i++)
		for (int j = 0; j < 3; j++)
			t[i * 3 + j] = a[i * 3 + 0] * b[0 * 3 + j] + a[i * 3 + 1] * b[1 * 3 + j] + a[i * 3 + 2] * b[2 * 3 + j];
	memcpy(r, t, sizeof(t));
}

static int m3_invert(float r[9], const float m[9])
{
	double det = (double)m[0] * ((double)m[4] * m[8] - (double)m[5] * m[7])
	           - (double)m[1] * ((double)m[3] * m[8] - (double)m[5] * m[6])
	           + (double)m[2] * ((double)m[3] * m[7] - (double)m[4] * m[6]);
	if (!isfinite(det) || fabs(det) < 1e-12) /* Skia: SK_ScalarNearlyZero^3 */
		return 0;
	double inv = 1.0 / det;
	float t[9] = {
		(float)(((double)m[4] * m[8] - (double)m[5] * m[7]) * inv),
		(float)(((double)m[2] * m[7] - (double)m[1] * m[8]) * inv),
		(float)(((double)m[1] * m[5] - (double)m[2] * m[4]) * inv),
		(float)(((double)m[5] * m[6] - (double)m[3] * m[8]) * inv),
		(float)(((double)m[0] * m[8] - (double)m[2] * m[6]) * inv),
		(float)(((double)m[2] * m[3] - (double)m[0] * m[5]) * inv),
		(float)(((double)m[3] * m[7] - (double)m[4] * m[6]) * inv),
		(float)(((double)m[1] * m[6] - (double)m[0] * m[7]) * inv),
		(float)(((double)m[0] * m[4] - (double)m[1] * m[3]) * inv),
	};
	memcpy(r, t, sizeof(t));
	return 1;
}

static void m3_identity(float m[9])
{
	static const float id[9] = { 1, 0, 0, 0, 1, 0, 0, 0, 1 };
	memcpy(m, id, sizeof(id));
}

/* SkMatrix::setSkew(kx, ky, px, py) */
static void m3_skew(float m[9], float kx, float ky, float px, float py)
{
	m3_identity(m);
	m[KX] = kx;
	m[KY] = ky;
	m[TX] = -kx * py;
	m[TY] = -ky * px;
}

JNIEXPORT void JNICALL Java_android_graphics_Matrix_native_1setSkew__JFFFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat kx, jfloat ky, jfloat px, jfloat py)
{
	float m[9];
	m3_skew(m, kx, ky, px, py);
	m3_set(_PTR(matrix_ptr), m);
}

JNIEXPORT void JNICALL Java_android_graphics_Matrix_native_1setSkew__JFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat kx, jfloat ky)
{
	Java_android_graphics_Matrix_native_1setSkew__JFFFF(env, class, matrix_ptr, kx, ky, 0, 0);
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1preSkew__JFFFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat kx, jfloat ky, jfloat px, jfloat py)
{
	float m[9], s[9];
	m3_get(_PTR(matrix_ptr), m);
	m3_skew(s, kx, ky, px, py);
	m3_mul(m, m, s);
	m3_set(_PTR(matrix_ptr), m);
	return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1preSkew__JFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat kx, jfloat ky)
{
	return Java_android_graphics_Matrix_native_1preSkew__JFFFF(env, class, matrix_ptr, kx, ky, 0, 0);
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1postSkew__JFFFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat kx, jfloat ky, jfloat px, jfloat py)
{
	float m[9], s[9];
	m3_get(_PTR(matrix_ptr), m);
	m3_skew(s, kx, ky, px, py);
	m3_mul(m, s, m);
	m3_set(_PTR(matrix_ptr), m);
	return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1postSkew__JFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat kx, jfloat ky)
{
	return Java_android_graphics_Matrix_native_1postSkew__JFFFF(env, class, matrix_ptr, kx, ky, 0, 0);
}

/* SkMatrix::setSinCos(sinV, cosV, px, py): the rotation with those values about (px, py) */
JNIEXPORT void JNICALL Java_android_graphics_Matrix_native_1setSinCos__JFFFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat sin_v, jfloat cos_v, jfloat px, jfloat py)
{
	float m[9];
	const float one_minus_cos = 1 - cos_v;
	m3_identity(m);
	m[SX] = cos_v;
	m[KX] = -sin_v;
	m[TX] = sin_v * py + one_minus_cos * px;
	m[KY] = sin_v;
	m[SY] = cos_v;
	m[TY] = -sin_v * px + one_minus_cos * py;
	m3_set(_PTR(matrix_ptr), m);
}

JNIEXPORT void JNICALL Java_android_graphics_Matrix_native_1setSinCos__JFF(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat sin_v, jfloat cos_v)
{
	Java_android_graphics_Matrix_native_1setSinCos__JFFFF(env, class, matrix_ptr, sin_v, cos_v, 0, 0);
}

/* AOSP setConcat(a, b): this = a * b (b applied first) */
JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1setConcat(JNIEnv *env, jclass class, jlong matrix_ptr, jlong a_ptr, jlong b_ptr)
{
	float a[9], b[9], r[9];
	m3_get(_PTR(a_ptr), a);
	m3_get(_PTR(b_ptr), b);
	m3_mul(r, a, b);
	m3_set(_PTR(matrix_ptr), r);
	return JNI_TRUE;
}

/* SkMatrix::mapRadius: the geometric mean of the lengths of the mapped vectors (r, 0) and (0, r) */
JNIEXPORT jfloat JNICALL Java_android_graphics_Matrix_native_1mapRadius(JNIEnv *env, jclass class, jlong matrix_ptr, jfloat radius)
{
	float m[9];
	m3_get(_PTR(matrix_ptr), m);
	float v[2][2] = { { radius, 0 }, { 0, radius } };
	float len[2];
	for (int i = 0; i < 2; i++) {
		float x = v[i][0], y = v[i][1];
		float mx, my;
		if (m[P0] == 0 && m[P1] == 0 && m[P2] == 1) {
			mx = m[SX] * x + m[KX] * y;
			my = m[KY] * x + m[SY] * y;
		} else {
			/* perspective (SkMatrix::mapVectors): the mapped point minus the mapped origin */
			float w = m[P0] * x + m[P1] * y + m[P2], w0 = m[P2];
			mx = (m[SX] * x + m[KX] * y + m[TX]) / w - m[TX] / w0;
			my = (m[KY] * x + m[SY] * y + m[TY]) / w - m[TY] / w0;
		}
		len[i] = sqrtf(mx * mx + my * my);
	}
	return sqrtf(len[0] * len[1]);
}

/* SkMatrix's PolyMapProcs: a matrix taking the unit square's corners (or two/three of them) to the points */
static int poly2(const float *p, float m[9])
{
	m[SX] = p[3] - p[1];
	m[KY] = p[0] - p[2];
	m[P0] = 0;
	m[KX] = p[2] - p[0];
	m[SY] = p[3] - p[1];
	m[P1] = 0;
	m[TX] = p[0];
	m[TY] = p[1];
	m[P2] = 1;
	return 1;
}

static int poly3(const float *p, float m[9])
{
	m[SX] = p[4] - p[0];
	m[KY] = p[5] - p[1];
	m[P0] = 0;
	m[KX] = p[2] - p[0];
	m[SY] = p[3] - p[1];
	m[P1] = 0;
	m[TX] = p[0];
	m[TY] = p[1];
	m[P2] = 1;
	return 1;
}

/* Skia's checkForZero: SkScalarNearlyZero(x * x), SK_ScalarNearlyZero being 1 / 4096 */
static int nearly_zero(float x)
{
	return fabsf(x * x) <= 1.0f / 4096;
}

static int poly4(const float *p, float m[9])
{
	float a1, a2;
	float x0 = p[4] - p[0], y0 = p[5] - p[1];
	float x1 = p[4] - p[2], y1 = p[5] - p[3];
	float x2 = p[4] - p[6], y2 = p[5] - p[7];
	/* abs(x2) > abs(y2) */
	if (x2 > 0 ? (y2 > 0 ? x2 > y2 : x2 > -y2) : (y2 > 0 ? -x2 > y2 : x2 < y2)) {
		float denom = x1 * y2 / x2 - y1;
		if (nearly_zero(denom))
			return 0;
		a1 = (((x0 - x1) * y2 / x2) - y0 + y1) / denom;
	} else {
		float denom = x1 - y1 * x2 / y2;
		if (nearly_zero(denom))
			return 0;
		a1 = (x0 - x1 - (y0 - y1) * x2 / y2) / denom;
	}
	/* abs(x1) > abs(y1) */
	if (x1 > 0 ? (y1 > 0 ? x1 > y1 : x1 > -y1) : (y1 > 0 ? -x1 > y1 : x1 < y1)) {
		float denom = y2 - x2 * y1 / x1;
		if (nearly_zero(denom))
			return 0;
		a2 = (y0 - y2 - (x0 - x2) * y1 / x1) / denom;
	} else {
		float denom = y2 * x1 / y1 - x2;
		if (nearly_zero(denom))
			return 0;
		a2 = ((y0 - y2) * x1 / y1 - x0 + x2) / denom;
	}
	m[SX] = a2 * p[6] + p[6] - p[0];
	m[KY] = a2 * p[7] + p[7] - p[1];
	m[P0] = a2;
	m[KX] = a1 * p[2] + p[2] - p[0];
	m[SY] = a1 * p[3] + p[3] - p[1];
	m[P1] = a1;
	m[TX] = p[0];
	m[TY] = p[1];
	m[P2] = 1;
	return 1;
}

/* SkMatrix::setPolyToPoly: src's points onto dst's (0 to 4 points); false when they are degenerate */
JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1setPolyToPoly(JNIEnv *env, jclass class, jlong matrix_ptr, jfloatArray src_ref, jint src_index, jfloatArray dst_ref, jint dst_index, jint count)
{
	if (count < 0 || count > 4)
		return JNI_FALSE;
	float src[8], dst[8], m[9];
	(*env)->GetFloatArrayRegion(env, src_ref, src_index, count * 2, src);
	(*env)->GetFloatArrayRegion(env, dst_ref, dst_index, count * 2, dst);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	if (count == 0) {
		m3_identity(m);
		m3_set(_PTR(matrix_ptr), m);
		return JNI_TRUE;
	}
	if (count == 1) {
		m3_identity(m);
		m[TX] = dst[0] - src[0];
		m[TY] = dst[1] - src[1];
		m3_set(_PTR(matrix_ptr), m);
		return JNI_TRUE;
	}
	int (*proc)(const float *, float *) = count == 2 ? poly2 : count == 3 ? poly3 : poly4;
	float temp[9], result[9];
	if (!proc(src, temp) || !m3_invert(result, temp))
		return JNI_FALSE;
	if (!proc(dst, temp))
		return JNI_FALSE;
	m3_mul(m, temp, result);
	m3_set(_PTR(matrix_ptr), m);
	return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Matrix_native_1rectStaysRect(JNIEnv *env, jclass class, jlong matrix_ptr)
{
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	float scale_x = graphene_matrix_get_value(matrix, 0, 0);
	float skew_x = graphene_matrix_get_value(matrix, 1, 0);
	float skew_y = graphene_matrix_get_value(matrix, 0, 1);
	float scale_y = graphene_matrix_get_value(matrix, 1, 1);
	return (!skew_x && !skew_y) || (!scale_x && !scale_y);
}
