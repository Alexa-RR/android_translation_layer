#include <arpa/inet.h>
#include <math.h>
#include <graphene.h>
#include <gtk/gtk.h>

#include "NinePatchPaintable.h"

enum {
	// The 9 patch segment is not a solid color.
	NO_COLOR = 0x00000001,
	// The 9 patch segment is completely transparent.
	TRANSPARENT_COLOR = 0x00000000
};

/*
 * The edges of the sections along one axis at the target size, as Skia's lattice places them: the
 * stretchable sections share what the fixed ones leave, and a target smaller than the fixed sections
 * shrinks them proportionally (the stretchable ones then vanish). With scale > 0 the edges are snapped
 * to whole pixels of the canvas root, where edge e lies at offset + scale * e: sections then meet on
 * pixel boundaries, without the seams of two antialiased edges sharing a pixel.
 */
static void ninepatch_axis(const int32_t *divs, int ndivs, int size, int stretchy, double target, double scale, double offset, float *edge)
{
	const double fixed = size - stretchy;
	double fixed_factor = 1, stretch_factor = 0;
	if (target < fixed)
		fixed_factor = fixed > 0 ? target / fixed : 0;
	else if (stretchy > 0)
		stretch_factor = (target - fixed) / stretchy;
	double pos = 0;
	edge[0] = 0;
	for (int i = 0; i <= ndivs; i++) {
		const int start = i ? divs[i - 1] : 0;
		const int end = (i == ndivs) ? size : divs[i];
		if (end > start)
			pos += (end - start) * ((i % 2) ? stretch_factor : fixed_factor); // odd sections are stretchable
		edge[i + 1] = pos;
	}
	if (stretchy > 0 || target < fixed)
		edge[ndivs + 1] = target;
	if (scale > 0)
		for (int i = 0; i <= ndivs + 1; i++)
			edge[i] = (round(offset + scale * edge[i]) - offset) / scale;
}

void ninepatch_paintable_draw(NinePatchPaintable *ninepatch, GdkSnapshot *snapshot, double width, double height,
                              double scale_x, double offset_x, double scale_y, double offset_y)
{
	struct Res_png_9patch *chunk = ninepatch->chunk;
	if (!chunk)
		return;
	const int32_t *xDivs = (void *)chunk + chunk->xDivsOffset;
	const int32_t *yDivs = (void *)chunk + chunk->yDivsOffset;
	const int32_t *color = (void *)chunk + chunk->colorsOffset;
	float xedge[chunk->numXDivs + 2], yedge[chunk->numYDivs + 2];
	ninepatch_axis(xDivs, chunk->numXDivs, ninepatch->width, ninepatch->strechy_width, width, scale_x, offset_x, xedge);
	ninepatch_axis(yDivs, chunk->numYDivs, ninepatch->height, ninepatch->strechy_height, height, scale_y, offset_y, yedge);
	const int texture_width = gdk_texture_get_width(ninepatch->texture);
	const int texture_height = gdk_texture_get_height(ninepatch->texture);

	if (ninepatch->tint) {
		graphene_matrix_t color_matrix;
		graphene_vec4_t color_offset;
		/* clang-format off */
		graphene_matrix_init_from_float(&color_matrix, (float[]){
			0, 0, 0, 0,
			0, 0, 0, 0,
			0, 0, 0, 0,
			0, 0, 0, ((ninepatch->tint >> 24) & 0xFF) / 255.f,
		});
		/* clang-format on */
		graphene_vec4_init(&color_offset, ((ninepatch->tint >> 16) & 0xFF) / 255.f, ((ninepatch->tint >> 8) & 0xFF) / 255.f, ((ninepatch->tint >> 0) & 0xFF) / 255.f, 0);
		gtk_snapshot_push_color_matrix(snapshot, &color_matrix, &color_offset);
	}
	/* one colour hint per section, row by row, for every section that is not empty in the source
	 * image (aapt leaves out an empty first or last column or row); a section that has no size at this
	 * target keeps its hint */
	for (int j = 0; j <= chunk->numYDivs; j++) {
		const int ydiv_start = j ? yDivs[j - 1] : 0;
		const int ydiv_end = (j == chunk->numYDivs) ? ninepatch->height : yDivs[j];
		if (ydiv_end <= ydiv_start)
			continue;
		const float y0 = yedge[j], y1 = yedge[j + 1];
		const float factor_y = (y1 - y0) / (ydiv_end - ydiv_start);
		for (int i = 0; i <= chunk->numXDivs; i++) {
			const int xdiv_start = i ? xDivs[i - 1] : 0;
			const int xdiv_end = (i == chunk->numXDivs) ? ninepatch->width : xDivs[i];
			if (xdiv_end <= xdiv_start)
				continue;
			const float x0 = xedge[i], x1 = xedge[i + 1];
			if (x1 > x0 && y1 > y0) {
				const graphene_rect_t rect = GRAPHENE_RECT_INIT(x0, y0, x1 - x0, y1 - y0);
				if (*color == NO_COLOR) {
					const float factor_x = (x1 - x0) / (xdiv_end - xdiv_start);
					gtk_snapshot_push_clip(snapshot, &rect);
					gtk_snapshot_append_texture(snapshot, ninepatch->texture,
					                            &GRAPHENE_RECT_INIT(x0 - xdiv_start * factor_x, y0 - ydiv_start * factor_y,
					                                                texture_width * factor_x, texture_height * factor_y));
					gtk_snapshot_pop(snapshot);
				} else if (*color != TRANSPARENT_COLOR) {
					const GdkRGBA rgba = {
						.red = (*color >> 16 & 0xFF) / 255.f,
						.green = (*color >> 8 & 0xFF) / 255.f,
						.blue = (*color & 0xFF) / 255.f,
						.alpha = (*color >> 24 & 0xFF) / 255.f,
					};
					gtk_snapshot_append_color(snapshot, &rgba, &rect);
				}
			}
			color++;
		}
	}
	if (ninepatch->tint)
		gtk_snapshot_pop(snapshot);
}

static void ninepatch_paintable_snapshot(GdkPaintable *paintable, GdkSnapshot *snapshot, double width, double height)
{
	ninepatch_paintable_draw(NINEPATCH_PAINTABLE(paintable), snapshot, width, height, 0, 0, 0, 0);
}

static GdkPaintableFlags ninepatch_paintable_get_flags(GdkPaintable *paintable)
{
	return GDK_PAINTABLE_STATIC_CONTENTS | GDK_PAINTABLE_STATIC_SIZE;
}

static void ninepatch_paintable_init(NinePatchPaintable *ninepatch_paintable)
{
}

static void ninepatch_paintable_paintable_init(GdkPaintableInterface *iface)
{
	iface->snapshot = ninepatch_paintable_snapshot;
	iface->get_flags = ninepatch_paintable_get_flags;
}

static void ninepatch_paintable_class_init(NinePatchPaintableClass *class)
{
}

G_DEFINE_TYPE_WITH_CODE(NinePatchPaintable, ninepatch_paintable, G_TYPE_OBJECT,
                        G_IMPLEMENT_INTERFACE(GDK_TYPE_PAINTABLE, ninepatch_paintable_paintable_init))

GdkPaintable *ninepatch_paintable_new(struct Res_png_9patch *chunk, uint32_t chunk_size, GdkTexture *texture)
{
	int width = 0;
	int height = 0;
	int strechy_width = 0;
	int strechy_height = 0;
	int i;

	if (chunk->wasDeserialized) {
		chunk->xDivsOffset = sizeof(struct Res_png_9patch);
		chunk->yDivsOffset = chunk->xDivsOffset + chunk->numXDivs * sizeof(int32_t);
		chunk->colorsOffset = chunk->yDivsOffset + chunk->numYDivs * sizeof(int32_t);
	}

	// verify that that all arrays are fully inside the chunk bounds
	if (chunk->xDivsOffset > chunk_size || chunk->yDivsOffset > chunk_size || chunk->colorsOffset > chunk_size
	    || chunk->numXDivs > (chunk_size - chunk->xDivsOffset) / sizeof(int32_t)
	    || chunk->numYDivs > (chunk_size - chunk->yDivsOffset) / sizeof(int32_t)
	    || chunk->numColors > (chunk_size - chunk->colorsOffset) / sizeof(int32_t))
		return NULL;

	int32_t *xDivs = (void *)chunk + chunk->xDivsOffset;
	int32_t *yDivs = (void *)chunk + chunk->yDivsOffset;
	int32_t *colors = (void *)chunk + chunk->colorsOffset;

	if (!chunk->wasDeserialized) {
		for (i = 0; i < chunk->numXDivs; i++)
			xDivs[i] = ntohl(xDivs[i]);
		for (i = 0; i < chunk->numYDivs; i++)
			yDivs[i] = ntohl(yDivs[i]);
		for (i = 0; i < chunk->numColors; i++)
			colors[i] = ntohl(colors[i]);
	}

	width = gdk_texture_get_width(texture);
	height = gdk_texture_get_height(texture);

	for (i = 1; i < chunk->numXDivs + 1; i += 2)
		strechy_width += (i == chunk->numXDivs ? width : xDivs[i]) - xDivs[i - 1];
	for (i = 1; i < chunk->numYDivs + 1; i += 2)
		strechy_height += (i == chunk->numYDivs ? height : yDivs[i]) - yDivs[i - 1];

	NinePatchPaintable *ninepatch = NINEPATCH_PAINTABLE(g_object_new(ninepatch_paintable_get_type(), NULL));
	ninepatch->chunk = chunk;
	ninepatch->width = width;
	ninepatch->height = height;
	ninepatch->strechy_width = strechy_width;
	ninepatch->strechy_height = strechy_height;
	ninepatch->texture = texture;
	return GDK_PAINTABLE(ninepatch);
}
