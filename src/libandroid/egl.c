#define _GNU_SOURCE /* RTLD_DEFAULT */
#include <gdk/wayland/gdkwayland.h>
#include <gdk/x11/gdkx.h>
#include <gtk/gtk.h>

#include <dlfcn.h>
#include <unistd.h>
#include <wayland-egl.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <GL/gl.h>
#include <GLES2/gl2.h>

// FIXME: put the header in a common place
#include "../api-impl-jni/defines.h"
#include "../api-impl-jni/widgets/android_view_SurfaceView.h"

#include "gl_passes.h"
#include "native_window.h"

extern GtkWindow *window; // TODO: how do we get rid of this? the app won't pass anything useful to eglGetDisplay

static GHashTable *egl_surface_hashtable;
#define tl_table_lookup(table, key) ((table) ? g_hash_table_lookup((table), (key)) : NULL)

/* environment switches read on every swap and framebuffer bind: read once (getenv walks the whole
 * environment, and a map frame binds framebuffer 0 many times) */
static int tl_env_flag(const char *name, int *cache)
{
	if (*cache < 0)
		*cache = getenv(name) != NULL;
	return *cache;
}
static int tl_direct_egl(void)
{
	static int cache = -1;
	return tl_env_flag("ATL_DIRECT_EGL", &cache);
}
static int tl_debug_surface(void)
{
	static int cache = -1;
	return tl_env_flag("ATL_DEBUG_SURFACE", &cache);
}

/* Wayland has no pbuffers: bionic_eglCreatePbufferSurface makes a window surface on a wl_surface that
 * is never given a role (never shown); these are kept per pbuffer to be destroyed with it */
struct tl_pbuffer_window {
	struct wl_egl_window *egl_window;
	struct wl_surface *wayland_surface;
};
static GHashTable *pbuffer_window_hashtable;

// temporary for debugging
static void PrintConfigAttributes(EGLDisplay display, EGLConfig config)
{
	EGLint value;
	printf("-------------------------------------------------------------------------------\n");
	eglGetConfigAttrib(display, config, EGL_CONFIG_ID, &value);
	printf("EGL_CONFIG_ID %d\n", value);

	eglGetConfigAttrib(display, config, EGL_BUFFER_SIZE, &value);
	printf("EGL_BUFFER_SIZE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_RED_SIZE, &value);
	printf("EGL_RED_SIZE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_GREEN_SIZE, &value);
	printf("EGL_GREEN_SIZE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_BLUE_SIZE, &value);
	printf("EGL_BLUE_SIZE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_ALPHA_SIZE, &value);
	printf("EGL_ALPHA_SIZE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_DEPTH_SIZE, &value);
	printf("EGL_DEPTH_SIZE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_STENCIL_SIZE, &value);
	printf("EGL_STENCIL_SIZE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_SAMPLE_BUFFERS, &value);
	printf("EGL_SAMPLE_BUFFERS %d\n", value);
	eglGetConfigAttrib(display, config, EGL_SAMPLES, &value);
	printf("EGL_SAMPLES %d\n", value);

	eglGetConfigAttrib(display, config, EGL_CONFIG_CAVEAT, &value);
	switch (value) {
		case EGL_NONE:
			printf("EGL_CONFIG_CAVEAT EGL_NONE\n");
			break;
		case EGL_SLOW_CONFIG:
			printf("EGL_CONFIG_CAVEAT EGL_SLOW_CONFIG\n");
			break;
	}

	eglGetConfigAttrib(display, config, EGL_MAX_PBUFFER_WIDTH, &value);
	printf("EGL_MAX_PBUFFER_WIDTH %d\n", value);
	eglGetConfigAttrib(display, config, EGL_MAX_PBUFFER_HEIGHT, &value);
	printf("EGL_MAX_PBUFFER_HEIGHT %d\n", value);
	eglGetConfigAttrib(display, config, EGL_MAX_PBUFFER_PIXELS, &value);
	printf("EGL_MAX_PBUFFER_PIXELS %d\n", value);
	eglGetConfigAttrib(display, config, EGL_NATIVE_RENDERABLE, &value);
	printf("EGL_NATIVE_RENDERABLE %s \n", (value ? "true" : "false"));
	eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_ID, &value);
	printf("EGL_NATIVE_VISUAL_ID %d\n", value);
	eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_TYPE, &value);
	printf("EGL_NATIVE_VISUAL_TYPE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_RENDERABLE_TYPE, &value);
	printf("EGL_RENDERABLE_TYPE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_SURFACE_TYPE, &value);
	printf("EGL_SURFACE_TYPE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_TRANSPARENT_TYPE, &value);
	printf("EGL_TRANSPARENT_TYPE %d\n", value);
	printf("-------------------------------------------------------------------------------\n");
}

// this is an extension that only android implements, we can hopefully get away with just stubbing it
EGLBoolean bionic_eglPresentationTimeANDROID(EGLDisplay dpy, EGLSurface surface, EGLnsecsANDROID time)
{
	return EGL_TRUE;
}

/* teslalinux (native-egl-swapchain.sh): glBufferData without llvmpipe's rasterizer wait */
static int tl_gl_is_llvmpipe(void)
{
	static int is_llvmpipe = -1;
	if (is_llvmpipe < 0) {
		const char *renderer = (const char *)glGetString(GL_RENDERER);
		if (!renderer)
			return 0; /* no current context: decide on a later call */
		is_llvmpipe = strstr(renderer, "llvmpipe") != NULL;
	}
	return is_llvmpipe;
}

void bionic_glBufferData(GLenum target, GLsizeiptr size, const void *data, GLenum usage)
{
	if (data && size > 0 && tl_gl_is_llvmpipe()) {
		glBufferData(target, size, NULL, usage); /* new storage; the queued draws keep the old one */
		glBufferSubData(target, 0, size, data);
		return;
	}
	glBufferData(target, size, data, usage);
}

/* the same entry points an app's direct calls reach: bionic_translation resolves an import or a
 * dlsym() of NAME to bionic_NAME when one is loaded (ATL's here, or a preloaded one), and so does
 * this; before, a pointer from here skipped ATL's wrappers (the default framebuffer of
 * bionic_glBindFramebuffer, gl_passes.c's tracking) */
void (*bionic_eglGetProcAddress(char const *procname))(void)
{
	void *wrapper = NULL;
	if (procname) {
		char name[256];
		if (snprintf(name, sizeof name, "bionic_%s", procname) < (int)sizeof name)
			wrapper = dlsym(RTLD_DEFAULT, name);
	}
	tl_gl_proc_requested(procname, wrapper);
	if (wrapper)
		return (void (*)(void))wrapper;
	return eglGetProcAddress(procname);
}

EGLContext bionic_eglCreateContext(EGLDisplay display, EGLConfig config, EGLContext share_context, const EGLint *attrib_list)
{
	EGLContext context = eglCreateContext(display, config, share_context, attrib_list);
	if (context != EGL_NO_CONTEXT)
		tl_gl_context_created(context, share_context);
	return context;
}

static void tl_forget_context(EGLContext context);
EGLBoolean bionic_eglDestroyContext(EGLDisplay display, EGLContext context)
{
	EGLBoolean ret = eglDestroyContext(display, context);
	if (ret) {
		tl_gl_context_destroyed(context);
		tl_forget_context(context); /* a later context can get the same handle */
	}
	return ret;
}

EGLBoolean bionic_eglReleaseThread(void)
{
	tl_gl_before_make_current();
	EGLBoolean ret = eglReleaseThread();
	tl_gl_after_make_current(eglGetCurrentContext());
	return ret;
}

EGLBoolean bionic_eglWaitGL(void)
{
	tl_gl_flush_point();
	return eglWaitGL();
}

EGLBoolean bionic_eglWaitClient(void)
{
	tl_gl_flush_point();
	return eglWaitClient();
}

EGLDisplay bionic_eglGetDisplay(EGLNativeDisplayType native_display)
{
	/*
	 * On android, at least SDL passes 0 (EGL_DISPLAY_DEFAULT) to eglGetDisplay and uses the resulting display.
	 * We obviously want to make the app use the correct display, which may happen to be a different one
	 * than the "default" display (especially on Wayland)
	 */
	GdkDisplay *display = gtk_root_get_display(GTK_ROOT(window));

	if (GDK_IS_WAYLAND_DISPLAY(display)) {
		struct wl_display *wl_display = gdk_wayland_display_get_wl_display(display);
		return eglGetPlatformDisplay(EGL_PLATFORM_WAYLAND_KHR, wl_display, NULL);
	} else if (GDK_IS_X11_DISPLAY(display)) {
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
		Display *x11_display = gdk_x11_display_get_xdisplay(display);
#pragma GCC diagnostic pop
		return eglGetPlatformDisplay(EGL_PLATFORM_X11_KHR, x11_display, NULL);
	} else {
		return NULL;
	}
}

EGLBoolean bionic_eglChooseConfig(EGLDisplay display, EGLint *attrib_list, EGLConfig *configs, EGLint config_size, EGLint *num_config)
{
	GdkDisplay *gdk_display = gtk_root_get_display(GTK_ROOT(window));

	if (GDK_IS_X11_DISPLAY(gdk_display)) {
		/* X11 supports pbuffers just fine */
		return eglChooseConfig(display, attrib_list, configs, config_size, num_config);
	} else {
		bool has_pbuffer_bit = false;
		int attrib_list_size = 0;
		for (EGLint *attr = attrib_list; *attr != EGL_NONE; attr += 2) {
			if (*attr == EGL_SURFACE_TYPE && (*(attr + 1) & EGL_PBUFFER_BIT) && *(attr + 1) != EGL_DONT_CARE) {
				has_pbuffer_bit = true;
			}
			attrib_list_size += 2;
		}
		attrib_list_size += 1; // for EGL_NONE
		if (has_pbuffer_bit) {
			/* copy the list in case it's mapped read-only */
			EGLint *new_attrib_list = malloc(sizeof(EGLint) * attrib_list_size);
			memcpy(new_attrib_list, attrib_list, sizeof(EGLint) * attrib_list_size);
			for (EGLint *attr = new_attrib_list; *attr != EGL_NONE; attr += 2) {
				if (*attr == EGL_SURFACE_TYPE && *(attr + 1) != EGL_DONT_CARE) {
					*(attr + 1) &= ~EGL_PBUFFER_BIT;
					*(attr + 1) |= EGL_WINDOW_BIT;
				}
			}
			EGLBoolean ret = eglChooseConfig(display, new_attrib_list, configs, config_size, num_config);
			free(new_attrib_list);
			return ret;
		} else {
			return eglChooseConfig(display, attrib_list, configs, config_size, num_config);
		}
	}
}

EGLSurface bionic_eglCreatePbufferSurface(EGLDisplay display, EGLConfig config, EGLint const *attrib_list)
{
	GdkDisplay *gdk_display = gtk_root_get_display(GTK_ROOT(window));

	if (GDK_IS_X11_DISPLAY(gdk_display)) {
		/* X11 supports pbuffers just fine */
		return eglCreatePbufferSurface(display, config, attrib_list);
	} else {
		struct wl_compositor *wl_compositor = gdk_wayland_display_get_wl_compositor(gdk_display);
		struct wl_surface *wayland_surface = wl_compositor_create_surface(wl_compositor);
		EGLint width = 0;
		EGLint height = 0;
		EGLint *new_attrib_list = NULL;
		if (attrib_list) {
			size_t attrib_list_len = 0;
			for (EGLint *attr = (EGLint *)attrib_list; *attr != EGL_NONE; attr++)
				attrib_list_len++;
			new_attrib_list = malloc(sizeof(EGLint) * (attrib_list_len + 1));
			EGLint *new_attr_pos = new_attrib_list;
			for (EGLint *attr = (EGLint *)attrib_list; *attr != EGL_NONE; attr += 2) {
				if (*attr == EGL_WIDTH) {
					width = *(attr + 1);
				} else if (*attr == EGL_HEIGHT) {
					height = *(attr + 1);
				} else {
					*new_attr_pos = *attr;
					*(new_attr_pos + 1) = *(attr + 1);
					new_attr_pos += 2;
				}
			}
			*new_attr_pos = EGL_NONE;
		}
		/* EGL's default pbuffer size is 0x0, which wl_egl_window_create refuses: one pixel stands in */
		struct wl_egl_window *egl_window = wl_egl_window_create(wayland_surface, width > 0 ? width : 1, height > 0 ? height : 1);
		EGLSurface surface = egl_window ? eglCreateWindowSurface(display, config, (EGLNativeWindowType)egl_window, new_attrib_list) : EGL_NO_SURFACE;
		free(new_attrib_list);
		if (surface == EGL_NO_SURFACE) {
			if (egl_window)
				wl_egl_window_destroy(egl_window);
			wl_surface_destroy(wayland_surface);
			return EGL_NO_SURFACE;
		}
		/* the window and wl_surface behind it go when the pbuffer is destroyed (bionic_eglDestroySurface) */
		struct tl_pbuffer_window *pbuffer_window = g_new(struct tl_pbuffer_window, 1);
		pbuffer_window->egl_window = egl_window;
		pbuffer_window->wayland_surface = wayland_surface;
		if (!pbuffer_window_hashtable)
			pbuffer_window_hashtable = g_hash_table_new(NULL, NULL);
		g_hash_table_insert(pbuffer_window_hashtable, surface, pbuffer_window);
		return surface;
	}
}

/* after eglDestroySurface of a pbuffer: the Wayland window that stood in for it */
static void tl_destroy_pbuffer_window(EGLSurface surface)
{
	struct tl_pbuffer_window *pbuffer_window = tl_table_lookup(pbuffer_window_hashtable, surface);
	if (!pbuffer_window)
		return;
	g_hash_table_remove(pbuffer_window_hashtable, surface);
	wl_egl_window_destroy(pbuffer_window->egl_window);
	wl_surface_destroy(pbuffer_window->wayland_surface);
	g_free(pbuffer_window);
}

#define NUM_BUFFERS 4 /* teslalinux: GTK holds two frames, the app draws one ahead (native-egl-swapchain.sh) */
#define TL_MAX_GPU_FRAMES 3 /* tl_swap_gpu_throttle */

struct _ATLSurface {
	GObject parent;
	int width;
	int height;
	SurfaceViewWidget *surface_view_widget;
	int32_t framebuffer_format;
	int32_t renderbuffer_format;
	unsigned int renderbuffer_attachment;
	uint32_t renderbuffer;
	struct atl_surface_buffer {
		struct _ATLSurface *surface;
		EGLImage egl_image;
		GdkGLTextureBuilder *texture_builder;
		/* tl_surface_offload: the buffer as a dmabuf (fd -1: none), handed to GTK as a GdkDmabufTexture */
		int dmabuf_fd;
		int dmabuf_fourcc;
		EGLint dmabuf_stride, dmabuf_offset;
		EGLuint64KHR dmabuf_modifier;
		uint32_t gl_texture;
		uint32_t gl_framebuffer;
		EGLSync tl_frame_fence;          /* native-egl-import-fence.sh: the app's commands for this frame */
		EGLDisplay tl_frame_fence_display;
		void *tl_gtk_sync;               /* the GLsync GTK waits on (import context) */
	} buffers[NUM_BUFFERS];
	struct atl_surface_buffer *back_buffer;
	struct atl_surface_buffer *front_buffer;
	/* tl_swap_gpu_throttle: fences of the frames queued whose GPU work may still run, oldest first;
	 * only the thread that swaps touches them */
	EGLSync tl_gpu_fences[TL_MAX_GPU_FRAMES + 1];
	EGLDisplay tl_gpu_fence_display;
	int tl_gpu_fence_count;
	GAsyncQueue *vsync;
	GAsyncQueue *unused_buffers;
	gboolean destroyed;
	GdkGLContext *tl_import_context; /* native-egl-import-context.sh */
};
G_DECLARE_FINAL_TYPE(ATLSurface, atl_surface, ATL, SURFACE, GObject);

/* tl_surface_for_widget (tl_dialog_overlay): a SurfaceView inside a dialog layer that is not in the
 * window yet has no native; its GL context belongs to the main toplevel's surface, where the layer
 * will be composited */
static GdkSurface *tl_surface_for_widget(GtkWidget *widget)
{
	GtkNative *native = gtk_widget_get_native(widget);
	if (native)
		return gtk_native_get_surface(native);
	GList *tops = gtk_window_list_toplevels();
	GdkSurface *surface = NULL;
	for (GList *l = tops; l && !surface; l = l->next)
		surface = gtk_native_get_surface(GTK_NATIVE(l->data));
	g_list_free(tops);
	return surface;
}
/* teslalinux (native-egl-import-context.sh): make the surface's import context current, creating and
 * realizing it on first use; returns the context that was current before (to restore), or sets *ok */
static GdkGLContext *tl_import_context_begin(ATLSurface *atl_surface, gboolean create, gboolean *ok)
{
	GdkGLContext *previous = gdk_gl_context_get_current();
	*ok = FALSE;
	if (!atl_surface->tl_import_context && create) {
		GError *error = NULL;
		GdkSurface *surface = tl_surface_for_widget(GTK_WIDGET(atl_surface->surface_view_widget));
		GdkGLContext *context = surface ? gdk_surface_create_gl_context(surface, &error) : NULL;
		if (context && !gdk_gl_context_realize(context, &error))
			g_clear_object(&context);
		if (!context) {
			static int warned;
			if (!warned++)
				fprintf(stderr, "[egl] no GL context to import SurfaceView frames into GTK: %s\n", error ? error->message : "no GdkSurface");
			g_clear_error(&error);
			return previous;
		}
		atl_surface->tl_import_context = context;
	}
	if (!atl_surface->tl_import_context)
		return previous;
	gdk_gl_context_make_current(atl_surface->tl_import_context);
	*ok = gdk_gl_context_get_current() == atl_surface->tl_import_context;
	return previous;
}

static void tl_import_context_end(GdkGLContext *previous)
{
	if (previous)
		gdk_gl_context_make_current(previous);
	else
		gdk_gl_context_clear_current();
}

static void atl_surface_dispose(GObject *object)
{
	ATLSurface *atl_surface = ATL_SURFACE(object);
	gboolean current;
	GdkGLContext *previous = tl_import_context_begin(atl_surface, FALSE, &current);
	for (int i = 0; i < NUM_BUFFERS; i++) {
		if (atl_surface->buffers[i].texture_builder) {
			GLuint texture_id = gdk_gl_texture_builder_get_id(atl_surface->buffers[i].texture_builder);
			if (current) /* never in another context: the number may name one of GTK's textures */
				glDeleteTextures(1, &texture_id);
			g_clear_object(&atl_surface->buffers[i].texture_builder);
		}
	}
	tl_import_context_end(previous);
	g_clear_object(&atl_surface->tl_import_context);
	g_async_queue_unref(atl_surface->unused_buffers);
	g_async_queue_unref(atl_surface->vsync);
	g_object_unref(atl_surface->surface_view_widget);
}
static void atl_surface_class_init(ATLSurfaceClass *class)
{
	G_OBJECT_CLASS(class)->dispose = atl_surface_dispose;
}
static void atl_surface_init(ATLSurface *self)
{
	for (int i = 0; i < NUM_BUFFERS; i++)
		self->buffers[i].dmabuf_fd = -1; /* tl_surface_offload: no dmabuf until the buffer exists */
}
G_DEFINE_TYPE(ATLSurface, atl_surface, G_TYPE_OBJECT)

/*
 * Android exports eglCreateImageKHR (with EGLAttrib) as a direct libEGL.so symbol,
 * while Mesa exports the core EGL 1.5 function eglCreateImage as its linkable entry
 * point instead. The wrappers bridge this ABI naming difference.
 */
EGLImage bionic_eglCreateImageKHR(EGLDisplay dpy, EGLContext ctx, EGLenum target, EGLClientBuffer buffer, const EGLAttrib *attrib_list)
{
	return eglCreateImage(dpy, ctx, target, buffer, attrib_list);
}

EGLBoolean bionic_eglDestroyImageKHR(EGLDisplay dpy, EGLImage image)
{
	return eglDestroyImage(dpy, image);
}

EGLSurface bionic_eglCreateWindowSurface(EGLDisplay display, EGLConfig config, struct ANativeWindow *native_window, EGLint const *attrib_list)
{
	// better than crashing (TODO: check if apps try to use the NULL value anyway)
	if (!native_window)
		return NULL;

	if (!egl_surface_hashtable)
		egl_surface_hashtable = g_hash_table_new(NULL, NULL);

	ANativeWindow_acquire(native_window);

	PrintConfigAttributes(display, config);
	EGLSurface surface;
	if (tl_direct_egl()) {
		surface = eglCreateWindowSurface(display, config, native_window->egl_window, attrib_list);
	} else {
		ATLSurface *atl_surface = g_object_new(atl_surface_get_type(), NULL);
		atl_surface->width = native_window->width;
		atl_surface->height = native_window->height;
		atl_surface->surface_view_widget = SURFACE_VIEW_WIDGET(g_object_ref(native_window->surface_view_widget));
		EGLint alpha_size = 0;
		EGLint depth_size = 0;
		EGLint stencil_size = 0;
		eglGetConfigAttrib(display, config, EGL_ALPHA_SIZE, &alpha_size);
		eglGetConfigAttrib(display, config, EGL_DEPTH_SIZE, &depth_size);
		eglGetConfigAttrib(display, config, EGL_STENCIL_SIZE, &stencil_size);
		if (alpha_size > 0) {
			atl_surface->framebuffer_format = GL_RGBA;
		} else {
			atl_surface->framebuffer_format = GL_RGB;
		}
		if (depth_size > 0 && stencil_size > 0) {
			atl_surface->renderbuffer_format = GL_DEPTH24_STENCIL8;
			atl_surface->renderbuffer_attachment = GL_DEPTH_STENCIL_ATTACHMENT;
		} else if (depth_size > 0) {
			atl_surface->renderbuffer_format = GL_DEPTH_COMPONENT16;
			atl_surface->renderbuffer_attachment = GL_DEPTH_ATTACHMENT;
		} else if (stencil_size > 0) {
			atl_surface->renderbuffer_format = GL_STENCIL_INDEX8;
			atl_surface->renderbuffer_attachment = GL_STENCIL_ATTACHMENT;
		}
		surface = atl_surface;
	}

	printf("EGL::: native_window->egl_window: %p\n", native_window->egl_window);
	printf("EGL::: eglGetError: %d\n", eglGetError());

	printf("EGL::: ret: %p\n", surface);

	g_hash_table_insert(egl_surface_hashtable, surface, native_window);

	if (!surface)
		ANativeWindow_release(native_window);

	return surface;
}

/* The surfaces each context draws to and reads from while an ATLSurface is current: Mesa sees no
 * surface then (the context is made current surfaceless, the app draws into the surface's FBO), so
 * eglGetCurrentSurface and framebuffer 0 are answered from here. An entry lives exactly as long as
 * that binding: making the context current with a surface Mesa owns (a pbuffer), or destroying the
 * ATLSurface, removes it. A stale entry made eglGetCurrentSurface report a destroyed surface (Waze's
 * map kept it as the fallback surface it makes current between windows, EGL_BAD_SURFACE on every
 * frame) and glBindFramebuffer(0) bind the freed surface's buffers. */
static GHashTable *draw_surface_hashtable = NULL;
static GHashTable *read_surface_hashtable = NULL;

static gboolean tl_entry_is_surface(gpointer context, gpointer surface, gpointer destroyed)
{
	return surface == destroyed;
}

static void tl_forget_surface(EGLSurface surface)
{
	if (draw_surface_hashtable)
		g_hash_table_foreach_remove(draw_surface_hashtable, tl_entry_is_surface, surface);
	if (read_surface_hashtable)
		g_hash_table_foreach_remove(read_surface_hashtable, tl_entry_is_surface, surface);
}

static void tl_forget_context(EGLContext context)
{
	if (draw_surface_hashtable)
		g_hash_table_remove(draw_surface_hashtable, context);
	if (read_surface_hashtable)
		g_hash_table_remove(read_surface_hashtable, context);
}

static void tl_swap_gpu_fences_destroy(ATLSurface *atl_surface);
EGLBoolean bionic_eglDestroySurface(EGLDisplay display, EGLSurface surface)
{
	struct ANativeWindow *native_window = tl_table_lookup(egl_surface_hashtable, surface);

	if (!native_window) {
		EGLBoolean ret = eglDestroySurface(display, surface);
		if (ret)
			tl_destroy_pbuffer_window(surface);
		return ret;
	}

	EGLBoolean ret = EGL_TRUE;
	if (tl_direct_egl()) {
		ret = eglDestroySurface(display, surface);
	} else {
		ATLSurface *atl_surface = surface;
		tl_forget_surface(surface);
		atl_surface->destroyed = TRUE;
		tl_swap_gpu_fences_destroy(atl_surface);
		for (int i = 0; i < NUM_BUFFERS; i++) {
			if (atl_surface->buffers[i].egl_image)
				eglDestroyImage(display, atl_surface->buffers[i].egl_image);
			atl_surface->buffers[i].egl_image = 0;
			if (atl_surface->buffers[i].dmabuf_fd >= 0)
				close(atl_surface->buffers[i].dmabuf_fd);
			atl_surface->buffers[i].dmabuf_fd = -1;
			if (atl_surface->buffers[i].gl_framebuffer)
				glDeleteFramebuffers(1, &atl_surface->buffers[i].gl_framebuffer);
			if (atl_surface->buffers[i].gl_texture)
				glDeleteTextures(1, &atl_surface->buffers[i].gl_texture);
		}
		g_object_unref(atl_surface);
	}
	if (ret) {
		g_hash_table_remove(egl_surface_hashtable, surface);
		ANativeWindow_release(native_window);
	}

	return ret;
}

/* tl_surface_offload: SurfaceView frames go to GTK as dmabufs, which GTK can put in a Wayland
 * subsurface of their own (GtkGraphicsOffload in the SurfaceView widget) instead of drawing them
 * into the window: the window is not redrawn for every frame of the app, and the frame reaches the
 * compositor as it is. The GL texture path stays for when a buffer cannot be exported or GTK does
 * not offload. ATL_SURFACE_OFFLOAD=0 turns it off. */
/* DRM fourccs (drm_fourcc.h's encoding: four characters, the first in the low byte) */
#define TL_FOURCC(a, b, c, d) ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))
#define DRM_FORMAT_ABGR8888 TL_FOURCC('A', 'B', '2', '4')
#define DRM_FORMAT_XBGR8888 TL_FOURCC('X', 'B', '2', '4')
#define DRM_FORMAT_ARGB8888 TL_FOURCC('A', 'R', '2', '4')
#define DRM_FORMAT_XRGB8888 TL_FOURCC('X', 'R', '2', '4')

/* how many frames an app may have queued but not shown (ATL_SWAP_FRAMES_AHEAD, 1 to 3; 1). On a
 * Pi 4 the GPU is the limit: a second frame queued ahead only lines more of the app's work up in
 * front of the compositor's, which then shows fewer frames (Waze in Car mode: 24 frames/s drawn,
 * 9.5 shown, against 24.8 shown with one) */
static int tl_frames_ahead(void)
{
	static int n;
	if (!n) {
		const char *e = getenv("ATL_SWAP_FRAMES_AHEAD");
		n = e ? atoi(e) : 1;
		if (n < 1 || n > 3)
			n = 1;
	}
	return n;
}

/* tl_swap_gpu_throttle: how many of an app's frames may still be on the GPU when eglSwapBuffers
 * returns (ATL_SWAP_GPU_FRAMES, 0 to TL_MAX_GPU_FRAMES; 0 turns the wait off). On Android a producer
 * cannot run further ahead of the GPU than its buffer queue allows: SurfaceFlinger latches a buffer
 * only once its acquire fence has signalled, and dequeueBuffer hands out a buffer only with the
 * release fence of the frame that used it before, which EGL waits on (Mesa's platform_android:
 * droid_window_dequeue_buffer). Here a frame replaced before it was shown gives its buffer and its
 * vsync token back at once, so nothing else stops an app whose frames take the GPU longer than its
 * CPU from queueing frame after frame behind the GPU: each frame then reaches the screen several GPU
 * frames after it was drawn, and the compositor's own GPU work waits behind the queue. */
static int tl_gpu_frames(void)
{
	static int n = -1;
	if (n < 0) {
		const char *e = getenv("ATL_SWAP_GPU_FRAMES");
		n = e ? atoi(e) : 1;
		if (n < 0 || n > TL_MAX_GPU_FRAMES)
			n = 1;
	}
	return n;
}

/* the fence of the frame just flushed goes in; then the oldest frames are waited for until at most
 * tl_gpu_frames() remain (a kernel wait on the GPU's fence, not a spin). A fence that does not signal
 * within a second (a GPU hang) is given up on, as the other bounded waits here are. */
static void tl_swap_gpu_throttle(ATLSurface *atl_surface, EGLDisplay display, EGLSync fence)
{
	if (fence == EGL_NO_SYNC)
		return;
	if (atl_surface->tl_gpu_fence_count > 0 && atl_surface->tl_gpu_fence_display != display) {
		for (int i = 0; i < atl_surface->tl_gpu_fence_count; i++) /* a new display: the old fences belong to the old one */
			eglDestroySync(atl_surface->tl_gpu_fence_display, atl_surface->tl_gpu_fences[i]);
		atl_surface->tl_gpu_fence_count = 0;
	}
	atl_surface->tl_gpu_fence_display = display;
	atl_surface->tl_gpu_fences[atl_surface->tl_gpu_fence_count++] = fence;
	while (atl_surface->tl_gpu_fence_count > tl_gpu_frames()) {
		EGLSync oldest = atl_surface->tl_gpu_fences[0];
		if (eglClientWaitSync(display, oldest, 0, 1000000000) == EGL_TIMEOUT_EXPIRED) { /* 1 s */
			static int warned;
			if (!warned++)
				fprintf(stderr, "[egl] a frame's GPU work did not finish within 1 s; not waiting for it\n");
		}
		eglDestroySync(display, oldest);
		memmove(&atl_surface->tl_gpu_fences[0], &atl_surface->tl_gpu_fences[1], --atl_surface->tl_gpu_fence_count * sizeof(EGLSync));
	}
}

static void tl_swap_gpu_fences_destroy(ATLSurface *atl_surface)
{
	for (int i = 0; i < atl_surface->tl_gpu_fence_count; i++)
		eglDestroySync(atl_surface->tl_gpu_fence_display, atl_surface->tl_gpu_fences[i]);
	atl_surface->tl_gpu_fence_count = 0;
}

gboolean tl_surface_offload_enabled(void)
{
	static int enabled = -1;
	if (enabled < 0) {
		const char *e = getenv("ATL_SURFACE_OFFLOAD");
		enabled = !(e && !strcmp(e, "0"));
	}
	return enabled;
}

static void tl_export_dmabuf(EGLDisplay display, struct atl_surface_buffer *buffer)
{
	static PFNEGLEXPORTDMABUFIMAGEQUERYMESAPROC query;
	static PFNEGLEXPORTDMABUFIMAGEMESAPROC export_image;
	buffer->dmabuf_fd = -1;
	if (!tl_surface_offload_enabled() || !buffer->egl_image)
		return;
	if (!query) {
		query = (PFNEGLEXPORTDMABUFIMAGEQUERYMESAPROC)eglGetProcAddress("eglExportDMABUFImageQueryMESA");
		export_image = (PFNEGLEXPORTDMABUFIMAGEMESAPROC)eglGetProcAddress("eglExportDMABUFImageMESA");
	}
	int fourcc = 0, planes = 0;
	EGLuint64KHR modifier = 0;
	if (!query || !export_image || !query(display, buffer->egl_image, &fourcc, &planes, &modifier) || planes != 1)
		return;
	int fd = -1;
	EGLint stride = 0, offset = 0;
	if (!export_image(display, buffer->egl_image, &fd, &stride, &offset) || fd < 0)
		return;
	buffer->dmabuf_fd = fd;
	buffer->dmabuf_fourcc = fourcc;
	buffer->dmabuf_stride = stride;
	buffer->dmabuf_offset = offset;
	buffer->dmabuf_modifier = modifier;
}

static GLuint tl_surface_back_buffer(ATLSurface *atl_surface); /* tl_makecurrent_nowait */
static EGLBoolean tl_make_current(EGLDisplay display, EGLSurface draw, EGLSurface read, EGLContext context);
EGLBoolean bionic_eglMakeCurrent(EGLDisplay display, EGLSurface draw, EGLSurface read, EGLContext context)
{
	/* gl_passes.c: the context that stops being current puts its deferred fences in first */
	tl_gl_before_make_current();
	EGLBoolean ret = tl_make_current(display, draw, read, context);
	tl_gl_after_make_current(eglGetCurrentContext());
	return ret;
}

static EGLBoolean tl_make_current(EGLDisplay display, EGLSurface draw, EGLSurface read, EGLContext context)
{
	if (tl_direct_egl())
		return eglMakeCurrent(display, draw, read, context);
	struct ANativeWindow *native_window = tl_table_lookup(egl_surface_hashtable, draw);
	if (!native_window) {
		/* a surface of Mesa's own (or none): from now on Mesa answers for this context */
		EGLBoolean ret = eglMakeCurrent(display, draw, read, context);
		if (ret && context != EGL_NO_CONTEXT)
			tl_forget_context(context);
		return ret;
	}
	if (!draw_surface_hashtable)
		draw_surface_hashtable = g_hash_table_new(NULL, NULL);
	g_hash_table_insert(draw_surface_hashtable, context, draw);
	if (!read_surface_hashtable)
		read_surface_hashtable = g_hash_table_new(NULL, NULL);
	g_hash_table_insert(read_surface_hashtable, context, read);
	EGLBoolean ret = eglMakeCurrent(display, NULL, NULL, context);
	ATLSurface *atl_surface = draw;
	if (!atl_surface->unused_buffers) {
		atl_surface->vsync = g_async_queue_new();
		/* tl_frames_ahead: eglSwapBuffers waits for the frame shown tl_frames_ahead() frames back
		 * (AOSP's buffer queue lets an app queue frames ahead the same way) */
		for (int i = 0; i < tl_frames_ahead(); i++)
			g_async_queue_push(atl_surface->vsync, GINT_TO_POINTER(1));
		atl_surface->unused_buffers = g_async_queue_new();
		GLint previousTexture;
		glGetIntegerv(GL_TEXTURE_BINDING_2D, &previousTexture); // Save current binding
		if (atl_surface->renderbuffer_format) {
			GLint previous_renderbuffer;
			glGetIntegerv(GL_RENDERBUFFER_BINDING, &previous_renderbuffer);
			glGenRenderbuffers(1, &atl_surface->renderbuffer);
			glBindRenderbuffer(GL_RENDERBUFFER, atl_surface->renderbuffer);
			glRenderbufferStorage(GL_RENDERBUFFER, atl_surface->renderbuffer_format, native_window->width, native_window->height);
			glBindRenderbuffer(GL_RENDERBUFFER, previous_renderbuffer);
		}

		for (int i = 0; i < NUM_BUFFERS; i++) {
			atl_surface->buffers[i].surface = atl_surface;
			glGenTextures(1, &atl_surface->buffers[i].gl_texture);
			glBindTexture(GL_TEXTURE_2D, atl_surface->buffers[i].gl_texture);
			glTexImage2D(GL_TEXTURE_2D, 0, atl_surface->framebuffer_format, native_window->width, native_window->height, 0, atl_surface->framebuffer_format, GL_UNSIGNED_BYTE, NULL);

			// Create EGLImage from texture
			atl_surface->buffers[i].egl_image = eglCreateImage(eglGetCurrentDisplay(),
			                                                   eglGetCurrentContext(), // The current context
			                                                   EGL_GL_TEXTURE_2D,
			                                                   (EGLClientBuffer)(uintptr_t)atl_surface->buffers[i].gl_texture,
			                                                   NULL);

			glGenFramebuffers(1, &atl_surface->buffers[i].gl_framebuffer);
			glBindFramebuffer(GL_FRAMEBUFFER, atl_surface->buffers[i].gl_framebuffer);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, atl_surface->buffers[i].gl_texture, 0);
			if (atl_surface->renderbuffer)
				glFramebufferRenderbuffer(GL_FRAMEBUFFER, atl_surface->renderbuffer_attachment, GL_RENDERBUFFER, atl_surface->renderbuffer);
			tl_export_dmabuf(eglGetCurrentDisplay(), &atl_surface->buffers[i]);
			g_async_queue_push(atl_surface->unused_buffers, &atl_surface->buffers[i]);
		}
		glBindTexture(GL_TEXTURE_2D, previousTexture);
	}
	/* tl_makecurrent_nowait: a bounded wait for a buffer, then the front buffer; never a wait on the
	 * app's own main thread (AOSP's buffer queue is consumed by SurfaceFlinger regardless) */
	glBindFramebuffer(GL_FRAMEBUFFER, tl_surface_back_buffer(atl_surface));
	return ret;
}

/* native-egl-import-fence.sh: glFenceSync/glDeleteSync of the import context */
static void *(*tl_gl_fence_sync)(unsigned int condition, unsigned int flags);
static void (*tl_gl_delete_sync)(void *sync);
static void tl_frame_fence_procs(void)
{
	if (!tl_gl_fence_sync) {
		tl_gl_fence_sync = (void *(*)(unsigned int, unsigned int))eglGetProcAddress("glFenceSync");
		tl_gl_delete_sync = (void (*)(void *))eglGetProcAddress("glDeleteSync");
	}
}

/* runs on main thread */
static void destroy_texture(void *data)
{
	struct atl_surface_buffer *buffer = data;
	if (buffer->tl_gtk_sync) {
		gboolean current;
		GdkGLContext *previous = tl_import_context_begin(buffer->surface, FALSE, &current);
		if (current && tl_gl_delete_sync)
			tl_gl_delete_sync(buffer->tl_gtk_sync);
		buffer->tl_gtk_sync = NULL;
		tl_import_context_end(previous);
	}
	g_async_queue_push(buffer->surface->unused_buffers, buffer);
	g_object_unref(buffer->surface);
}

static void frame_callback(SurfaceViewWidget *surface_view_widget)
{
	g_async_queue_push(surface_view_widget->frame_callback_data, GINT_TO_POINTER(1));
}

/* the frame about to replace the widget's: one that was never shown counts as consumed, as a frame
 * SurfaceFlinger drops for a newer one (its token goes back; with frames ahead, frames can arrive
 * faster than the screen shows them) */
static void tl_set_frame_callback(ATLSurface *atl_surface)
{
	SurfaceViewWidget *widget = atl_surface->surface_view_widget;
	if (widget->frame_callback) {
		widget->frame_callback(widget);
		widget->frame_callback = NULL;
	}
	widget->frame_callback_data = atl_surface->vsync;
	widget->frame_callback = frame_callback;
}

static gboolean queue_texture(gpointer data)
{
	static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES = NULL;
	ATLSurface *atl_surface = data;
	if (atl_surface->destroyed) {
		g_object_unref(atl_surface);
		return G_SOURCE_REMOVE;
	}
	struct atl_surface_buffer *buffer = atl_surface->front_buffer;
	if (buffer->dmabuf_fd >= 0) {
		/* tl_surface_offload: the dmabuf itself; the GPU work that drew it is waited for through the
		 * buffer's implicit fence (the app flushed it in eglSwapBuffers) */
		if (buffer->tl_frame_fence) {
			eglDestroySync(buffer->tl_frame_fence_display, buffer->tl_frame_fence);
			buffer->tl_frame_fence = NULL;
		}
		GdkDmabufTextureBuilder *builder = gdk_dmabuf_texture_builder_new();
		gdk_dmabuf_texture_builder_set_display(builder, gtk_widget_get_display(GTK_WIDGET(atl_surface->surface_view_widget)));
		gdk_dmabuf_texture_builder_set_width(builder, atl_surface->width);
		gdk_dmabuf_texture_builder_set_height(builder, atl_surface->height);
		/* an opaque surface (SurfaceHolder.setFormat) goes as the same memory without alpha: GTK
		 * places an opaque buffer below the window, under the app's views */
		uint32_t fourcc = buffer->dmabuf_fourcc;
		if (atl_surface->surface_view_widget->opaque) {
			if (fourcc == DRM_FORMAT_ABGR8888)
				fourcc = DRM_FORMAT_XBGR8888;
			else if (fourcc == DRM_FORMAT_ARGB8888)
				fourcc = DRM_FORMAT_XRGB8888;
		}
		gdk_dmabuf_texture_builder_set_fourcc(builder, fourcc);
		gdk_dmabuf_texture_builder_set_modifier(builder, buffer->dmabuf_modifier);
		gdk_dmabuf_texture_builder_set_premultiplied(builder, TRUE);
		gdk_dmabuf_texture_builder_set_n_planes(builder, 1);
		gdk_dmabuf_texture_builder_set_fd(builder, 0, buffer->dmabuf_fd);
		gdk_dmabuf_texture_builder_set_stride(builder, 0, buffer->dmabuf_stride);
		gdk_dmabuf_texture_builder_set_offset(builder, 0, buffer->dmabuf_offset);
		GError *error = NULL;
		GdkTexture *texture = gdk_dmabuf_texture_builder_build(builder, destroy_texture, buffer, &error);
		g_object_unref(builder);
		if (texture) {
			tl_set_frame_callback(atl_surface);
			surface_view_widget_set_texture(atl_surface->surface_view_widget, texture, TRUE);
			if (!gtk_widget_get_mapped(GTK_WIDGET(atl_surface->surface_view_widget)) && atl_surface->surface_view_widget->frame_callback) {
				atl_surface->surface_view_widget->frame_callback(atl_surface->surface_view_widget);
				atl_surface->surface_view_widget->frame_callback = NULL;
			}
			return G_SOURCE_REMOVE; /* the reference is dropped in destroy_texture */
		}
		static int warned;
		if (!warned++)
			fprintf(stderr, "[egl] SurfaceView frames go through GL textures: GTK takes no dmabuf of format %.4s modifier 0x%llx (%s)\n",
			        (char *)&buffer->dmabuf_fourcc, (unsigned long long)buffer->dmabuf_modifier, error ? error->message : "?");
		g_clear_error(&error);
		close(buffer->dmabuf_fd);
		buffer->dmabuf_fd = -1;
		/* the GL path needs the image the export left alone */
	}
	if (!buffer->texture_builder) {
		GLuint texture_id;
		gboolean current;
		GdkGLContext *previous = tl_import_context_begin(atl_surface, TRUE, &current);
		if (!current) {
			/* no context to import into: the frame is consumed and dropped (its buffer goes back) */
			tl_import_context_end(previous);
			g_async_queue_push(atl_surface->unused_buffers, buffer);
			g_async_queue_push(atl_surface->vsync, GINT_TO_POINTER(1));
			g_object_unref(atl_surface);
			return G_SOURCE_REMOVE;
		}
		glGenTextures(1, &texture_id);
		glBindTexture(GL_TEXTURE_2D, texture_id);
		if (!glEGLImageTargetTexture2DOES)
			glEGLImageTargetTexture2DOES = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
		glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, buffer->egl_image);
		glBindTexture(GL_TEXTURE_2D, 0);
		eglDestroyImage(eglGetCurrentDisplay(), buffer->egl_image);
		buffer->egl_image = NULL;
		buffer->texture_builder = gdk_gl_texture_builder_new();
		gdk_gl_texture_builder_set_context(buffer->texture_builder, atl_surface->tl_import_context);
		gdk_gl_texture_builder_set_id(buffer->texture_builder, texture_id);
		gdk_gl_texture_builder_set_format(buffer->texture_builder, GDK_MEMORY_R8G8B8A8_PREMULTIPLIED);
		gdk_gl_texture_builder_set_width(buffer->texture_builder, atl_surface->width);
		gdk_gl_texture_builder_set_height(buffer->texture_builder, atl_surface->height);
		tl_import_context_end(previous);
	}
	if (buffer->tl_frame_fence || !buffer->tl_gtk_sync) {
		gboolean current;
		GdkGLContext *previous = tl_import_context_begin(atl_surface, FALSE, &current);
		if (buffer->tl_frame_fence) {
			EGLDisplay fence_display = buffer->tl_frame_fence_display;
			if (!current || !eglWaitSync(fence_display, buffer->tl_frame_fence, 0))
				eglClientWaitSync(fence_display, buffer->tl_frame_fence, 0, 100000000); /* 100 ms */
			eglDestroySync(fence_display, buffer->tl_frame_fence);
			buffer->tl_frame_fence = NULL;
		}
		tl_frame_fence_procs();
		if (current && tl_gl_fence_sync && !buffer->tl_gtk_sync) {
			buffer->tl_gtk_sync = tl_gl_fence_sync(0x9117 /* GL_SYNC_GPU_COMMANDS_COMPLETE */, 0);
			glFlush();
		}
		gdk_gl_texture_builder_set_sync(buffer->texture_builder, buffer->tl_gtk_sync);
		tl_import_context_end(previous);
	}
	tl_set_frame_callback(atl_surface);
	surface_view_widget_set_texture(atl_surface->surface_view_widget, gdk_gl_texture_builder_build(buffer->texture_builder, destroy_texture, buffer), TRUE);
	if (!gtk_widget_get_mapped(GTK_WIDGET(atl_surface->surface_view_widget)) && atl_surface->surface_view_widget->frame_callback) {
		/* tl_makecurrent_nowait: nobody snapshots a hidden widget; consume the frame like SurfaceFlinger does */
		atl_surface->surface_view_widget->frame_callback(atl_surface->surface_view_widget);
		atl_surface->surface_view_widget->frame_callback = NULL;
	}
	// atl_surface reference will be dropped in destroy_texture
	return G_SOURCE_REMOVE;
}

EGLBoolean bionic_eglSwapBuffers(EGLDisplay display, EGLSurface surface)
{
	if (tl_debug_surface()) {
		static int swaps = 0;
		if ((++swaps % 50) == 1)
			fprintf(stderr, "[surface] eglSwapBuffers #%d surface=%p\n", swaps, (void *)surface);
	}
	tl_gl_flush_point(); /* gl_passes.c: the app's deferred fences go in before the frame's flush */
	if (tl_direct_egl())
		return eglSwapBuffers(display, surface);
	struct ANativeWindow *native_window = tl_table_lookup(egl_surface_hashtable, surface);
	if (!native_window) {
		return eglSwapBuffers(display, surface);
	}
	ATLSurface *atl_surface = surface;
	/* eglSwapBuffers leaves the context's framebuffer bindings alone (EGL 1.5, 3.10.1): only when the
	 * app has the default framebuffer bound (one of this surface's buffers, or 0) does the next buffer
	 * take its place below. GL_FRAMEBUFFER_BINDING is GL_DRAW_FRAMEBUFFER_BINDING, in GLES 2 too */
	GLint app_framebuffer = 0;
	glGetIntegerv(GL_FRAMEBUFFER_BINDING, &app_framebuffer);
	gboolean app_default_framebuffer = app_framebuffer == 0;
	for (int i = 0; i < NUM_BUFFERS; i++)
		app_default_framebuffer |= atl_surface->buffers[i].gl_framebuffer && (GLint)atl_surface->buffers[i].gl_framebuffer == app_framebuffer;

	if (atl_surface->back_buffer) { /* native-egl-import-fence.sh */
		struct atl_surface_buffer *b = atl_surface->back_buffer;
		if (b->tl_frame_fence)
			eglDestroySync(b->tl_frame_fence_display, b->tl_frame_fence);
		b->tl_frame_fence_display = eglGetCurrentDisplay();
		b->tl_frame_fence = eglCreateSync(b->tl_frame_fence_display, EGL_SYNC_FENCE, NULL);
		if (b->tl_frame_fence == EGL_NO_SYNC)
			b->tl_frame_fence = NULL;
	}
	/* tl_swap_gpu_throttle: this frame's own fence (the buffer's one above is the compositor side's,
	 * used and destroyed on the main thread) */
	EGLDisplay gpu_fence_display = eglGetCurrentDisplay();
	EGLSync gpu_fence = (atl_surface->back_buffer && tl_gpu_frames() > 0) ? eglCreateSync(gpu_fence_display, EGL_SYNC_FENCE, NULL) : EGL_NO_SYNC;
	glFlush();
	if (tl_debug_surface()) { /* tl_frame_probe */
		static int probes = 0;
		if ((++probes % 100) == 1 && atl_surface->back_buffer) {
			unsigned char c[4] = {0}, e[4] = {0};
			int w = atl_surface->width, h = atl_surface->height;
			glReadPixels(w / 2, h / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, c);
			glReadPixels(10, 10, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, e);
			fprintf(stderr, "[surface] frame #%d %dx%d fbo=%u centre=%02x%02x%02x%02x edge=%02x%02x%02x%02x glerr=%x\n", probes, w, h,
			        atl_surface->back_buffer->gl_framebuffer, c[0], c[1], c[2], c[3], e[0], e[1], e[2], e[3], glGetError());
		}
	}
	if (atl_surface->back_buffer) {
		g_async_queue_timeout_pop(atl_surface->vsync, 50000); // 50ms
		atl_surface->front_buffer = atl_surface->back_buffer;
		atl_surface->back_buffer = NULL;
		g_idle_add_full(G_PRIORITY_HIGH_IDLE + 20, queue_texture, g_object_ref(atl_surface), NULL);
	}
	/* the frame is queued; before the next buffer goes to the app, its earlier frames' GPU work
	 * (as dequeueBuffer's release fence wait) */
	tl_swap_gpu_throttle(atl_surface, gpu_fence_display, gpu_fence);
	atl_surface->back_buffer = g_async_queue_timeout_pop(atl_surface->unused_buffers, 100000); // 100ms
	if (!app_default_framebuffer)
		return EGL_TRUE; /* the app's own framebuffer stays bound; binding 0 later gets the new buffer */
	if (atl_surface->back_buffer)
		glBindFramebuffer(GL_FRAMEBUFFER, atl_surface->back_buffer->gl_framebuffer);
	else
		glBindFramebuffer(GL_FRAMEBUFFER, 0);

	return EGL_TRUE;
}

EGLBoolean bionic_eglQuerySurface(EGLDisplay display, EGLSurface surface, EGLint attribute, EGLint *value)
{
	if (tl_direct_egl())
		return eglQuerySurface(display, surface, attribute, value);
	struct ANativeWindow *native_window = tl_table_lookup(egl_surface_hashtable, surface);
	if (!native_window)
		return eglQuerySurface(display, surface, attribute, value);
	if (attribute == EGL_WIDTH) {
		*value = native_window->width;
	} else if (attribute == EGL_HEIGHT) {
		*value = native_window->height;
	} else {
		printf("bionic_eglQuerySurface(%p, %p, %d, %p): attribute not implemented\n", display, surface, attribute, value);
		return eglQuerySurface(display, surface, attribute, value);
	}
	return EGL_TRUE;
}

EGLSurface bionic_eglGetCurrentSurface(EGLint readdraw)
{
	if (tl_direct_egl())
		return eglGetCurrentSurface(readdraw);
	EGLContext current_context = eglGetCurrentContext();
	EGLSurface surface = NULL; /* tl_current_surface_fallback */
	if (readdraw == EGL_READ)
		surface = tl_table_lookup(read_surface_hashtable, current_context);
	else if (readdraw == EGL_DRAW)
		surface = tl_table_lookup(draw_surface_hashtable, current_context);
	if (!surface)
		surface = eglGetCurrentSurface(readdraw);
	return surface;
}

/* teslalinux: the framebuffer to render into; waits for a free buffer like AOSP's dequeueBuffer */
static GLuint tl_surface_back_buffer(ATLSurface *atl_surface)
{
	if (!atl_surface->back_buffer && atl_surface->unused_buffers)
		atl_surface->back_buffer = g_async_queue_timeout_pop(atl_surface->unused_buffers, 500000); // 500 ms
	if (atl_surface->back_buffer)
		return atl_surface->back_buffer->gl_framebuffer;
	if (atl_surface->front_buffer) {
		static int warned = 0;
		if (!warned++)
			fprintf(stderr, "[egl] no free buffer for surface %p (its widget is not consuming frames); drawing into the front buffer\n", (void *)atl_surface);
		return atl_surface->front_buffer->gl_framebuffer;
	}
	return 0;
}

void bionic_glBindFramebuffer(GLenum target, GLuint framebuffer)
{
	if (tl_direct_egl() || framebuffer != 0)
		return glBindFramebuffer(target, framebuffer);
	ATLSurface *atl_surface = tl_table_lookup(draw_surface_hashtable, eglGetCurrentContext());
	return glBindFramebuffer(target, atl_surface ? tl_surface_back_buffer(atl_surface) : 0);
}
