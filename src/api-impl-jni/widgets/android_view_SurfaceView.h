#include <gtk/gtk.h>

G_DECLARE_FINAL_TYPE(SurfaceViewWidget, surface_view_widget, SURFACE_VIEW, WIDGET, GtkWidget)

struct _SurfaceViewWidget {
	GtkWidget parent_instance;
	GdkTexture *texture;
	gboolean needs_flip;
	void (*frame_callback)(SurfaceViewWidget *surface_view_widget);
	gpointer frame_callback_data;
	GtkWidget *offload_content; /* tl_surface_offload: the child that draws the frame, or NULL */
	gboolean opaque;            /* SurfaceHolder.setFormat: the surface has no alpha (AOSP's default) */
};

struct _SurfaceViewWidgetClass {
	GtkWidgetClass parent_class;
};

void surface_view_widget_set_texture(SurfaceViewWidget *surface_view_widget, GdkTexture *texture, gboolean needs_flip);
