#include "pango/pango-layout.h"
#include <gdk/gdk.h>
#include <gsk/gsk.h>
#include <pango/pango.h>

struct AndroidPaint {
	GdkRGBA color;
	GskStroke *gsk_stroke;
	PangoFontDescription *font;
	PangoAlignment alignment;
	float letter_spacing; /* teslalinux: em units, as Paint.setLetterSpacing */
	float stroke_width; /* teslalinux: as set (0: hairline); the GSK stroke is >= 1 unit wide */
	graphene_matrix_t color_matrix;
	graphene_vec4_t color_offset;
	/* teslalinux: with filter_by_mode the colour filter is a blend mode (SkBlendMode numbers) with an
	 * un-premultiplied colour (atl_colorfilter.c); otherwise color_matrix/color_offset (on
	 * un-premultiplied colours) is the filter. Either only counts with use_color_filter. */
	int filter_mode;
	GdkRGBA filter_color;
	bool is_fill : 1;
	bool is_stroke : 1;
	bool use_color_filter : 1;
	bool filter_by_mode : 1;
};

/* atl_colorfilter.c: the paint's colour filter on a solid colour and on drawn content */
void tl_paint_filtered_color(const struct AndroidPaint *paint, GdkRGBA *out);
GskRenderNode *tl_paint_filter_node(const struct AndroidPaint *paint, GskRenderNode *child, const graphene_rect_t *bounds);
void tl_paint_append_filtered(GdkSnapshot *snapshot, const struct AndroidPaint *paint, GskRenderNode *child, const graphene_rect_t *bounds);
