#ifndef TL_SURFACE_TEXTURE_H
#define TL_SURFACE_TEXTURE_H

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <glib.h>

/* A frame a producer (an EGL window surface on the SurfaceTexture) queues: the image it drew, the
 * fence of the commands that drew it, its timestamp, and how to give the buffer back. */
struct tl_st_frame {
	EGLImage image;
	EGLDisplay display;
	EGLSync fence; /* owned by the SurfaceTexture once queued (EGL_NO_SYNC: none) */
	gint64 timestamp_ns;
	int width;
	int height;
	void (*release)(void *owner, void *buffer); /* the producer's buffer is free again */
	void *owner;
	void *buffer;
};

struct tl_surface_texture;
struct ANativeWindow;

struct tl_surface_texture *tl_surface_texture_new(unsigned int tex_name, gboolean attached);
struct tl_surface_texture *tl_surface_texture_ref(struct tl_surface_texture *st);
void tl_surface_texture_unref(struct tl_surface_texture *st);

/* the producer's window (a new reference): EGL window surfaces made on it queue their frames here */
struct ANativeWindow *tl_surface_texture_get_window(struct tl_surface_texture *st);
void tl_surface_texture_set_default_buffer_size(struct tl_surface_texture *st, int width, int height);
void tl_surface_texture_get_default_buffer_size(struct tl_surface_texture *st, int *width, int *height);

/* producer side: AOSP BufferQueueProducer::connect/disconnect (one producer at a time; FALSE when
 * one is connected or the SurfaceTexture was released) */
gboolean tl_surface_texture_connect(struct tl_surface_texture *st);
void tl_surface_texture_disconnect(struct tl_surface_texture *st);
/* FALSE when the SurfaceTexture was released (the frame is given back) */
gboolean tl_surface_texture_queue(struct tl_surface_texture *st, const struct tl_st_frame *frame);
gboolean tl_surface_texture_is_abandoned(struct tl_surface_texture *st);
/* returns the previous listener data */
void *tl_surface_texture_set_frame_listener(struct tl_surface_texture *st, void (*listener)(void *data), void *data);

/* consumer side (the GL context it is attached to must be current); 0 or a negative errno */
int tl_surface_texture_update(struct tl_surface_texture *st);
int tl_surface_texture_release_tex_image(struct tl_surface_texture *st);
int tl_surface_texture_detach(struct tl_surface_texture *st);
int tl_surface_texture_attach(struct tl_surface_texture *st, unsigned int tex_name);
void tl_surface_texture_get_transform_matrix(struct tl_surface_texture *st, float mtx[16]);
gint64 tl_surface_texture_get_timestamp(struct tl_surface_texture *st);
/* SurfaceTexture.release: the consumer goes away, the producer is abandoned */
void tl_surface_texture_abandon(struct tl_surface_texture *st);

#endif
