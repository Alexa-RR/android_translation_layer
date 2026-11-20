/*
 * Render passes on a tiling GPU (the Raspberry Pi's V3D, Mesa's v3d driver).
 *
 * A tiler renders a framebuffer one pass at a time: it loads the tiles, runs the draws, stores the
 * tiles. A pass ends whenever the driver flushes, and every extra pass loads and stores the whole
 * framebuffer again. Two things make an app's frame cost passes here that it does not cost on the
 * GPUs Android apps are written for:
 *
 * 1. glFenceSync flushes. Mesa asks the driver for a deferred flush when the context is alone in its
 *    share group (st_fence_sync: PIPE_FLUSH_DEFERRED), as Android's GPU drivers defer it, but v3d
 *    flushes every job anyway. Godot (Waze's map) creates fences in the middle of its main pass, so
 *    that pass became four, each storing and reloading colour and depth. Here an app's fence goes
 *    into the command stream when its context next flushes: glFlush, glFinish, eglSwapBuffers,
 *    eglMakeCurrent, eglReleaseThread, eglWaitGL/eglWaitClient, or a wait that needs it. That fence
 *    signals once every command before the app's glFenceSync has completed, as the GL requires, and
 *    never earlier than the fence would have. A status query, or a zero-timeout wait without
 *    GL_SYNC_FLUSH_COMMANDS_BIT, reports it unsignalled (its commands have not even been flushed);
 *    any other wait creates it first. Only fences of a context alone in its share group are deferred,
 *    as in Mesa: no other context can wait on them.
 *
 * 2. A clear is a pass of its own when nothing is drawn after it, and a clear of a buffer that
 *    already holds exactly that value changes nothing. Godot clears a 2048x2048 shadow atlas twice a
 *    frame that nothing is drawn into, and a transparent viewport that stays empty: on a phone's GPU
 *    those cost little (unchanged tiles are not written back); on V3D each is a full pass, 3 to 6 ms
 *    of a 30 to 45 ms frame. Here a clear is left out when every buffer it would clear is known to
 *    hold that value: it was fully cleared to it, the framebuffer was complete, and nothing has
 *    written it since. Every way a context writes a texture or renderbuffer is followed: draws and
 *    blits into a framebuffer it is attached to, partial (scissored or masked) clears, invalidation,
 *    uploads and copies into it, storage changes, image units (shader stores), EGL images (written
 *    outside GL) and deletion. The decisions read the GL's own state (bindings, attachments, clear
 *    values, masks, scissor), never a copy of it, so an app's GL error cannot make them wrong. Entry
 *    points the app gets from eglGetProcAddress resolve to these same wrappers (egl.c); a name that
 *    could write a texture and has none here turns the clear tracking off (tl_gl_proc_requested).
 *    It applies to contexts that never shared their objects with another context, and to
 *    framebuffers whose attachments are level 0 of 2D textures or renderbuffers, attached through
 *    these wrappers, cleared through draw buffer 0 only.
 *
 * ATL_GL_DEFER_FENCES=0 and ATL_GL_SKIP_CLEARS=0 turn each off; ATL_DEBUG_GL_PASSES=1 prints counts.
 */

#include <EGL/egl.h>
#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>

#include <glib.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "gl_passes.h"

/* ---- switches ---- */

static int tl_env_switch(const char *name, int def)
{
	const char *e = getenv(name);
	return e ? atoi(e) != 0 : def;
}
static int defer_fences_enabled(void)
{
	static int v = -1;
	if (v < 0)
		v = tl_env_switch("ATL_GL_DEFER_FENCES", 1);
	return v;
}
static gboolean clear_tracking_off; /* an entry point that writes untracked was handed out */
static int skip_clears_enabled(void)
{
	static int v = -1;
	if (v < 0)
		v = tl_env_switch("ATL_GL_SKIP_CLEARS", 1);
	return v && !clear_tracking_off;
}
static int debug_enabled(void)
{
	static int v = -1;
	if (v < 0)
		v = tl_env_switch("ATL_DEBUG_GL_PASSES", 0);
	return v;
}
static long stat_clears, stat_clears_skipped, stat_fences, stat_fences_deferred, stat_fences_forced;

/* ---- what is known about textures, renderbuffers and framebuffers, per share group ---- */

enum { IMG_TEXTURE = 1, IMG_RENDERBUFFER = 2 };

struct tl_image {
	gboolean external; /* written outside what is followed here (EGL image, image unit): never assumed to hold a value */
	gboolean color_clean, depth_clean, stencil_clean;
	int color_kind; /* 0 float (glClearColor, glClearBufferfv), 1 int, 2 uint */
	uint32_t color_value[4];
	GLfloat depth_value;
	GLint stencil_value;
};

#define TL_MAX_COLOR 8
enum { ATT_NONE = 0, ATT_SIMPLE, ATT_OTHER }; /* ATT_OTHER: layered, a mipmap level, a cube face, not a 2D texture */

struct tl_attachment {
	int state;
	int kind;
	GLuint name;
	struct tl_image *image; /* ATT_SIMPLE */
};

struct tl_fbo {
	gboolean seen; /* an attachment was set through the wrappers: what is attached is known */
	struct tl_attachment color[TL_MAX_COLOR], depth, stencil;
};

struct tl_group {
	int contexts;
	gboolean shared; /* a second context joined, or a share context ATL did not create: nothing is tracked */
	GHashTable *images; /* (kind << 32 | name) -> struct tl_image */
	GHashTable *fbos;   /* name -> struct tl_fbo */
};

struct tl_sync;

struct tl_ctx {
	EGLContext context;
	struct tl_group *group;
	gboolean destroyed, current;
	int es3; /* -1 not known yet; the clear tracking uses GLES 3.0 queries */
	struct tl_sync *pending; /* fences not yet in the command stream */
};

static GMutex tl_lock; /* contexts, groups, sync objects */
static GHashTable *contexts; /* EGLContext -> struct tl_ctx */
static __thread struct tl_ctx *thread_ctx;

static struct tl_group *group_new(void)
{
	struct tl_group *g = g_new0(struct tl_group, 1);
	g->images = g_hash_table_new_full(g_int64_hash, g_int64_equal, g_free, g_free);
	g->fbos = g_hash_table_new_full(g_direct_hash, g_direct_equal, NULL, g_free);
	return g;
}

static void group_unref(struct tl_group *g)
{
	if (--g->contexts > 0)
		return;
	g_hash_table_destroy(g->images);
	g_hash_table_destroy(g->fbos);
	g_free(g);
}

void tl_gl_context_created(EGLContext context, EGLContext share_context)
{
	if (context == EGL_NO_CONTEXT)
		return;
	g_mutex_lock(&tl_lock);
	if (!contexts)
		contexts = g_hash_table_new(g_direct_hash, g_direct_equal);
	struct tl_ctx *c = g_new0(struct tl_ctx, 1);
	c->context = context;
	c->es3 = -1;
	struct tl_ctx *share = share_context != EGL_NO_CONTEXT ? g_hash_table_lookup(contexts, share_context) : NULL;
	if (share) {
		c->group = share->group;
		c->group->shared = TRUE;
	} else {
		c->group = group_new();
		c->group->shared = share_context != EGL_NO_CONTEXT;
	}
	c->group->contexts++;
	g_hash_table_insert(contexts, context, c);
	g_mutex_unlock(&tl_lock);
}

static void ctx_free_locked(struct tl_ctx *c)
{
	group_unref(c->group);
	g_free(c);
}

void tl_gl_context_destroyed(EGLContext context)
{
	g_mutex_lock(&tl_lock);
	struct tl_ctx *c = contexts ? g_hash_table_lookup(contexts, context) : NULL;
	if (c) {
		g_hash_table_remove(contexts, context);
		c->destroyed = TRUE;
		/* a context current on some thread is deleted when it stops being current (EGL 1.5,
		 * 3.7.1.3), and so is its tracking (tl_gl_before_make_current) */
		if (!c->current)
			ctx_free_locked(c);
	}
	g_mutex_unlock(&tl_lock);
}

void tl_gl_after_make_current(EGLContext context)
{
	g_mutex_lock(&tl_lock);
	struct tl_ctx *c = (contexts && context != EGL_NO_CONTEXT) ? g_hash_table_lookup(contexts, context) : NULL;
	if (c)
		c->current = TRUE;
	thread_ctx = c;
	g_mutex_unlock(&tl_lock);
}

/* the tracking of the context current on this thread, or NULL (one ATL did not create) */
static struct tl_ctx *cur(void)
{
	struct tl_ctx *c = thread_ctx;
	if (c && !c->destroyed && c->context == eglGetCurrentContext())
		return c;
	return NULL;
}

/* this context's writes are all seen here: it never shared its objects, and it is a GLES 3 context
 * (the queries the decisions use) */
static struct tl_group *tracked_group(void)
{
	struct tl_ctx *c = cur();
	if (!c || c->group->shared)
		return NULL;
	if (c->es3 < 0) {
		const char *version = (const char *)glGetString(GL_VERSION);
		int major = 0;
		c->es3 = version && sscanf(version, "OpenGL ES %d", &major) == 1 && major >= 3;
	}
	return c->es3 ? c->group : NULL;
}

static struct tl_image *image_lookup(struct tl_group *g, int kind, GLuint name, gboolean create)
{
	if (!name)
		return NULL;
	guint64 key = ((guint64)kind << 32) | name;
	struct tl_image *img = g_hash_table_lookup(g->images, &key);
	if (!img && create) {
		img = g_new0(struct tl_image, 1);
		guint64 *k = g_new(guint64, 1);
		*k = key;
		g_hash_table_insert(g->images, k, img);
	}
	return img;
}

static void image_dirty(struct tl_image *img)
{
	if (img)
		img->color_clean = img->depth_clean = img->stencil_clean = FALSE;
}

static GLuint bound_fbo(GLenum target)
{
	GLint name = 0;
	glGetIntegerv(target == GL_READ_FRAMEBUFFER ? GL_READ_FRAMEBUFFER_BINDING : GL_DRAW_FRAMEBUFFER_BINDING, &name);
	return (GLuint)name;
}

static struct tl_fbo *fbo_lookup(struct tl_group *g, GLuint name, gboolean create)
{
	if (!name)
		return NULL;
	struct tl_fbo *f = g_hash_table_lookup(g->fbos, GUINT_TO_POINTER(name));
	if (!f && create) {
		f = g_new0(struct tl_fbo, 1);
		g_hash_table_insert(g->fbos, GUINT_TO_POINTER(name), f);
	}
	return f;
}

/* every image attached to the framebuffer drawn into may have been written. A framebuffer whose
 * attachments were never set through the wrappers (one of ATL's own, an app's set up before ATL
 * created its context) holds no image known here, so it needs nothing */
static void draw_target_written(void)
{
	struct tl_group *g = tracked_group();
	if (!g)
		return;
	struct tl_fbo *f = fbo_lookup(g, bound_fbo(GL_DRAW_FRAMEBUFFER), FALSE);
	if (!f)
		return;
	for (int i = 0; i < TL_MAX_COLOR; i++)
		image_dirty(f->color[i].image);
	image_dirty(f->depth.image);
	image_dirty(f->stencil.image);
}

/* the texture bound to TARGET on the active unit gets new contents or storage. Only a 2D texture
 * can be an image known here (attached with glFramebufferTexture2D(GL_TEXTURE_2D) at level 0), and
 * a texture's target never changes, so writes through other targets cannot touch one */
static void bound_texture_written(GLenum target, gboolean external)
{
	struct tl_group *g = tracked_group();
	if (!g || target != GL_TEXTURE_2D)
		return;
	GLint name = 0;
	glGetIntegerv(GL_TEXTURE_BINDING_2D, &name);
	struct tl_image *img = image_lookup(g, IMG_TEXTURE, (GLuint)name, external);
	image_dirty(img);
	if (img && external)
		img->external = TRUE;
}

static void bound_renderbuffer_written(gboolean external)
{
	struct tl_group *g = tracked_group();
	if (!g)
		return;
	GLint name = 0;
	glGetIntegerv(GL_RENDERBUFFER_BINDING, &name);
	struct tl_image *img = image_lookup(g, IMG_RENDERBUFFER, (GLuint)name, external);
	image_dirty(img);
	if (img && external)
		img->external = TRUE;
}

/* what the GL now has attached at ATTACHMENT of the framebuffer bound to TARGET, read back (GLES
 * 3.0 queries only), so that a call that failed cannot leave a wrong record. SIMPLE_KIND/NAME: the
 * level-0 2D texture or renderbuffer the call just attached; anything else read back is ATT_OTHER
 * (a 2D array's layer 0 reads back like a 2D texture, so only the call itself can tell) */
static void attachment_read_back(struct tl_group *g, GLenum target, GLenum attachment, struct tl_attachment *a, int simple_kind, GLuint simple_name)
{
	GLint type = GL_NONE, name = 0;
	glGetFramebufferAttachmentParameteriv(target, attachment, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, &type);
	memset(a, 0, sizeof *a);
	if (type == GL_NONE)
		return;
	glGetFramebufferAttachmentParameteriv(target, attachment, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, &name);
	a->name = (GLuint)name;
	a->kind = type == GL_RENDERBUFFER ? IMG_RENDERBUFFER : IMG_TEXTURE;
	a->state = ATT_OTHER;
	if (simple_kind && a->kind == simple_kind && a->name == simple_name) {
		GLint level = 0, face = 0;
		if (a->kind == IMG_TEXTURE) {
			glGetFramebufferAttachmentParameteriv(target, attachment, GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL, &level);
			glGetFramebufferAttachmentParameteriv(target, attachment, GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE, &face);
		}
		if (level == 0 && face == 0) {
			a->state = ATT_SIMPLE;
			a->image = image_lookup(g, a->kind, a->name, TRUE);
		}
	}
}

/* KIND/NAME: the level-0 2D texture or renderbuffer the call attached, or 0 for anything else */
static void attachments_changed(GLenum target, GLenum attachment, int kind, GLuint name)
{
	struct tl_group *g = tracked_group();
	if (!g)
		return;
	struct tl_fbo *f = fbo_lookup(g, bound_fbo(target), TRUE);
	if (!f)
		return; /* the default framebuffer: an error for GL to report */
	f->seen = TRUE;
	GLenum points[2] = { attachment, GL_NONE };
	if (attachment == GL_DEPTH_STENCIL_ATTACHMENT)
		points[0] = GL_DEPTH_ATTACHMENT, points[1] = GL_STENCIL_ATTACHMENT;
	for (int i = 0; i < 2 && points[i] != GL_NONE; i++) {
		struct tl_attachment *a = NULL;
		if (points[i] >= GL_COLOR_ATTACHMENT0 && points[i] < GL_COLOR_ATTACHMENT0 + TL_MAX_COLOR)
			a = &f->color[points[i] - GL_COLOR_ATTACHMENT0];
		else if (points[i] == GL_DEPTH_ATTACHMENT)
			a = &f->depth;
		else if (points[i] == GL_STENCIL_ATTACHMENT)
			a = &f->stencil;
		if (!a)
			continue; /* an attachment point beyond those followed: an error, or never cleared through draw buffer 0 */
		attachment_read_back(g, target, points[i], a, kind, name);
	}
}

/* a deleted texture or renderbuffer: its name can come back for something else */
static void forget_image(struct tl_group *g, int kind, GLuint name)
{
	if (!name)
		return;
	GHashTableIter it;
	gpointer v;
	g_hash_table_iter_init(&it, g->fbos);
	while (g_hash_table_iter_next(&it, NULL, &v)) {
		struct tl_fbo *f = v;
		struct tl_attachment *atts[TL_MAX_COLOR + 2];
		for (int i = 0; i < TL_MAX_COLOR; i++)
			atts[i] = &f->color[i];
		atts[TL_MAX_COLOR] = &f->depth;
		atts[TL_MAX_COLOR + 1] = &f->stencil;
		for (int i = 0; i < TL_MAX_COLOR + 2; i++) {
			if (atts[i]->state != ATT_NONE && atts[i]->kind == kind && atts[i]->name == name) {
				/* detached from a bound framebuffer, left dangling on the others (GLES 3.2,
				 * 5.1.2): in both cases nothing is known about it any more */
				atts[i]->state = ATT_OTHER;
				atts[i]->image = NULL;
			}
		}
	}
	guint64 key = ((guint64)kind << 32) | name;
	g_hash_table_remove(g->images, &key);
}

/* ---- the clears ---- */

struct clear_values {
	int color_kind;
	uint32_t color[4];
	GLfloat depth;
	GLint stencil;
};

struct clear_plan {
	struct tl_fbo *fbo;     /* NULL: not followed (the default framebuffer, an unknown one, an untracked context) */
	gboolean full;           /* every pixel of the cleared buffers is written */
	struct tl_image *color, *depth, *stencil; /* the images the clear writes */
	struct clear_values v;
};

static gboolean attachment_plain(const struct tl_attachment *a)
{
	return a->state == ATT_NONE || (a->state == ATT_SIMPLE && a->image && !a->image->external);
}

/* which images a clear of these buffers writes on the bound draw framebuffer, and whether fully */
static void clear_plan(struct clear_plan *p, gboolean color, gboolean depth, gboolean stencil)
{
	memset(p, 0, sizeof *p);
	struct tl_group *g = tracked_group();
	if (!g)
		return;
	struct tl_fbo *f = fbo_lookup(g, bound_fbo(GL_DRAW_FRAMEBUFFER), FALSE);
	if (!f || !f->seen)
		return;
	p->fbo = f;
	if (glIsEnabled(GL_RASTERIZER_DISCARD)) {
		p->fbo = NULL; /* the clear does nothing (GLES 3.2, 15.2.3 / 12.1) */
		return;
	}
	GLint draw_buffer0 = GL_NONE;
	glGetIntegerv(GL_DRAW_BUFFER0, &draw_buffer0);
	if (color && draw_buffer0 == GL_COLOR_ATTACHMENT0 && f->color[0].state == ATT_SIMPLE)
		p->color = f->color[0].image;
	if (depth && f->depth.state == ATT_SIMPLE)
		p->depth = f->depth.image;
	if (stencil && f->stencil.state == ATT_SIMPLE)
		p->stencil = f->stencil.image;

	gboolean full = !glIsEnabled(GL_SCISSOR_TEST);
	for (int i = 0; i < TL_MAX_COLOR; i++)
		full = full && attachment_plain(&f->color[i]);
	full = full && attachment_plain(&f->depth) && attachment_plain(&f->stencil);
	if (full && color) {
		GLint max_draw_buffers = 1;
		glGetIntegerv(GL_MAX_DRAW_BUFFERS, &max_draw_buffers);
		for (int i = 1; i < max_draw_buffers && i < TL_MAX_COLOR && full; i++) {
			GLint db = GL_NONE;
			glGetIntegerv(GL_DRAW_BUFFER0 + i, &db);
			full = db == GL_NONE; /* a second draw buffer: not followed */
		}
		GLboolean mask[4] = { GL_FALSE };
		glGetBooleanv(GL_COLOR_WRITEMASK, mask);
		full = full && mask[0] && mask[1] && mask[2] && mask[3];
	}
	if (full && depth) {
		GLboolean mask = GL_FALSE;
		glGetBooleanv(GL_DEPTH_WRITEMASK, &mask);
		full = mask;
	}
	if (full && p->stencil) {
		/* every bit of the stencil buffer (GLES stencil buffers have at most 8 bits) */
		GLint mask = 0;
		glGetIntegerv(GL_STENCIL_WRITEMASK, &mask);
		full = ((GLuint)mask & 0xffu) == 0xffu;
	}
	p->full = full && glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
}

/* the clear can be left out: it is full and each image it writes holds exactly that value */
static gboolean clear_redundant(const struct clear_plan *p)
{
	if (!skip_clears_enabled() || !p->fbo || !p->full || !(p->color || p->depth || p->stencil))
		return FALSE;
	if (p->color && !(p->color->color_clean && p->color->color_kind == p->v.color_kind && !memcmp(p->color->color_value, p->v.color, sizeof p->v.color)))
		return FALSE;
	if (p->depth && !(p->depth->depth_clean && !memcmp(&p->depth->depth_value, &p->v.depth, sizeof p->v.depth)))
		return FALSE;
	if (p->stencil && !(p->stencil->stencil_clean && p->stencil->stencil_value == p->v.stencil))
		return FALSE;
	return TRUE;
}

/* after a clear: the images it wrote hold the value (a full clear) or are unknown (a partial one) */
static void clear_record(const struct clear_plan *p, gboolean color)
{
	if (!p->fbo)
		return;
	if (color) {
		for (int i = 0; i < TL_MAX_COLOR; i++)
			if (p->fbo->color[i].image && p->fbo->color[i].image != p->color)
				image_dirty(p->fbo->color[i].image);
	}
	if (p->color) {
		p->color->color_clean = p->full;
		p->color->color_kind = p->v.color_kind;
		memcpy(p->color->color_value, p->v.color, sizeof p->v.color);
	}
	if (p->depth) {
		p->depth->depth_clean = p->full;
		p->depth->depth_value = p->v.depth;
	}
	if (p->stencil) {
		p->stencil->stencil_clean = p->full;
		p->stencil->stencil_value = p->v.stencil;
	}
}

static void count_clear(const struct clear_plan *p, gboolean skipped)
{
	if (!p->fbo)
		return;
	stat_clears++;
	if (skipped)
		stat_clears_skipped++;
}

void bionic_glClear(GLbitfield mask)
{
	const GLbitfield known = GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT;
	struct clear_plan p;
	gboolean color = (mask & GL_COLOR_BUFFER_BIT) != 0, depth = (mask & GL_DEPTH_BUFFER_BIT) != 0, stencil = (mask & GL_STENCIL_BUFFER_BIT) != 0;
	if (mask & ~known) {
		glClear(mask); /* GL_INVALID_VALUE, nothing cleared */
		return;
	}
	clear_plan(&p, color, depth, stencil);
	if (p.fbo) {
		GLfloat cc[4] = { 0 };
		glGetFloatv(GL_COLOR_CLEAR_VALUE, cc);
		memcpy(p.v.color, cc, sizeof cc);
		p.v.color_kind = 0;
		glGetFloatv(GL_DEPTH_CLEAR_VALUE, &p.v.depth);
		glGetIntegerv(GL_STENCIL_CLEAR_VALUE, &p.v.stencil);
	}
	gboolean skip = clear_redundant(&p);
	count_clear(&p, skip);
	if (!skip)
		glClear(mask);
	clear_record(&p, color);
}

/* glClearBuffer*: TRUE when the clear must be done; plan the record in P */
static gboolean clear_buffer_begin(struct clear_plan *p, GLenum buffer, GLint drawbuffer, int color_kind, const void *color, GLfloat depth, GLint stencil)
{
	gboolean is_color = buffer == GL_COLOR, is_depth = buffer == GL_DEPTH || buffer == GL_DEPTH_STENCIL,
	         is_stencil = buffer == GL_STENCIL || buffer == GL_DEPTH_STENCIL;
	clear_plan(p, is_color, is_depth, is_stencil);
	if (is_color && drawbuffer != 0 && p->fbo) {
		/* another draw buffer: its image is written, which one is not followed */
		p->full = FALSE;
		p->color = NULL;
	}
	p->v.color_kind = color_kind;
	if (is_color && color)
		memcpy(p->v.color, color, sizeof p->v.color);
	p->v.depth = depth < 0 ? 0 : depth > 1 ? 1 : depth; /* the depth clear value is clamped to [0, 1] */
	p->v.stencil = stencil;
	gboolean skip = clear_redundant(p);
	count_clear(p, skip);
	return !skip;
}

void bionic_glClearBufferfv(GLenum buffer, GLint drawbuffer, const GLfloat *value)
{
	struct clear_plan p;
	if (!value || (buffer != GL_COLOR && buffer != GL_DEPTH)) {
		glClearBufferfv(buffer, drawbuffer, value); /* an error for GL to report */
		return;
	}
	if (clear_buffer_begin(&p, buffer, drawbuffer, 0, value, buffer == GL_DEPTH ? value[0] : 0, 0))
		glClearBufferfv(buffer, drawbuffer, value);
	clear_record(&p, buffer == GL_COLOR);
}

void bionic_glClearBufferiv(GLenum buffer, GLint drawbuffer, const GLint *value)
{
	struct clear_plan p;
	if (!value || (buffer != GL_COLOR && buffer != GL_STENCIL)) {
		glClearBufferiv(buffer, drawbuffer, value);
		return;
	}
	if (clear_buffer_begin(&p, buffer, drawbuffer, 1, value, 0, buffer == GL_STENCIL ? value[0] : 0))
		glClearBufferiv(buffer, drawbuffer, value);
	clear_record(&p, buffer == GL_COLOR);
}

void bionic_glClearBufferuiv(GLenum buffer, GLint drawbuffer, const GLuint *value)
{
	struct clear_plan p;
	if (!value || buffer != GL_COLOR) {
		glClearBufferuiv(buffer, drawbuffer, value);
		return;
	}
	if (clear_buffer_begin(&p, buffer, drawbuffer, 2, value, 0, 0))
		glClearBufferuiv(buffer, drawbuffer, value);
	clear_record(&p, TRUE);
}

void bionic_glClearBufferfi(GLenum buffer, GLint drawbuffer, GLfloat depth, GLint stencil)
{
	struct clear_plan p;
	if (buffer != GL_DEPTH_STENCIL) {
		glClearBufferfi(buffer, drawbuffer, depth, stencil);
		return;
	}
	if (clear_buffer_begin(&p, buffer, drawbuffer, 0, NULL, depth, stencil))
		glClearBufferfi(buffer, drawbuffer, depth, stencil);
	clear_record(&p, FALSE);
}

/* ---- attachments ---- */

void bionic_glFramebufferTexture2D(GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level)
{
	glFramebufferTexture2D(target, attachment, textarget, texture, level);
	gboolean simple = textarget == GL_TEXTURE_2D && level == 0 && texture;
	attachments_changed(target, attachment, simple ? IMG_TEXTURE : 0, simple ? texture : 0);
}

void bionic_glFramebufferTextureLayer(GLenum target, GLenum attachment, GLuint texture, GLint level, GLint layer)
{
	glFramebufferTextureLayer(target, attachment, texture, level, layer);
	attachments_changed(target, attachment, 0, 0);
}

void bionic_glFramebufferTexture(GLenum target, GLenum attachment, GLuint texture, GLint level)
{
	glFramebufferTexture(target, attachment, texture, level); /* layered */
	attachments_changed(target, attachment, 0, 0);
}

void bionic_glFramebufferRenderbuffer(GLenum target, GLenum attachment, GLenum renderbuffertarget, GLuint renderbuffer)
{
	glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
	attachments_changed(target, attachment, renderbuffer ? IMG_RENDERBUFFER : 0, renderbuffer);
}

/* ---- writes into the bound framebuffer ---- */

void bionic_glDrawArrays(GLenum mode, GLint first, GLsizei count)
{
	draw_target_written();
	glDrawArrays(mode, first, count);
}

void bionic_glDrawElements(GLenum mode, GLsizei count, GLenum type, const void *indices)
{
	draw_target_written();
	glDrawElements(mode, count, type, indices);
}

void bionic_glDrawRangeElements(GLenum mode, GLuint start, GLuint end, GLsizei count, GLenum type, const void *indices)
{
	draw_target_written();
	glDrawRangeElements(mode, start, end, count, type, indices);
}

void bionic_glDrawArraysInstanced(GLenum mode, GLint first, GLsizei count, GLsizei instancecount)
{
	draw_target_written();
	glDrawArraysInstanced(mode, first, count, instancecount);
}

void bionic_glDrawElementsInstanced(GLenum mode, GLsizei count, GLenum type, const void *indices, GLsizei instancecount)
{
	draw_target_written();
	glDrawElementsInstanced(mode, count, type, indices, instancecount);
}

void bionic_glDrawArraysIndirect(GLenum mode, const void *indirect)
{
	draw_target_written();
	glDrawArraysIndirect(mode, indirect);
}

void bionic_glDrawElementsIndirect(GLenum mode, GLenum type, const void *indirect)
{
	draw_target_written();
	glDrawElementsIndirect(mode, type, indirect);
}

void bionic_glDrawElementsBaseVertex(GLenum mode, GLsizei count, GLenum type, const void *indices, GLint basevertex)
{
	draw_target_written();
	glDrawElementsBaseVertex(mode, count, type, indices, basevertex);
}

void bionic_glDrawRangeElementsBaseVertex(GLenum mode, GLuint start, GLuint end, GLsizei count, GLenum type, const void *indices, GLint basevertex)
{
	draw_target_written();
	glDrawRangeElementsBaseVertex(mode, start, end, count, type, indices, basevertex);
}

void bionic_glDrawElementsInstancedBaseVertex(GLenum mode, GLsizei count, GLenum type, const void *indices, GLsizei instancecount, GLint basevertex)
{
	draw_target_written();
	glDrawElementsInstancedBaseVertex(mode, count, type, indices, instancecount, basevertex);
}

void bionic_glBlitFramebuffer(GLint srcX0, GLint srcY0, GLint srcX1, GLint srcY1, GLint dstX0, GLint dstY0, GLint dstX1, GLint dstY1, GLbitfield mask, GLenum filter)
{
	draw_target_written();
	glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
}

/* the contents of the listed attachments become undefined */
static void invalidated(GLenum target, GLsizei n, const GLenum *attachments)
{
	struct tl_group *g = tracked_group();
	if (!g || !attachments)
		return;
	struct tl_fbo *f = fbo_lookup(g, bound_fbo(target), FALSE);
	if (!f)
		return;
	for (int i = 0; i < n; i++) {
		GLenum a = attachments[i];
		if (a >= GL_COLOR_ATTACHMENT0 && a < GL_COLOR_ATTACHMENT0 + TL_MAX_COLOR)
			image_dirty(f->color[a - GL_COLOR_ATTACHMENT0].image);
		else if (a == GL_DEPTH_ATTACHMENT)
			image_dirty(f->depth.image);
		else if (a == GL_STENCIL_ATTACHMENT)
			image_dirty(f->stencil.image);
		else if (a == GL_DEPTH_STENCIL_ATTACHMENT)
			image_dirty(f->depth.image), image_dirty(f->stencil.image);
	}
}

void bionic_glInvalidateFramebuffer(GLenum target, GLsizei numAttachments, const GLenum *attachments)
{
	invalidated(target, numAttachments, attachments);
	glInvalidateFramebuffer(target, numAttachments, attachments);
}

void bionic_glInvalidateSubFramebuffer(GLenum target, GLsizei numAttachments, const GLenum *attachments, GLint x, GLint y, GLsizei width, GLsizei height)
{
	invalidated(target, numAttachments, attachments);
	glInvalidateSubFramebuffer(target, numAttachments, attachments, x, y, width, height);
}

/* ---- writes into textures and renderbuffers ---- */

void bionic_glTexImage2D(GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLint border, GLenum format, GLenum type, const void *pixels)
{
	bound_texture_written(target, FALSE);
	glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
}

void bionic_glTexImage3D(GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLsizei depth, GLint border, GLenum format, GLenum type, const void *pixels)
{
	bound_texture_written(target, FALSE);
	glTexImage3D(target, level, internalformat, width, height, depth, border, format, type, pixels);
}

void bionic_glTexSubImage2D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const void *pixels)
{
	bound_texture_written(target, FALSE);
	glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
}

void bionic_glTexSubImage3D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLenum type, const void *pixels)
{
	bound_texture_written(target, FALSE);
	glTexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels);
}

void bionic_glCompressedTexImage2D(GLenum target, GLint level, GLenum internalformat, GLsizei width, GLsizei height, GLint border, GLsizei imageSize, const void *data)
{
	bound_texture_written(target, FALSE);
	glCompressedTexImage2D(target, level, internalformat, width, height, border, imageSize, data);
}

void bionic_glCompressedTexImage3D(GLenum target, GLint level, GLenum internalformat, GLsizei width, GLsizei height, GLsizei depth, GLint border, GLsizei imageSize, const void *data)
{
	bound_texture_written(target, FALSE);
	glCompressedTexImage3D(target, level, internalformat, width, height, depth, border, imageSize, data);
}

void bionic_glCompressedTexSubImage2D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLsizei imageSize, const void *data)
{
	bound_texture_written(target, FALSE);
	glCompressedTexSubImage2D(target, level, xoffset, yoffset, width, height, format, imageSize, data);
}

void bionic_glCompressedTexSubImage3D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLsizei imageSize, const void *data)
{
	bound_texture_written(target, FALSE);
	glCompressedTexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, imageSize, data);
}

void bionic_glCopyTexImage2D(GLenum target, GLint level, GLenum internalformat, GLint x, GLint y, GLsizei width, GLsizei height, GLint border)
{
	bound_texture_written(target, FALSE);
	glCopyTexImage2D(target, level, internalformat, x, y, width, height, border);
}

void bionic_glCopyTexSubImage2D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint x, GLint y, GLsizei width, GLsizei height)
{
	bound_texture_written(target, FALSE);
	glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
}

void bionic_glCopyTexSubImage3D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLint x, GLint y, GLsizei width, GLsizei height)
{
	bound_texture_written(target, FALSE);
	glCopyTexSubImage3D(target, level, xoffset, yoffset, zoffset, x, y, width, height);
}

void bionic_glTexStorage2D(GLenum target, GLsizei levels, GLenum internalformat, GLsizei width, GLsizei height)
{
	bound_texture_written(target, FALSE);
	glTexStorage2D(target, levels, internalformat, width, height);
}

void bionic_glTexStorage3D(GLenum target, GLsizei levels, GLenum internalformat, GLsizei width, GLsizei height, GLsizei depth)
{
	bound_texture_written(target, FALSE);
	glTexStorage3D(target, levels, internalformat, width, height, depth);
}

void bionic_glTexStorage2DMultisample(GLenum target, GLsizei samples, GLenum internalformat, GLsizei width, GLsizei height, GLboolean fixedsamplelocations)
{
	bound_texture_written(target, FALSE);
	glTexStorage2DMultisample(target, samples, internalformat, width, height, fixedsamplelocations);
}

void bionic_glTexStorage3DMultisample(GLenum target, GLsizei samples, GLenum internalformat, GLsizei width, GLsizei height, GLsizei depth, GLboolean fixedsamplelocations)
{
	bound_texture_written(target, FALSE);
	glTexStorage3DMultisample(target, samples, internalformat, width, height, depth, fixedsamplelocations);
}

void bionic_glGenerateMipmap(GLenum target)
{
	bound_texture_written(target, FALSE);
	glGenerateMipmap(target);
}

void bionic_glRenderbufferStorage(GLenum target, GLenum internalformat, GLsizei width, GLsizei height)
{
	bound_renderbuffer_written(FALSE);
	glRenderbufferStorage(target, internalformat, width, height);
}

void bionic_glRenderbufferStorageMultisample(GLenum target, GLsizei samples, GLenum internalformat, GLsizei width, GLsizei height)
{
	bound_renderbuffer_written(FALSE);
	glRenderbufferStorageMultisample(target, samples, internalformat, width, height);
}

void bionic_glCopyImageSubData(GLuint srcName, GLenum srcTarget, GLint srcLevel, GLint srcX, GLint srcY, GLint srcZ, GLuint dstName, GLenum dstTarget, GLint dstLevel, GLint dstX, GLint dstY, GLint dstZ, GLsizei srcWidth, GLsizei srcHeight, GLsizei srcDepth)
{
	struct tl_group *g = tracked_group();
	if (g)
		image_dirty(image_lookup(g, dstTarget == GL_RENDERBUFFER ? IMG_RENDERBUFFER : IMG_TEXTURE, dstName, FALSE));
	glCopyImageSubData(srcName, srcTarget, srcLevel, srcX, srcY, srcZ, dstName, dstTarget, dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, srcDepth);
}

/* a texture bound to an image unit can be written by any shader invocation while it is bound: it
 * is never assumed to hold a cleared value again */
void bionic_glBindImageTexture(GLuint unit, GLuint texture, GLint level, GLboolean layered, GLint layer, GLenum access, GLenum format)
{
	struct tl_group *g = tracked_group();
	if (g && texture && access != GL_READ_ONLY) {
		struct tl_image *img = image_lookup(g, IMG_TEXTURE, texture, TRUE);
		image_dirty(img);
		img->external = TRUE;
	}
	glBindImageTexture(unit, texture, level, layered, layer, access, format);
}

/* extensions, reached through eglGetProcAddress (egl.c resolves "bionic_" names first) */
void bionic_glEGLImageTargetTexture2DOES(GLenum target, GLeglImageOES image)
{
	static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC real;
	if (!real)
		real = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
	bound_texture_written(target, TRUE); /* its contents are written wherever the EGL image is */
	real(target, image);
}

void bionic_glEGLImageTargetRenderbufferStorageOES(GLenum target, GLeglImageOES image)
{
	static PFNGLEGLIMAGETARGETRENDERBUFFERSTORAGEOESPROC real;
	if (!real)
		real = (PFNGLEGLIMAGETARGETRENDERBUFFERSTORAGEOESPROC)eglGetProcAddress("glEGLImageTargetRenderbufferStorageOES");
	bound_renderbuffer_written(TRUE);
	real(target, image);
}

void bionic_glDiscardFramebufferEXT(GLenum target, GLsizei numAttachments, const GLenum *attachments)
{
	static PFNGLDISCARDFRAMEBUFFEREXTPROC real;
	if (!real)
		real = (PFNGLDISCARDFRAMEBUFFEREXTPROC)eglGetProcAddress("glDiscardFramebufferEXT");
	invalidated(target, numAttachments, attachments);
	real(target, numAttachments, attachments);
}

/* the OES/EXT names of core GLES 3.2 commands */
void bionic_glDrawElementsBaseVertexOES(GLenum mode, GLsizei count, GLenum type, const void *indices, GLint basevertex)
{
	bionic_glDrawElementsBaseVertex(mode, count, type, indices, basevertex);
}
void bionic_glDrawElementsBaseVertexEXT(GLenum mode, GLsizei count, GLenum type, const void *indices, GLint basevertex)
{
	bionic_glDrawElementsBaseVertex(mode, count, type, indices, basevertex);
}
void bionic_glDrawRangeElementsBaseVertexOES(GLenum mode, GLuint start, GLuint end, GLsizei count, GLenum type, const void *indices, GLint basevertex)
{
	bionic_glDrawRangeElementsBaseVertex(mode, start, end, count, type, indices, basevertex);
}
void bionic_glDrawRangeElementsBaseVertexEXT(GLenum mode, GLuint start, GLuint end, GLsizei count, GLenum type, const void *indices, GLint basevertex)
{
	bionic_glDrawRangeElementsBaseVertex(mode, start, end, count, type, indices, basevertex);
}
void bionic_glDrawElementsInstancedBaseVertexOES(GLenum mode, GLsizei count, GLenum type, const void *indices, GLsizei instancecount, GLint basevertex)
{
	bionic_glDrawElementsInstancedBaseVertex(mode, count, type, indices, instancecount, basevertex);
}
void bionic_glDrawElementsInstancedBaseVertexEXT(GLenum mode, GLsizei count, GLenum type, const void *indices, GLsizei instancecount, GLint basevertex)
{
	bionic_glDrawElementsInstancedBaseVertex(mode, count, type, indices, instancecount, basevertex);
}
void bionic_glDrawArraysInstancedEXT(GLenum mode, GLint first, GLsizei count, GLsizei instancecount)
{
	bionic_glDrawArraysInstanced(mode, first, count, instancecount);
}
void bionic_glDrawElementsInstancedEXT(GLenum mode, GLsizei count, GLenum type, const void *indices, GLsizei instancecount)
{
	bionic_glDrawElementsInstanced(mode, count, type, indices, instancecount);
}
void bionic_glCopyImageSubDataEXT(GLuint srcName, GLenum srcTarget, GLint srcLevel, GLint srcX, GLint srcY, GLint srcZ, GLuint dstName, GLenum dstTarget, GLint dstLevel, GLint dstX, GLint dstY, GLint dstZ, GLsizei srcWidth, GLsizei srcHeight, GLsizei srcDepth)
{
	bionic_glCopyImageSubData(srcName, srcTarget, srcLevel, srcX, srcY, srcZ, dstName, dstTarget, dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, srcDepth);
}
void bionic_glCopyImageSubDataOES(GLuint srcName, GLenum srcTarget, GLint srcLevel, GLint srcX, GLint srcY, GLint srcZ, GLuint dstName, GLenum dstTarget, GLint dstLevel, GLint dstX, GLint dstY, GLint dstZ, GLsizei srcWidth, GLsizei srcHeight, GLsizei srcDepth)
{
	bionic_glCopyImageSubData(srcName, srcTarget, srcLevel, srcX, srcY, srcZ, dstName, dstTarget, dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, srcDepth);
}
void bionic_glFramebufferTextureEXT(GLenum target, GLenum attachment, GLuint texture, GLint level)
{
	bionic_glFramebufferTexture(target, attachment, texture, level);
}
void bionic_glFramebufferTextureOES(GLenum target, GLenum attachment, GLuint texture, GLint level)
{
	bionic_glFramebufferTexture(target, attachment, texture, level);
}

/* ---- deletion ---- */

void bionic_glDeleteTextures(GLsizei n, const GLuint *textures)
{
	struct tl_group *g = tracked_group();
	if (g && textures)
		for (int i = 0; i < n; i++)
			forget_image(g, IMG_TEXTURE, textures[i]);
	glDeleteTextures(n, textures);
}

void bionic_glDeleteRenderbuffers(GLsizei n, const GLuint *renderbuffers)
{
	struct tl_group *g = tracked_group();
	if (g && renderbuffers)
		for (int i = 0; i < n; i++)
			forget_image(g, IMG_RENDERBUFFER, renderbuffers[i]);
	glDeleteRenderbuffers(n, renderbuffers);
}

void bionic_glDeleteFramebuffers(GLsizei n, const GLuint *framebuffers)
{
	struct tl_group *g = tracked_group();
	if (g && framebuffers)
		for (int i = 0; i < n; i++)
			if (framebuffers[i])
				g_hash_table_remove(g->fbos, GUINT_TO_POINTER(framebuffers[i]));
	glDeleteFramebuffers(n, framebuffers);
}

/* ---- sync objects ---- */

struct tl_real_sync {
	GLsync sync;
	int refs;
};

struct tl_sync {
	struct tl_real_sync *real; /* NULL while pending */
	struct tl_ctx *owner;      /* while pending: the context whose next flush creates it */
	struct tl_sync *next_pending;
};

static GHashTable *live_syncs; /* the sync objects handed out (glIsSync, and to tell ours from garbage) */
static GCond materialized;

static struct tl_sync *sync_lookup_locked(GLsync handle)
{
	if (!handle || !live_syncs)
		return NULL;
	return g_hash_table_contains(live_syncs, handle) ? (struct tl_sync *)handle : NULL;
}

static void real_unref_locked(struct tl_real_sync *r)
{
	if (--r->refs == 0) {
		glDeleteSync(r->sync);
		g_free(r);
	}
}

/* put the context's pending fences into its command stream: one fence covers them all. On the
 * thread the context is current on, with tl_lock held */
static void materialize_locked(struct tl_ctx *c)
{
	if (!c || !c->pending)
		return;
	struct tl_real_sync *real = g_new0(struct tl_real_sync, 1);
	real->sync = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
	for (struct tl_sync *s = c->pending, *next; s; s = next) {
		next = s->next_pending;
		s->real = real;
		s->owner = NULL;
		s->next_pending = NULL;
		real->refs++;
	}
	c->pending = NULL;
	g_cond_broadcast(&materialized);
}

static void debug_report(void)
{
	static gint64 last;
	if (!debug_enabled())
		return;
	gint64 now = g_get_monotonic_time();
	if (now - last < 10 * G_USEC_PER_SEC)
		return;
	last = now;
	fprintf(stderr, "[gl_passes] clears %ld, left out %ld; fences %ld, deferred %ld, created early for a wait %ld%s\n",
	        stat_clears, stat_clears_skipped, stat_fences, stat_fences_deferred, stat_fences_forced,
	        clear_tracking_off ? " (clear tracking off)" : "");
}

void tl_gl_flush_point(void)
{
	struct tl_ctx *c = cur();
	if (c && c->pending) {
		g_mutex_lock(&tl_lock);
		materialize_locked(c);
		g_mutex_unlock(&tl_lock);
	}
	debug_report();
}

void tl_gl_before_make_current(void)
{
	struct tl_ctx *c = cur();
	if (!c)
		return;
	g_mutex_lock(&tl_lock);
	materialize_locked(c);
	c->current = FALSE;
	if (c->destroyed)
		ctx_free_locked(c); /* eglDestroyContext came while it was current here */
	thread_ctx = NULL;
	g_mutex_unlock(&tl_lock);
}

GLsync bionic_glFenceSync(GLenum condition, GLbitfield flags)
{
	if (condition != GL_SYNC_GPU_COMMANDS_COMPLETE || flags != 0)
		return glFenceSync(condition, flags); /* an error for GL to report; no sync is created */
	struct tl_ctx *c = cur();
	struct tl_sync *s = g_new0(struct tl_sync, 1);
	g_mutex_lock(&tl_lock);
	if (!live_syncs)
		live_syncs = g_hash_table_new(g_direct_hash, g_direct_equal);
	stat_fences++;
	if (defer_fences_enabled() && c && !c->group->shared && c->group->contexts == 1) {
		s->owner = c;
		s->next_pending = c->pending;
		c->pending = s;
		stat_fences_deferred++;
	} else {
		GLsync real = glFenceSync(condition, flags);
		if (!real) {
			g_mutex_unlock(&tl_lock);
			g_free(s);
			return NULL;
		}
		s->real = g_new0(struct tl_real_sync, 1);
		s->real->sync = real;
		s->real->refs = 1;
	}
	g_hash_table_add(live_syncs, s);
	g_mutex_unlock(&tl_lock);
	return (GLsync)s;
}

GLboolean bionic_glIsSync(GLsync sync)
{
	g_mutex_lock(&tl_lock);
	GLboolean ret = sync_lookup_locked(sync) ? GL_TRUE : GL_FALSE;
	g_mutex_unlock(&tl_lock);
	return ret;
}

void bionic_glDeleteSync(GLsync sync)
{
	if (!sync)
		return; /* silently ignored (GLES 3.2, 4.1.1) */
	g_mutex_lock(&tl_lock);
	struct tl_sync *s = sync_lookup_locked(sync);
	if (!s) {
		g_mutex_unlock(&tl_lock);
		glDeleteSync(sync); /* not a sync object: GL_INVALID_VALUE from GL */
		return;
	}
	g_hash_table_remove(live_syncs, s);
	if (!s->real) {
		for (struct tl_sync **pp = &s->owner->pending; *pp; pp = &(*pp)->next_pending) {
			if (*pp == s) {
				*pp = s->next_pending;
				break;
			}
		}
	} else {
		real_unref_locked(s->real);
	}
	g_free(s);
	g_mutex_unlock(&tl_lock);
}

/* with tl_lock held: a pending fence of another context, waited for until that context creates it
 * (at most timeout ns); NULL if it has not by then or the sync was deleted meanwhile */
static struct tl_real_sync *wait_materialized_locked(struct tl_sync *s, GLuint64 timeout)
{
	gint64 end = timeout == GL_TIMEOUT_IGNORED ? G_MAXINT64 : g_get_monotonic_time() + (gint64)(timeout / 1000);
	while (sync_lookup_locked((GLsync)s) && !s->real) {
		if (end == G_MAXINT64)
			g_cond_wait(&materialized, &tl_lock);
		else if (!g_cond_wait_until(&materialized, &tl_lock, end))
			break;
	}
	return sync_lookup_locked((GLsync)s) ? s->real : NULL;
}

GLenum bionic_glClientWaitSync(GLsync sync, GLbitfield flags, GLuint64 timeout)
{
	g_mutex_lock(&tl_lock);
	struct tl_sync *s = sync_lookup_locked(sync);
	if (!s) {
		g_mutex_unlock(&tl_lock);
		return glClientWaitSync(sync, flags, timeout); /* GL_INVALID_VALUE, GL_WAIT_FAILED */
	}
	if (!s->real) {
		struct tl_ctx *c = cur();
		if (s->owner == c) {
			if (timeout == 0 && !(flags & GL_SYNC_FLUSH_COMMANDS_BIT)) {
				g_mutex_unlock(&tl_lock); /* its commands have not been flushed: not complete */
				return GL_TIMEOUT_EXPIRED;
			}
			stat_fences_forced++;
			materialize_locked(c);
		} else {
			gint64 start = g_get_monotonic_time();
			if (!wait_materialized_locked(s, timeout)) {
				g_mutex_unlock(&tl_lock);
				return GL_TIMEOUT_EXPIRED;
			}
			if (timeout != GL_TIMEOUT_IGNORED) {
				GLuint64 spent = (GLuint64)(g_get_monotonic_time() - start) * 1000;
				timeout = spent >= timeout ? 0 : timeout - spent;
			}
		}
	}
	struct tl_real_sync *r = s->real;
	r->refs++; /* kept while waiting unlocked, should another thread delete the sync */
	g_mutex_unlock(&tl_lock);
	GLenum ret = glClientWaitSync(r->sync, flags, timeout);
	g_mutex_lock(&tl_lock);
	real_unref_locked(r);
	g_mutex_unlock(&tl_lock);
	return ret;
}

void bionic_glWaitSync(GLsync sync, GLbitfield flags, GLuint64 timeout)
{
	g_mutex_lock(&tl_lock);
	struct tl_sync *s = sync_lookup_locked(sync);
	if (!s) {
		g_mutex_unlock(&tl_lock);
		glWaitSync(sync, flags, timeout); /* GL_INVALID_VALUE */
		return;
	}
	if (!s->real) {
		struct tl_ctx *c = cur();
		if (s->owner == c) {
			stat_fences_forced++;
			materialize_locked(c);
		} else if (!wait_materialized_locked(s, GL_TIMEOUT_IGNORED)) {
			g_mutex_unlock(&tl_lock);
			return;
		}
	}
	glWaitSync(s->real->sync, flags, timeout);
	g_mutex_unlock(&tl_lock);
}

void bionic_glGetSynciv(GLsync sync, GLenum pname, GLsizei count, GLsizei *length, GLint *values)
{
	g_mutex_lock(&tl_lock);
	struct tl_sync *s = sync_lookup_locked(sync);
	if (!s) {
		g_mutex_unlock(&tl_lock);
		glGetSynciv(sync, pname, count, length, values); /* GL_INVALID_VALUE */
		return;
	}
	if (!s->real) {
		GLint v = 0;
		gboolean known = TRUE;
		switch (pname) {
			case GL_OBJECT_TYPE: v = GL_SYNC_FENCE; break;
			case GL_SYNC_STATUS: v = GL_UNSIGNALED; break;
			case GL_SYNC_CONDITION: v = GL_SYNC_GPU_COMMANDS_COMPLETE; break;
			case GL_SYNC_FLAGS: v = 0; break;
			default: known = FALSE;
		}
		if (known && count >= 0) {
			g_mutex_unlock(&tl_lock);
			if (count > 0 && values)
				values[0] = v;
			if (length)
				*length = count > 0 ? 1 : 0;
			return;
		}
		/* an invalid pname or count: GL reports it, on the real sync */
		struct tl_ctx *c = cur();
		if (s->owner == c)
			materialize_locked(c);
		else if (!wait_materialized_locked(s, GL_TIMEOUT_IGNORED)) {
			g_mutex_unlock(&tl_lock);
			return;
		}
	}
	glGetSynciv(s->real->sync, pname, count, length, values);
	g_mutex_unlock(&tl_lock);
}

void bionic_glFlush(void)
{
	tl_gl_flush_point();
	glFlush();
}

void bionic_glFinish(void)
{
	tl_gl_flush_point();
	glFinish();
}

/* ---- entry points from eglGetProcAddress ---- */

/* egl.c hands out a bionic_ wrapper for a name when there is one (PROC is then that wrapper) and
 * reports every name here: one that could write a texture or renderbuffer without passing through
 * the wrappers makes the clear tracking unreliable, so it is turned off for good */
void tl_gl_proc_requested(const char *procname, void *proc)
{
	if (proc || !procname || strncmp(procname, "gl", 2))
		return;
	static const char *const writes[] = { "Draw", "Clear", "Blit", "Copy", "Tex", "Framebuffer", "Renderbuffer",
	                                      "Discard", "Invalidate", "EGLImage", "Image", "Dispatch", "Resolve", NULL };
	for (int i = 0; writes[i]; i++) {
		if (strstr(procname, writes[i])) {
			if (!clear_tracking_off)
				fprintf(stderr, "[gl_passes] the app uses %s, which ATL does not follow: clears are no longer left out\n", procname);
			clear_tracking_off = TRUE;
			return;
		}
	}
}
