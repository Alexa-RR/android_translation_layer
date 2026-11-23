/* teslalinux: the buffer queue behind android.graphics.SurfaceTexture (AOSP BufferQueue + GLConsumer).
 *
 * The producer is an EGL window surface made on the SurfaceTexture's window (Surface(SurfaceTexture),
 * EGL14/EGLImpl eglCreateWindowSurface(SurfaceTexture)): egl.c's ATLSurface draws each frame into one of
 * its buffers, a GL texture with an EGLImage, and on eglSwapBuffers queues that image here with the
 * fence of the commands that drew it and its timestamp (eglPresentationTimeANDROID's, else the time it
 * was queued). The consumer, in its own GL context, latches the oldest queued frame with
 * updateTexImage: it waits for the frame's fence, binds the image to its texture name as
 * GL_TEXTURE_EXTERNAL_OES (OES_EGL_image_external; Mesa has it on llvmpipe and V3D) and gives the
 * previous frame's buffer back to the producer. Producer and consumer may be different threads and
 * unshared contexts of the same EGL display: the EGLImage is what they share. */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>

#include "native_window.h"
#include "surface_texture.h"

struct tl_surface_texture {
	gint refcount;
	GMutex lock;
	/* the producer side */
	GQueue queue; /* struct tl_st_frame *, oldest first */
	int default_width, default_height;
	gboolean abandoned;
	gboolean connected; /* a producer (an EGL window surface) is connected */
	/* the consumer's frame listener; called and replaced under listener_lock, so that it is never
	 * freed while a producer calls it */
	GMutex listener_lock;
	void (*listener)(void *data);
	void *listener_data;
	/* the consumer side (GLConsumer) */
	gboolean attached;
	unsigned int tex_name;
	EGLContext context; /* the context it is attached to; EGL_NO_CONTEXT until the first update */
	struct tl_st_frame *current;
	gint64 timestamp_ns;
	EGLImage released_image; /* releaseTexImage: the 1x1 black image bound in place of a frame */
	unsigned int released_texture;
	EGLContext released_context;
};

static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC tl_image_target_texture;

struct tl_surface_texture *tl_surface_texture_new(unsigned int tex_name, gboolean attached)
{
	struct tl_surface_texture *st = calloc(1, sizeof(*st));
	st->refcount = 1;
	g_mutex_init(&st->lock);
	g_mutex_init(&st->listener_lock);
	g_queue_init(&st->queue);
	/* AOSP BufferQueueCore: a new queue's default buffer size is 1x1 */
	st->default_width = 1;
	st->default_height = 1;
	st->attached = attached;
	st->tex_name = tex_name;
	st->context = EGL_NO_CONTEXT;
	return st;
}

struct tl_surface_texture *tl_surface_texture_ref(struct tl_surface_texture *st)
{
	g_atomic_int_inc(&st->refcount);
	return st;
}

static void tl_frame_give_back(struct tl_st_frame *frame)
{
	if (!frame)
		return;
	if (frame->fence != EGL_NO_SYNC)
		eglDestroySync(frame->display, frame->fence);
	if (frame->release)
		frame->release(frame->owner, frame->buffer);
	free(frame);
}

void tl_surface_texture_unref(struct tl_surface_texture *st)
{
	if (!g_atomic_int_dec_and_test(&st->refcount))
		return;
	/* the window holds a reference, so it is gone already */
	struct tl_st_frame *f;
	while ((f = g_queue_pop_head(&st->queue)))
		tl_frame_give_back(f);
	tl_frame_give_back(st->current);
	g_mutex_clear(&st->lock);
	g_mutex_clear(&st->listener_lock);
	free(st);
}

/* a window for one producer connection (an EGL surface, an ANativeWindow_fromSurface): its own
 * object, so that its reference count is only touched by the code using it */
struct ANativeWindow *tl_surface_texture_get_window(struct tl_surface_texture *st)
{
	struct ANativeWindow *w = calloc(1, sizeof(struct ANativeWindow));
	w->refcount = 1;
	w->surface_texture = tl_surface_texture_ref(st);
	g_mutex_lock(&st->lock);
	/* the size an EGL surface made on the window gets (AOSP: the next dequeued buffer's) */
	w->width = st->default_width;
	w->height = st->default_height;
	g_mutex_unlock(&st->lock);
	return w;
}

/* called by ANativeWindow_release when the window's last reference goes */
void tl_surface_texture_window_gone(struct tl_surface_texture *st, struct ANativeWindow *window)
{
	(void)window;
	tl_surface_texture_unref(st);
}

void tl_surface_texture_set_default_buffer_size(struct tl_surface_texture *st, int width, int height)
{
	g_mutex_lock(&st->lock);
	st->default_width = width;
	st->default_height = height;
	g_mutex_unlock(&st->lock);
}

void tl_surface_texture_get_default_buffer_size(struct tl_surface_texture *st, int *width, int *height)
{
	g_mutex_lock(&st->lock);
	*width = st->default_width;
	*height = st->default_height;
	g_mutex_unlock(&st->lock);
}

void *tl_surface_texture_set_frame_listener(struct tl_surface_texture *st, void (*listener)(void *data), void *data)
{
	g_mutex_lock(&st->listener_lock);
	void *previous = st->listener_data;
	st->listener = listener;
	st->listener_data = data;
	g_mutex_unlock(&st->listener_lock);
	return previous;
}

gboolean tl_surface_texture_connect(struct tl_surface_texture *st)
{
	g_mutex_lock(&st->lock);
	gboolean ok = !st->abandoned && !st->connected;
	if (ok)
		st->connected = TRUE;
	g_mutex_unlock(&st->lock);
	return ok;
}

void tl_surface_texture_disconnect(struct tl_surface_texture *st)
{
	g_mutex_lock(&st->lock);
	st->connected = FALSE;
	g_mutex_unlock(&st->lock);
}

gboolean tl_surface_texture_is_abandoned(struct tl_surface_texture *st)
{
	g_mutex_lock(&st->lock);
	gboolean abandoned = st->abandoned;
	g_mutex_unlock(&st->lock);
	return abandoned;
}

gboolean tl_surface_texture_queue(struct tl_surface_texture *st, const struct tl_st_frame *frame)
{
	struct tl_st_frame *f = malloc(sizeof(*f));
	*f = *frame;
	g_mutex_lock(&st->lock);
	if (st->abandoned) {
		g_mutex_unlock(&st->lock);
		tl_frame_give_back(f);
		return FALSE; /* AOSP: queueBuffer on an abandoned queue fails (NO_INIT) */
	}
	g_queue_push_tail(&st->queue, f);
	g_mutex_unlock(&st->lock);
	/* AOSP ConsumerListener.onFrameAvailable, on the producer's thread */
	g_mutex_lock(&st->listener_lock);
	if (st->listener)
		st->listener(st->listener_data);
	g_mutex_unlock(&st->listener_lock);
	return TRUE;
}

static GLenum tl_texture_target(void)
{
	static int external = -1;
	if (external < 0) {
		const char *exts = (const char *)glGetString(GL_EXTENSIONS);
		external = exts && strstr(exts, "GL_OES_EGL_image_external") != NULL;
	}
	/* GL_TEXTURE_EXTERNAL_OES as on Android; a driver without OES_EGL_image_external gets the frame
	 * as a GL_TEXTURE_2D, which is what its image target can bind */
	return external ? GL_TEXTURE_EXTERNAL_OES : GL_TEXTURE_2D;
}

static void tl_bind_image(unsigned int tex_name, EGLImage image)
{
	if (!tl_image_target_texture)
		tl_image_target_texture = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
	GLenum target = tl_texture_target();
	glBindTexture(target, tex_name);
	tl_image_target_texture(target, image);
}

/* the consumer's context must be the one it is attached to (AOSP GLConsumer::checkAndUpdateEglStateLocked) */
static int tl_check_context_locked(struct tl_surface_texture *st)
{
	if (st->abandoned)
		return -ENODEV;
	if (!st->attached)
		return -EINVAL;
	EGLContext ctx = eglGetCurrentContext();
	if (ctx == EGL_NO_CONTEXT)
		return -EINVAL;
	if (st->context == EGL_NO_CONTEXT)
		st->context = ctx;
	else if (st->context != ctx)
		return -EINVAL;
	return 0;
}

int tl_surface_texture_update(struct tl_surface_texture *st)
{
	g_mutex_lock(&st->lock);
	int err = tl_check_context_locked(st);
	if (err) {
		g_mutex_unlock(&st->lock);
		return err;
	}
	struct tl_st_frame *next = g_queue_pop_head(&st->queue);
	struct tl_st_frame *previous = NULL;
	if (next) {
		previous = st->current;
		st->current = next;
		st->timestamp_ns = next->timestamp_ns;
	}
	struct tl_st_frame *bind = st->current;
	unsigned int tex = st->tex_name;
	g_mutex_unlock(&st->lock);
	if (next && next->fence != EGL_NO_SYNC) {
		/* AOSP doGLFenceWait: the GPU waits for the producer's commands before sampling */
		if (!eglWaitSync(next->display, next->fence, 0))
			eglClientWaitSync(next->display, next->fence, EGL_SYNC_FLUSH_COMMANDS_BIT, 1000000000);
		eglDestroySync(next->display, next->fence);
		next->fence = EGL_NO_SYNC;
	}
	/* AOSP: without a new frame the current one is bound again */
	if (bind)
		tl_bind_image(tex, bind->image);
	tl_frame_give_back(previous);
	return 0;
}

int tl_surface_texture_release_tex_image(struct tl_surface_texture *st)
{
	g_mutex_lock(&st->lock);
	int err = tl_check_context_locked(st);
	if (err) {
		g_mutex_unlock(&st->lock);
		return err;
	}
	struct tl_st_frame *previous = st->current;
	st->current = NULL;
	unsigned int tex = st->tex_name;
	EGLContext ctx = st->context;
	g_mutex_unlock(&st->lock);
	/* AOSP GLConsumer::releaseTexImage: a 1x1 black buffer takes the released frame's place */
	if (st->released_image == EGL_NO_IMAGE || st->released_context != ctx) {
		static const unsigned char black[4] = {0, 0, 0, 0};
		GLint previous_binding = 0;
		glGetIntegerv(GL_TEXTURE_BINDING_2D, &previous_binding);
		glGenTextures(1, &st->released_texture);
		glBindTexture(GL_TEXTURE_2D, st->released_texture);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, black);
		glBindTexture(GL_TEXTURE_2D, previous_binding);
		st->released_image = eglCreateImage(eglGetCurrentDisplay(), ctx, EGL_GL_TEXTURE_2D, (EGLClientBuffer)(uintptr_t)st->released_texture, NULL);
		st->released_context = ctx;
	}
	if (st->released_image != EGL_NO_IMAGE)
		tl_bind_image(tex, st->released_image);
	tl_frame_give_back(previous);
	return 0;
}

int tl_surface_texture_detach(struct tl_surface_texture *st)
{
	g_mutex_lock(&st->lock);
	int err = tl_check_context_locked(st);
	if (!err) {
		/* AOSP GLConsumer::detachFromContext: the texture name goes with the context */
		glDeleteTextures(1, &st->tex_name);
		if (st->released_texture && st->released_context == st->context) {
			eglDestroyImage(eglGetCurrentDisplay(), st->released_image);
			glDeleteTextures(1, &st->released_texture);
			st->released_image = EGL_NO_IMAGE;
			st->released_texture = 0;
			st->released_context = EGL_NO_CONTEXT;
		}
		st->attached = FALSE;
		st->context = EGL_NO_CONTEXT;
		st->tex_name = 0;
	}
	g_mutex_unlock(&st->lock);
	return err;
}

int tl_surface_texture_attach(struct tl_surface_texture *st, unsigned int tex_name)
{
	g_mutex_lock(&st->lock);
	EGLContext ctx = eglGetCurrentContext();
	int err = 0;
	if (st->abandoned)
		err = -ENODEV;
	else if (st->attached || ctx == EGL_NO_CONTEXT)
		err = -EINVAL; /* AOSP: already attached, or no current context */
	if (!err) {
		st->attached = TRUE;
		st->context = ctx;
		st->tex_name = tex_name;
	}
	struct tl_st_frame *bind = err ? NULL : st->current;
	g_mutex_unlock(&st->lock);
	/* AOSP GLConsumer::attachToContext: the current frame is bound to the new texture */
	if (bind)
		tl_bind_image(tex_name, bind->image);
	return err;
}

void tl_surface_texture_get_transform_matrix(struct tl_surface_texture *st, float mtx[16])
{
	/* AOSP GLConsumer::computeTransformMatrix: the buffer's transform flags, its crop, then a
	 * vertical flip, because Android's buffers hold the top row first. A frame here is a GL texture
	 * the producer drew through a framebuffer, holding the bottom row first, uncropped and
	 * untransformed: texture coordinates need no change. */
	static const float identity[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	(void)st;
	memcpy(mtx, identity, sizeof(identity));
}

gint64 tl_surface_texture_get_timestamp(struct tl_surface_texture *st)
{
	g_mutex_lock(&st->lock);
	gint64 t = st->timestamp_ns;
	g_mutex_unlock(&st->lock);
	return t;
}

void tl_surface_texture_abandon(struct tl_surface_texture *st)
{
	GQueue frames = G_QUEUE_INIT;
	g_mutex_lock(&st->lock);
	st->abandoned = TRUE;
	struct tl_st_frame *f;
	while ((f = g_queue_pop_head(&st->queue)))
		g_queue_push_tail(&frames, f);
	if (st->current)
		g_queue_push_tail(&frames, st->current);
	st->current = NULL;
	g_mutex_unlock(&st->lock);
	while ((f = g_queue_pop_head(&frames)))
		tl_frame_give_back(f);
}
