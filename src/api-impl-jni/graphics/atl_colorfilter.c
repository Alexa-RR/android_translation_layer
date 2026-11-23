/*
 * teslalinux: blend-mode colour filters (PorterDuffColorFilter, BlendModeColorFilter) with Skia's
 * results for every mode.
 *
 * Skia's SkModeColorFilter blends its colour (the source) onto each pixel the paint produces (the
 * destination): out = mode(filter colour, pixel), in premultiplied colour. The filter acts on the
 * paint's colour before coverage, so:
 *  - a solid-colour draw (rects, paths, text, lines) needs the filtered colour only: it is computed
 *    here exactly (tl_paint_filtered_color, Skia's per-mode formulas, also for colour matrices) and
 *    drawn with no filter node at all;
 *  - drawn content whose colour varies (bitmaps, shaders, nine-patches, drawables, layers) gets a
 *    node that computes the mode for every pixel inside the content's rectangle (Skia draws such
 *    content as an image, every texel of which goes through the filter, transparent ones too):
 *    tl_paint_filter_node.
 * The modes that are affine maps of the un-premultiplied colour (CLEAR, SRC, DST, SRC_IN, DST_IN,
 * SRC_OUT, DST_OUT, SRC_ATOP, MODULATE, SRC_OVER with an opaque colour) are GSK colour matrices; the
 * others are built from GSK's exact operations: source-over (container), alpha masks, opacity,
 * colour matrices, the W3C blend modes (Skia's separable and non-separable modes are the W3C ones),
 * and cross-fades for sums (a cross-fade at 0.5 is half the sum of two premultiplied pixels; a
 * colour matrix doubling the alpha doubles it back).
 *
 * Mode numbers are SkBlendMode values (PorterDuff.Mode.nativeInt, BlendMode.toValue): 0 CLEAR 1 SRC
 * 2 DST 3 SRC_OVER 4 DST_OVER 5 SRC_IN 6 DST_IN 7 SRC_OUT 8 DST_OUT 9 SRC_ATOP 10 DST_ATOP 11 XOR
 * 12 PLUS 13 MODULATE 14 SCREEN 15 OVERLAY 16 DARKEN 17 LIGHTEN 18 COLOR_DODGE 19 COLOR_BURN
 * 20 HARD_LIGHT 21 SOFT_LIGHT 22 DIFFERENCE 23 EXCLUSION 24 MULTIPLY 25 HUE 26 SATURATION 27 COLOR
 * 28 LUMINOSITY.
 */
#include <math.h>
#include <gtk/gtk.h>
#include <jni.h>

#include "../defines.h"
#include "AndroidPaint.h"

/* ---- Skia's blend modes on one premultiplied colour (SkRasterPipeline's stages) ---------------- */

static float inv(float x) { return 1.f - x; }
static float clamp01(float x) { return x < 0.f ? 0.f : x > 1.f ? 1.f : x; }

static float lum(float r, float g, float b) { return r * 0.30f + g * 0.59f + b * 0.11f; }

static float sat(float r, float g, float b) { return fmaxf(r, fmaxf(g, b)) - fminf(r, fminf(g, b)); }

static void set_sat(float *r, float *g, float *b, float s)
{
	const float mn = fminf(*r, fminf(*g, *b)), mx = fmaxf(*r, fmaxf(*g, *b)), st = mx - mn;
	*r = st == 0.f ? 0.f : (*r - mn) * s / st;
	*g = st == 0.f ? 0.f : (*g - mn) * s / st;
	*b = st == 0.f ? 0.f : (*b - mn) * s / st;
}

static void set_lum(float *r, float *g, float *b, float l)
{
	const float diff = l - lum(*r, *g, *b);
	*r += diff;
	*g += diff;
	*b += diff;
}

static float clip_one(float c, float mn, float mx, float l, float a)
{
	if (mn < 0.f && l - mn != 0.f)
		c = l + (c - l) * l / (l - mn);
	if (mx > a && mx - l != 0.f)
		c = l + (c - l) * (a - l) / (mx - l);
	return fmaxf(c, 0.f);
}

static void clip_color(float *r, float *g, float *b, float a)
{
	const float mn = fminf(*r, fminf(*g, *b)), mx = fmaxf(*r, fmaxf(*g, *b)), l = lum(*r, *g, *b);
	*r = clip_one(*r, mn, mx, l, a);
	*g = clip_one(*g, mn, mx, l, a);
	*b = clip_one(*b, mn, mx, l, a);
}

static float separable(int mode, float s, float d, float sa, float da)
{
	switch (mode) {
	case 14: /* SCREEN */
		return s + d - s * d;
	case 15: /* OVERLAY: hard light with source and destination swapped */
		return s * inv(da) + d * inv(sa) + (2 * d <= da ? 2 * s * d : sa * da - 2 * (da - d) * (sa - s));
	case 16: /* DARKEN */
		return s + d - fmaxf(s * da, d * sa);
	case 17: /* LIGHTEN */
		return s + d - fminf(s * da, d * sa);
	case 18: /* COLOR_DODGE */
		if (d == 0.f)
			return s * inv(da);
		if (s == sa)
			return s + d * inv(sa);
		return sa * fminf(da, (d * sa) / (sa - s)) + s * inv(da) + d * inv(sa);
	case 19: /* COLOR_BURN */
		if (d == da)
			return d + s * inv(da);
		if (s == 0.f)
			return d * inv(sa);
		return sa * (da - fminf(da, (da - d) * sa / s)) + s * inv(da) + d * inv(sa);
	case 20: /* HARD_LIGHT */
		return s * inv(da) + d * inv(sa) + (2 * s <= sa ? 2 * s * d : sa * da - 2 * (da - d) * (sa - s));
	case 21: { /* SOFT_LIGHT */
		const float m = da > 0.f ? d / da : 0.f, s2 = 2 * s, m4 = 4 * m;
		const float dark_src = d * (sa + (s2 - sa) * (1.f - m));
		const float dark_dst = (m4 * m4 + m4) * (m - 1.f) + 7.f * m;
		const float lite_dst = sqrtf(m) - m;
		const float lite_src = d * sa + da * (s2 - sa) * (4 * d <= da ? dark_dst : lite_dst);
		return s * inv(da) + d * inv(sa) + (s2 <= sa ? dark_src : lite_src);
	}
	case 22: /* DIFFERENCE */
		return s + d - 2 * fminf(s * da, d * sa);
	case 23: /* EXCLUSION */
		return s + d - 2 * s * d;
	case 24: /* MULTIPLY */
		return s * inv(da) + d * inv(sa) + s * d;
	default:
		return s;
	}
}

/* out = mode(s, d), all premultiplied RGBA */
static void tl_blend(int mode, const float s[4], const float d[4], float out[4])
{
	const float sa = s[3], da = d[3];
	int i;
	switch (mode) {
	case 0: for (i = 0; i < 4; i++) out[i] = 0; return;
	case 1: for (i = 0; i < 4; i++) out[i] = s[i]; return;
	case 2: for (i = 0; i < 4; i++) out[i] = d[i]; return;
	case 3: for (i = 0; i < 4; i++) out[i] = s[i] + d[i] * inv(sa); return;
	case 4: for (i = 0; i < 4; i++) out[i] = d[i] + s[i] * inv(da); return;
	case 5: for (i = 0; i < 4; i++) out[i] = s[i] * da; return;
	case 6: for (i = 0; i < 4; i++) out[i] = d[i] * sa; return;
	case 7: for (i = 0; i < 4; i++) out[i] = s[i] * inv(da); return;
	case 8: for (i = 0; i < 4; i++) out[i] = d[i] * inv(sa); return;
	case 9: for (i = 0; i < 4; i++) out[i] = s[i] * da + d[i] * inv(sa); return;
	case 10: for (i = 0; i < 4; i++) out[i] = d[i] * sa + s[i] * inv(da); return;
	case 11: for (i = 0; i < 4; i++) out[i] = s[i] * inv(da) + d[i] * inv(sa); return;
	case 12: for (i = 0; i < 4; i++) out[i] = fminf(s[i] + d[i], 1.f); return;
	case 13: for (i = 0; i < 4; i++) out[i] = s[i] * d[i]; return;
	case 25: case 26: case 27: case 28: {
		float R, G, B;
		const float r = s[0], g = s[1], b = s[2], dr = d[0], dg = d[1], db = d[2];
		if (mode == 25) { /* HUE */
			R = r * da; G = g * da; B = b * da;
			set_sat(&R, &G, &B, sat(dr, dg, db) * sa);
			set_lum(&R, &G, &B, lum(dr, dg, db) * sa);
		} else if (mode == 26) { /* SATURATION */
			R = dr * sa; G = dg * sa; B = db * sa;
			set_sat(&R, &G, &B, sat(r, g, b) * da);
			set_lum(&R, &G, &B, lum(dr, dg, db) * sa);
		} else if (mode == 27) { /* COLOR */
			R = r * da; G = g * da; B = b * da;
			set_lum(&R, &G, &B, lum(dr, dg, db) * sa);
		} else { /* LUMINOSITY */
			R = dr * sa; G = dg * sa; B = db * sa;
			set_lum(&R, &G, &B, lum(r, g, b) * da);
		}
		clip_color(&R, &G, &B, sa * da);
		out[0] = r * inv(da) + dr * inv(sa) + R;
		out[1] = g * inv(da) + dg * inv(sa) + G;
		out[2] = b * inv(da) + db * inv(sa) + B;
		out[3] = sa + da - sa * da;
		return;
	}
	default: /* the separable modes keep source-over's alpha */
		for (i = 0; i < 3; i++)
			out[i] = separable(mode, s[i], d[i], sa, da);
		out[3] = sa + da - sa * da;
		return;
	}
}

/* ---- the filter on a solid colour --------------------------------------------------------------- */

void tl_paint_filtered_color(const struct AndroidPaint *paint, GdkRGBA *out)
{
	const GdkRGBA *c = &paint->color;
	if (!paint->use_color_filter) {
		*out = *c;
		return;
	}
	if (paint->filter_by_mode) {
		const GdkRGBA *f = &paint->filter_color;
		const float s[4] = { f->red * f->alpha, f->green * f->alpha, f->blue * f->alpha, f->alpha };
		const float d[4] = { c->red * c->alpha, c->green * c->alpha, c->blue * c->alpha, c->alpha };
		float r[4];
		tl_blend(paint->filter_mode, s, d, r);
		const float a = clamp01(r[3]);
		*out = (GdkRGBA){
			a > 0.f ? clamp01(r[0] / a) : 0.f,
			a > 0.f ? clamp01(r[1] / a) : 0.f,
			a > 0.f ? clamp01(r[2] / a) : 0.f,
			a,
		};
		return;
	}
	/* a colour matrix on the un-premultiplied colour (a transparent colour un-premultiplies to 0, as
	 * Skia's), row vector times the matrix plus the offset, clamped */
	graphene_vec4_t v, r;
	if (c->alpha > 0.f)
		graphene_vec4_init(&v, c->red, c->green, c->blue, c->alpha);
	else
		graphene_vec4_init(&v, 0, 0, 0, 0);
	graphene_matrix_transform_vec4(&paint->color_matrix, &v, &r);
	graphene_vec4_add(&r, &paint->color_offset, &r);
	*out = (GdkRGBA){
		clamp01(graphene_vec4_get_x(&r)),
		clamp01(graphene_vec4_get_y(&r)),
		clamp01(graphene_vec4_get_z(&r)),
		clamp01(graphene_vec4_get_w(&r)),
	};
}

/* ---- the filter on drawn content ----------------------------------------------------------------- */

static GskRenderNode *color_rect(float r, float g, float b, float a, const graphene_rect_t *bounds)
{
	return gsk_color_node_new(&(GdkRGBA){ r, g, b, a }, bounds);
}

static GskRenderNode *matrix_node(GskRenderNode *child, const float diag[4], const float offset[4])
{
	graphene_matrix_t m;
	graphene_vec4_t o;
	/* clang-format off */
	graphene_matrix_init_from_float(&m, (float[]){
		diag[0], 0, 0, 0,
		0, diag[1], 0, 0,
		0, 0, diag[2], 0,
		0, 0, 0, diag[3],
	});
	/* clang-format on */
	graphene_vec4_init(&o, offset[0], offset[1], offset[2], offset[3]);
	return gsk_color_matrix_node_new(child, &m, &o);
}

static GskRenderNode *two(GskRenderNode *bottom, GskRenderNode *top)
{
	GskRenderNode *children[2] = { bottom, top };
	return gsk_container_node_new(children, 2);
}

/* a + b for premultiplied pixels whose alphas sum to at most 1 (consumes both) */
static GskRenderNode *sum(GskRenderNode *a, GskRenderNode *b)
{
	GskRenderNode *half = gsk_cross_fade_node_new(a, b, 0.5f);
	gsk_render_node_unref(a);
	gsk_render_node_unref(b);
	GskRenderNode *r = matrix_node(half, (float[]){ 1, 1, 1, 2 }, (float[]){ 0, 0, 0, 0 });
	gsk_render_node_unref(half);
	return r;
}

static GskRenderNode *masked(GskRenderNode *source, GskRenderNode *mask, GskMaskMode mode)
{
	return gsk_mask_node_new(source, mask, mode);
}

static GskBlendMode gsk_blend_for(int mode)
{
	switch (mode) {
	case 14: return GSK_BLEND_MODE_SCREEN;
	case 15: return GSK_BLEND_MODE_OVERLAY;
	case 16: return GSK_BLEND_MODE_DARKEN;
	case 17: return GSK_BLEND_MODE_LIGHTEN;
	case 18: return GSK_BLEND_MODE_COLOR_DODGE;
	case 19: return GSK_BLEND_MODE_COLOR_BURN;
	case 20: return GSK_BLEND_MODE_HARD_LIGHT;
	case 21: return GSK_BLEND_MODE_SOFT_LIGHT;
	case 22: return GSK_BLEND_MODE_DIFFERENCE;
	case 23: return GSK_BLEND_MODE_EXCLUSION;
	case 24: return GSK_BLEND_MODE_MULTIPLY;
	case 25: return GSK_BLEND_MODE_HUE;
	case 26: return GSK_BLEND_MODE_SATURATION;
	case 27: return GSK_BLEND_MODE_COLOR;
	case 28: return GSK_BLEND_MODE_LUMINOSITY;
	default: return GSK_BLEND_MODE_DEFAULT;
	}
}

/* mode(C, E) for every pixel of bounds; E is the content (pixel p), C the filter colour */
static GskRenderNode *mode_node(int mode, const GdkRGBA *f, GskRenderNode *E, const graphene_rect_t *R)
{
	const float ca = f->alpha;
	GskRenderNode *N = color_rect(f->red, f->green, f->blue, ca, R);
	GskRenderNode *r, *t1, *t2, *t;
	switch (mode) {
	case 3: /* SRC_OVER: C + p(1 - Ca) */
		r = two(E, N);
		break;
	case 4: /* DST_OVER: p + C(1 - pa) */
		r = two(N, E);
		break;
	case 10: /* DST_ATOP: Ca * (p + Cc(1 - pa)): p over the opaque colour, then the alpha set to Ca */
		t = color_rect(f->red, f->green, f->blue, 1, R);
		t1 = two(t, E);
		gsk_render_node_unref(t);
		r = matrix_node(t1, (float[]){ 1, 1, 1, 0 }, (float[]){ 0, 0, 0, ca });
		gsk_render_node_unref(t1);
		break;
	case 11: /* XOR: C(1 - pa) + p(1 - Ca), alphas summing to at most 1 */
		t1 = masked(N, E, GSK_MASK_MODE_INVERTED_ALPHA);
		t2 = gsk_opacity_node_new(E, 1.f - ca);
		r = sum(t1, t2);
		break;
	case 12: /* PLUS: min(1, C + p) per premultiplied channel */
		/* where C + p saturates the alpha the result is opaque: p over black (its premultiplied
		 * colour as an opaque one) plus the premultiplied filter colour, clamped */
		t = color_rect(0, 0, 0, 1, R);
		t1 = two(t, E);
		gsk_render_node_unref(t);
		GskRenderNode *opaque = matrix_node(t1, (float[]){ 1, 1, 1, 0 }, (float[]){ f->red * ca, f->green * ca, f->blue * ca, 1 });
		gsk_render_node_unref(t1);
		if (ca >= 1.f) {
			r = opaque;
			break;
		}
		{
			/* elsewhere (pa <= 1 - Ca) the plain sum; a step on pa picks between the two
			 * (both agree at pa = 1 - Ca) */
			GskRenderNode *plain = sum(gsk_render_node_ref(N), gsk_render_node_ref(E));
			graphene_matrix_t m;
			graphene_vec4_t o;
			graphene_matrix_init_from_float(&m, (float[]){ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1024 });
			graphene_vec4_init(&o, 0, 0, 0, -1024 * (1.f - ca));
			GskRenderNode *step = gsk_color_matrix_node_new(E, &m, &o);
			t1 = masked(opaque, step, GSK_MASK_MODE_ALPHA);
			t2 = masked(plain, step, GSK_MASK_MODE_INVERTED_ALPHA);
			gsk_render_node_unref(opaque);
			gsk_render_node_unref(plain);
			gsk_render_node_unref(step);
			r = sum(t1, t2);
		}
		break;
	default: {
		/* the W3C blend modes: (1 - pa) C + (1 - Ca) p + Ca pa B(C, pu) (pu: the un-premultiplied
		 * pixel) = C over Q, Q = pa * lerp(pu, B(C, pu), Ca) with the alpha pa. The blend node only
		 * meets opaque pixels (the content un-premultiplied and made opaque, the opaque colour):
		 * GSK's GL renderer blends translucent ones differently from the W3C formula (its cairo
		 * renderer does not) */
		GskRenderNode *U = matrix_node(E, (float[]){ 1, 1, 1, 0 }, (float[]){ 0, 0, 0, 1 });
		t = color_rect(f->red, f->green, f->blue, 1, R);
		GskRenderNode *B = gsk_blend_node_new(U, t, gsk_blend_for(mode));
		gsk_render_node_unref(t);
		GskRenderNode *L = gsk_cross_fade_node_new(U, B, ca);
		gsk_render_node_unref(U);
		gsk_render_node_unref(B);
		GskRenderNode *Q = masked(L, E, GSK_MASK_MODE_ALPHA);
		gsk_render_node_unref(L);
		r = two(N, Q);
		gsk_render_node_unref(Q);
		break;
	}
	}
	gsk_render_node_unref(N);
	return r;
}

GskRenderNode *tl_paint_filter_node(const struct AndroidPaint *paint, GskRenderNode *child, const graphene_rect_t *bounds)
{
	if (!paint || !paint->use_color_filter)
		return gsk_render_node_ref(child);
	if (!paint->filter_by_mode)
		return gsk_color_matrix_node_new(child, &paint->color_matrix, &paint->color_offset);
	graphene_rect_t R;
	if (bounds)
		R = *bounds;
	else
		gsk_render_node_get_bounds(child, &R);
	return mode_node(paint->filter_mode, &paint->filter_color, child, &R);
}

void tl_paint_append_filtered(GdkSnapshot *snapshot, const struct AndroidPaint *paint, GskRenderNode *child, const graphene_rect_t *bounds)
{
	if (!child)
		return;
	GskRenderNode *n = tl_paint_filter_node(paint, child, bounds);
	gtk_snapshot_append_node(GTK_SNAPSHOT(snapshot), n);
	gsk_render_node_unref(n);
}

/* ---- Paint.setColorFilter for blend-mode filters ------------------------------------------------- */

/* mode: SkBlendMode of a PorterDuffColorFilter/BlendModeColorFilter, -1: no filter; color: ARGB */
JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1color_1filter_1mode(JNIEnv *env, jclass clazz, jlong paint_ptr, jint mode, jint color)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (mode < 0 || mode > 28) {
		paint->use_color_filter = FALSE;
		paint->filter_by_mode = FALSE;
		return;
	}
	const float ca = ((color >> 24) & 0xFF) / 255.f;
	const float c[3] = { ((color >> 16) & 0xFF) / 255.f, ((color >> 8) & 0xFF) / 255.f, ((color >> 0) & 0xFF) / 255.f };
	paint->filter_color = (GdkRGBA){ c[0], c[1], c[2], ca };
	paint->filter_mode = mode;
	paint->use_color_filter = TRUE;
	/* the affine modes as a colour matrix on the un-premultiplied pixel u with alpha a (C: the
	 * un-premultiplied filter colour, Ca its alpha) */
	float diag[4] = { 0, 0, 0, ca }; /* SRC_IN: (C, Ca * a) */
	float off[4] = { c[0], c[1], c[2], 0 };
	switch (mode) {
	case 0: /* CLEAR */
		diag[3] = 0;
		off[0] = off[1] = off[2] = 0;
		break;
	case 1: /* SRC: the colour everywhere */
		diag[3] = 0;
		off[3] = ca;
		break;
	case 2: /* DST */
		diag[0] = diag[1] = diag[2] = diag[3] = 1;
		off[0] = off[1] = off[2] = 0;
		break;
	case 3: /* SRC_OVER with an opaque colour is SRC */
		if (ca < 1.f)
			goto by_mode;
		diag[3] = 0;
		off[3] = 1;
		break;
	case 5: /* SRC_IN */
		break;
	case 6: /* DST_IN: (u, a * Ca) */
		diag[0] = diag[1] = diag[2] = 1;
		off[0] = off[1] = off[2] = 0;
		break;
	case 7: /* SRC_OUT: (C, Ca * (1 - a)) */
		diag[3] = -ca;
		off[3] = ca;
		break;
	case 8: /* DST_OUT: (u, a * (1 - Ca)) */
		diag[0] = diag[1] = diag[2] = 1;
		off[0] = off[1] = off[2] = 0;
		diag[3] = 1.f - ca;
		break;
	case 9: /* SRC_ATOP: (C * Ca + u * (1 - Ca), a) */
		for (int i = 0; i < 3; i++) {
			diag[i] = 1.f - ca;
			off[i] = ca * c[i];
		}
		diag[3] = 1;
		break;
	case 13: /* MODULATE (PorterDuff.Mode.MULTIPLY): (C * u, Ca * a) */
		for (int i = 0; i < 3; i++) {
			diag[i] = c[i];
			off[i] = 0;
		}
		break;
	default:
		goto by_mode;
	}
	/* clang-format off */
	graphene_matrix_init_from_float(&paint->color_matrix, (float[]){
		diag[0], 0, 0, 0,
		0, diag[1], 0, 0,
		0, 0, diag[2], 0,
		0, 0, 0, diag[3],
	});
	/* clang-format on */
	graphene_vec4_init(&paint->color_offset, off[0], off[1], off[2], off[3]);
	paint->filter_by_mode = FALSE;
	return;
by_mode:
	paint->filter_by_mode = TRUE;
}
