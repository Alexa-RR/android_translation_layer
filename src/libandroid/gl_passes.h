/* render passes on a tiling GPU: an app's fences deferred to the next flush, and clears that would
 * write what the buffer already holds left out (gl_passes.c) */
#ifndef ATL_GL_PASSES_H
#define ATL_GL_PASSES_H

#include <EGL/egl.h>

/* hooks for egl.c: a context created or destroyed (bionic_eglCreateContext/eglDestroyContext), a
 * context about to stop being current on this thread and the one current after eglMakeCurrent, a
 * flush point (eglSwapBuffers, eglWaitGL, eglWaitClient, eglReleaseThread), and every name handed
 * out by eglGetProcAddress with the bionic_ wrapper found for it (NULL if none) */
void tl_gl_context_created(EGLContext context, EGLContext share_context);
void tl_gl_context_destroyed(EGLContext context);
void tl_gl_before_make_current(void);
void tl_gl_after_make_current(EGLContext context);
void tl_gl_flush_point(void);
void tl_gl_proc_requested(const char *procname, void *proc);

/* the wrapped GLES calls the Java bindings (android.opengl.GLES20, GLUtils, GL10) make, so that
 * their writes are seen too (GL types spelled out, to need no GL header here) */
void bionic_glClear(unsigned int mask);
void bionic_glDrawArrays(unsigned int mode, int first, int count);
void bionic_glDrawElements(unsigned int mode, int count, unsigned int type, const void *indices);
void bionic_glFramebufferTexture2D(unsigned int target, unsigned int attachment, unsigned int textarget, unsigned int texture, int level);
void bionic_glTexImage2D(unsigned int target, int level, int internalformat, int width, int height, int border, unsigned int format, unsigned int type, const void *pixels);
void bionic_glTexSubImage2D(unsigned int target, int level, int xoffset, int yoffset, int width, int height, unsigned int format, unsigned int type, const void *pixels);
void bionic_glGenerateMipmap(unsigned int target);
void bionic_glDeleteTextures(int n, const unsigned int *textures);
void bionic_glDeleteFramebuffers(int n, const unsigned int *framebuffers);
void bionic_glFlush(void);

#endif
