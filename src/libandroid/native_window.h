
#include <EGL/egl.h>
#include <GL/gl.h>
#include <X11/Xlib.h>
#include <gtk/gtk.h>
#include <jni.h>

struct tl_surface_texture;

struct ANativeWindow {
	EGLNativeWindowType egl_window;
	GtkWidget *surface_view_widget;
	struct wl_display *wayland_display;
	struct wl_surface *wayland_surface;
	Display *x11_display;
	gulong resize_handler;
	int refcount;
	int width;
	int height;
	/* a SurfaceTexture's producer window (surface_texture.c): no widget, the frames go to it */
	struct tl_surface_texture *surface_texture;
};

void tl_surface_texture_window_gone(struct tl_surface_texture *st, struct ANativeWindow *window);

struct ANativeWindow *ANativeWindow_fromSurface(JNIEnv *env, jobject surface);
EGLSurface bionic_eglCreateWindowSurface(EGLDisplay display, EGLConfig config, struct ANativeWindow *native_window, EGLint const *attrib_list);
EGLBoolean bionic_eglDestroySurface(EGLDisplay display, EGLSurface surface);
EGLDisplay bionic_eglGetDisplay(NativeDisplayType native_display);
EGLBoolean bionic_eglMakeCurrent(EGLDisplay display, EGLSurface draw, EGLSurface read, EGLContext context);
EGLBoolean bionic_eglSwapBuffers(EGLDisplay display, EGLSurface surface);
EGLContext bionic_eglCreateContext(EGLDisplay display, EGLConfig config, EGLContext share_context, const EGLint *attrib_list);
EGLBoolean bionic_eglDestroyContext(EGLDisplay display, EGLContext context);
EGLBoolean bionic_eglReleaseThread(void);
EGLBoolean bionic_eglWaitGL(void);
EGLBoolean bionic_eglWaitClient(void);
EGLint bionic_eglGetError(void);
EGLBoolean bionic_eglSurfaceAttrib(EGLDisplay display, EGLSurface surface, EGLint attribute, EGLint value);
EGLBoolean bionic_eglQuerySurface(EGLDisplay display, EGLSurface surface, EGLint attribute, EGLint *value);
void bionic_glBindFramebuffer(GLenum target, GLuint framebuffer);
void ANativeWindow_acquire(struct ANativeWindow *native_window);
void ANativeWindow_release(struct ANativeWindow *native_window);
