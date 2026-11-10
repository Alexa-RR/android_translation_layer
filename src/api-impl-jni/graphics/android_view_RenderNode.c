#include <math.h>
#include <gsk/gsk.h>
#include <gtk/gtk.h>

#include "../defines.h"

#include "../generated_headers/android_view_RenderNode.h"

JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeCreateSnapshot(JNIEnv *env, jobject this)
{
	return _INTPTR(gtk_snapshot_new());
}

JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeCreateNode(JNIEnv *env, jobject this, jlong snapshot_ptr)
{
	GskRenderNode *node = gtk_snapshot_free_to_node(GTK_SNAPSHOT(_PTR(snapshot_ptr)));
	if (!node)
		node = gsk_container_node_new(NULL, 0);
	return _INTPTR(node);
}

#define TL_PATCH1(GET, MAKE) { \
			GskRenderNode *child = patch_node(GET(node), old_child, new_child); \
			new_node = child != GET(node) ? (MAKE) : gsk_render_node_ref(node); \
			gsk_render_node_unref(child); \
			break; \
		}

static GskRenderNode *patch_node(GskRenderNode *node, GskRenderNode *old_child, GskRenderNode *new_child)
{
	if (node == old_child)
		return gsk_render_node_ref(new_child);
	GskRenderNode *new_node;
	GskRenderNodeType type = gsk_render_node_get_node_type(node);
	switch (type) {
		case GSK_CONTAINER_NODE: {
			guint n_children = gsk_container_node_get_n_children(node);
			GskRenderNode *children[n_children];
			gboolean modified = FALSE;
			for (guint i = 0; i < n_children; i++) {
				children[i] = patch_node(gsk_container_node_get_child(node, i), old_child, new_child);
				modified |= children[i] != gsk_container_node_get_child(node, i);
			}
			if (modified)
				new_node = gsk_container_node_new(children, n_children);
			else
				new_node = gsk_render_node_ref(node);
			for (guint i = 0; i < n_children; i++)
				gsk_render_node_unref(children[i]);
			break;
		}
		case GSK_TRANSFORM_NODE: {
			GskRenderNode *child = patch_node(gsk_transform_node_get_child(node), old_child, new_child);
			if (child != gsk_transform_node_get_child(node))
				new_node = gsk_transform_node_new(child, gsk_transform_node_get_transform(node));
			else
				new_node = gsk_render_node_ref(node);
			gsk_render_node_unref(child);
			break;
		}
		/* teslalinux-gfx: every node type with children, so a child under any of them is patched */
		case GSK_OPACITY_NODE:
			TL_PATCH1(gsk_opacity_node_get_child, gsk_opacity_node_new(child, gsk_opacity_node_get_opacity(node)))
		case GSK_COLOR_MATRIX_NODE:
			TL_PATCH1(gsk_color_matrix_node_get_child, gsk_color_matrix_node_new(child, gsk_color_matrix_node_get_color_matrix(node), gsk_color_matrix_node_get_color_offset(node)))
		case GSK_ROUNDED_CLIP_NODE:
			TL_PATCH1(gsk_rounded_clip_node_get_child, gsk_rounded_clip_node_new(child, gsk_rounded_clip_node_get_clip(node)))
		case GSK_FILL_NODE:
			TL_PATCH1(gsk_fill_node_get_child, gsk_fill_node_new(child, gsk_fill_node_get_path(node), gsk_fill_node_get_fill_rule(node)))
		case GSK_STROKE_NODE:
			TL_PATCH1(gsk_stroke_node_get_child, gsk_stroke_node_new(child, gsk_stroke_node_get_path(node), gsk_stroke_node_get_stroke(node)))
		case GSK_BLUR_NODE:
			TL_PATCH1(gsk_blur_node_get_child, gsk_blur_node_new(child, gsk_blur_node_get_radius(node)))
		case GSK_DEBUG_NODE:
			TL_PATCH1(gsk_debug_node_get_child, gsk_debug_node_new(child, g_strdup(gsk_debug_node_get_message(node))))
		case GSK_REPEAT_NODE: {
			graphene_rect_t rb;
			gsk_render_node_get_bounds(node, &rb);
			TL_PATCH1(gsk_repeat_node_get_child, gsk_repeat_node_new(&rb, child, gsk_repeat_node_get_child_bounds(node)))
		}
		case GSK_SHADOW_NODE: {
			gsize ns = gsk_shadow_node_get_n_shadows(node);
			GskShadow shadows[ns > 0 ? ns : 1];
			for (gsize i = 0; i < ns; i++)
				shadows[i] = *gsk_shadow_node_get_shadow(node, i);
			TL_PATCH1(gsk_shadow_node_get_child, gsk_shadow_node_new(child, shadows, ns))
		}
		case GSK_MASK_NODE: {
			GskRenderNode *src = patch_node(gsk_mask_node_get_source(node), old_child, new_child);
			GskRenderNode *msk = patch_node(gsk_mask_node_get_mask(node), old_child, new_child);
			if (src != gsk_mask_node_get_source(node) || msk != gsk_mask_node_get_mask(node))
				new_node = gsk_mask_node_new(src, msk, gsk_mask_node_get_mask_mode(node));
			else
				new_node = gsk_render_node_ref(node);
			gsk_render_node_unref(src);
			gsk_render_node_unref(msk);
			break;
		}
		case GSK_BLEND_NODE: {
			GskRenderNode *bot = patch_node(gsk_blend_node_get_bottom_child(node), old_child, new_child);
			GskRenderNode *top = patch_node(gsk_blend_node_get_top_child(node), old_child, new_child);
			if (bot != gsk_blend_node_get_bottom_child(node) || top != gsk_blend_node_get_top_child(node))
				new_node = gsk_blend_node_new(bot, top, gsk_blend_node_get_blend_mode(node));
			else
				new_node = gsk_render_node_ref(node);
			gsk_render_node_unref(bot);
			gsk_render_node_unref(top);
			break;
		}
		case GSK_CROSS_FADE_NODE: {
			GskRenderNode *a = patch_node(gsk_cross_fade_node_get_start_child(node), old_child, new_child);
			GskRenderNode *b = patch_node(gsk_cross_fade_node_get_end_child(node), old_child, new_child);
			if (a != gsk_cross_fade_node_get_start_child(node) || b != gsk_cross_fade_node_get_end_child(node))
				new_node = gsk_cross_fade_node_new(a, b, gsk_cross_fade_node_get_progress(node));
			else
				new_node = gsk_render_node_ref(node);
			gsk_render_node_unref(a);
			gsk_render_node_unref(b);
			break;
		}
		case GSK_CLIP_NODE: {
			GskRenderNode *child = patch_node(gsk_clip_node_get_child(node), old_child, new_child);
			if (child != gsk_clip_node_get_child(node))
				new_node = gsk_clip_node_new(child, gsk_clip_node_get_clip(node));
			else
				new_node = gsk_render_node_ref(node);
			gsk_render_node_unref(child);
			break;
		}
		default:
			new_node = gsk_render_node_ref(node);
	}
	return new_node;
}

JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativePatchNode(JNIEnv *env, jobject this, jlong node_ptr, jlong old_child_ptr, jlong new_child_ptr)
{
	GskRenderNode *old_node = _PTR(node_ptr);
	GskRenderNode *new_node = patch_node(old_node, _PTR(old_child_ptr), _PTR(new_child_ptr));
	gsk_render_node_unref(old_node);
	return _INTPTR(new_node);
}

JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeTransform(JNIEnv *env, jobject this, jlong node_ptr, jfloat scale_x, jfloat scale_y, jfloat translate_x, jfloat translate_y, jfloat rotate, jfloat pivot_x, jfloat pivot_y)
{
	GskRenderNode *node = _PTR(node_ptr);
	GskTransform *transform = NULL;
	GskRenderNode *transformed_node;
	transform = gsk_transform_translate(transform, &GRAPHENE_POINT_INIT(-pivot_x, -pivot_y));
	transform = gsk_transform_scale(transform, scale_x, scale_y);
	transform = gsk_transform_rotate(transform, rotate);
	transform = gsk_transform_translate(transform, &GRAPHENE_POINT_INIT(translate_x + pivot_x, translate_y + pivot_y));
	if (transform) {
		transformed_node = gsk_transform_node_new(node, transform);
		gsk_transform_unref(transform);
	} else {
		transformed_node = gsk_render_node_ref(node);
	}
	return _INTPTR(transformed_node);
}

JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeClip(JNIEnv *env, jobject this, jlong node_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom)
{
	GskRenderNode *node = _PTR(node_ptr);
	GskRenderNode *clipped_node = gsk_clip_node_new(node, &GRAPHENE_RECT_INIT(left, top, right - left, bottom - top));
	gsk_render_node_unref(node);
	return _INTPTR(clipped_node);
}

JNIEXPORT void JNICALL Java_android_view_RenderNode_nativeUnref(JNIEnv *env, jobject this, jlong node_ptr)
{
	if (node_ptr != 0)
		gsk_render_node_unref(_PTR(node_ptr));
}

JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeAddStubNode(JNIEnv *env, jobject this, jlong snapshot_ptr)
{
	GtkSnapshot *snapshot = _PTR(snapshot_ptr);
	GdkRGBA rgba = {
		.red = 0.f,
		.green = 0.f,
		.blue = 0.f,
		.alpha = 0.f,
	};
	GskRenderNode *stub_node = gsk_color_node_new(&rgba, &GRAPHENE_RECT_INIT(0.0f, 0.0f, 0.0f, 0.0f));
	gtk_snapshot_append_node(snapshot, stub_node);
	gsk_render_node_unref(stub_node);
	return _INTPTR(stub_node);
}

/* teslalinux: rectangular clip; the input keeps its reference (the clip node takes its own) */
JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeClipKeep(JNIEnv *env, jobject this, jlong node_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom)
{
	GskRenderNode *node = _PTR(node_ptr);
	return _INTPTR(gsk_clip_node_new(node, &GRAPHENE_RECT_INIT(left, top, right - left, bottom - top)));
}

/* teslalinux: rounded-rectangle clip (one radius for all corners); the input keeps its reference */
JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeRoundedClipKeep(JNIEnv *env, jobject this, jlong node_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom, jfloat radius)
{
	GskRenderNode *node = _PTR(node_ptr);
	GskRoundedRect rect = {
		.bounds = GRAPHENE_RECT_INIT(left, top, right - left, bottom - top),
		.corner = { { radius, radius }, { radius, radius }, { radius, radius }, { radius, radius } },
	};
	return _INTPTR(gsk_rounded_clip_node_new(node, &rect));
}

/* teslalinux: layer alpha (HWUI draws the node's content at this opacity); the input keeps its reference */
JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeOpacityKeep(JNIEnv *env, jobject this, jlong node_ptr, jfloat alpha)
{
	GskRenderNode *node = _PTR(node_ptr);
	if (alpha >= 1.f)
		return _INTPTR(gsk_render_node_ref(node));
	return _INTPTR(gsk_opacity_node_new(node, alpha < 0.f ? 0.f : alpha));
}

/* teslalinux: the HWUI layer matrix (RenderProperties::updateMatrix); the input keeps its reference */
JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeLayerTransformKeep(JNIEnv *env, jobject this, jlong node_ptr,
		jfloat scale_x, jfloat scale_y, jfloat translate_x, jfloat translate_y, jfloat rotate_z,
		jfloat rotate_x, jfloat rotate_y, jfloat camera_distance_inches, jfloat pivot_x, jfloat pivot_y)
{
	GskRenderNode *node = _PTR(node_ptr);
	/* GskTransform chains read like CSS: the first operation is the outermost one */
	GskTransform *t = gsk_transform_translate(NULL, &GRAPHENE_POINT_INIT(translate_x + pivot_x, translate_y + pivot_y));
	if (rotate_x != 0.f || rotate_y != 0.f) {
		float inches = camera_distance_inches != 0.f ? fabsf(camera_distance_inches) : 8.f;
		t = gsk_transform_perspective(t, 72.f * inches);
		/* Skia's Sk3DView rotates about +X and -Y in screen coordinates (y points down) */
		t = gsk_transform_rotate_3d(t, rotate_x, graphene_vec3_x_axis());
		t = gsk_transform_rotate_3d(t, -rotate_y, graphene_vec3_y_axis());
	}
	t = gsk_transform_rotate(t, rotate_z);
	t = gsk_transform_scale(t, scale_x, scale_y);
	t = gsk_transform_translate(t, &GRAPHENE_POINT_INIT(-pivot_x, -pivot_y));
	if (!t)
		return _INTPTR(gsk_render_node_ref(node));
	if (gsk_transform_get_category(t) == GSK_TRANSFORM_CATEGORY_IDENTITY) {
		gsk_transform_unref(t);
		return _INTPTR(gsk_render_node_ref(node));
	}
	GskRenderNode *transformed = gsk_transform_node_new(node, t);
	gsk_transform_unref(t);
	return _INTPTR(transformed);
}

/* teslalinux: path clip (native-rendernode-pathclip.sh); the input keeps its reference */
JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativePathClipKeep(JNIEnv *env, jclass clazz, jlong node_ptr, jlong path_ptr, jboolean even_odd)
{
	GskRenderNode *node = _PTR(node_ptr);
	GskPath *path = _PTR(path_ptr);
	if (!node)
		return 0;
	if (!path)
		return _INTPTR(gsk_render_node_ref(node));
	return _INTPTR(gsk_fill_node_new(node, path, even_odd ? GSK_FILL_RULE_EVEN_ODD : GSK_FILL_RULE_WINDING));
}
