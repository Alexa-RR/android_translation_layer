/*
 * File overlays: the app reads a transformed copy of some of its own files, in memory.
 *
 * ATL_FILE_OVERLAYS names a JSON manifest. Each entry names one file of one app, the app versions
 * and original contents it was checked against, and what to change:
 *
 *   {
 *     "replacements": {                        optional: named replacement lists, shared by entries
 *       "palette": [["FROM", "TO"], ...]
 *     },
 *     "overlays": [
 *       {
 *         "name": "map palette, day",          for the log
 *         "package": "com.example.app",
 *         "path": "skins/values.lua",          relative to the app's data directory, or absolute
 *         "verified": [                        the (versionCode, original sha256) pairs it applies to
 *           {"versionCode": 1030736, "versionName": "5.25.0.1", "sha256": "4e51..."}
 *         ],
 *         "replace": "palette"                 a list name, or the list itself: [["FROM", "TO"], ...]
 *         -- or --
 *         "contents": "..."                    the whole file
 *       }
 *     ]
 *   }
 *
 * An entry applies only when the running app is that package, its versionCode is on the entry's
 * verified list, and the file as it is on disk hashes to the sha256 given for that versionCode.
 * Each FROM must occur exactly once in the original; all of them are replaced at once (a TO is
 * never searched again). Anything else (another version, other contents, a FROM that is missing or
 * repeated) leaves the app reading its own file, and says why in the log, once per file contents.
 *
 * The app's open()/fopen()/freopen() of the file, read-only, reach atl_file_overlay_open() through
 * bionic_translation's open overlay hook (libc_bio_set_open_overlay_func, registered by the main
 * executable). It answers with a new descriptor on a sealed memfd that holds the transformed copy,
 * so each open has its own file offset, mmap works, and nothing can write to it. Opens for writing
 * (the app unpacking a new copy) always reach the real file, and a changed file on disk is checked
 * again on its next open. stat() of the path still describes the file on disk: keep transformed
 * files the same size when the app sizes its reads from stat().
 */

#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <glib.h>

#include "file_overlays.h"

extern char *get_app_data_dir(void); /* main-executable: the app's data directory */

#define TAG "ATL file overlays"

/* ---------------------------------------------------------------- a small, strict JSON reader */

typedef enum {
	JV_NULL,
	JV_BOOL,
	JV_INT,
	JV_DOUBLE,
	JV_STRING,
	JV_ARRAY,
	JV_OBJECT,
} jv_type;

typedef struct jv {
	jv_type type;
	gboolean b;
	gint64 i;
	double d;
	GString *s;      /* JV_STRING (UTF-8, no NUL bytes) */
	GPtrArray *arr;  /* JV_ARRAY and JV_OBJECT: the values, struct jv * */
	GPtrArray *keys; /* JV_OBJECT: the keys, char *, parallel to arr */
} jv;

struct jparser {
	const char *p;
	const char *end;
	int depth;
	char error[160];
};

static void jv_free(jv *v)
{
	if (!v)
		return;
	if (v->s)
		g_string_free(v->s, TRUE);
	if (v->arr)
		g_ptr_array_free(v->arr, TRUE);
	if (v->keys)
		g_ptr_array_free(v->keys, TRUE);
	g_free(v);
}

static jv *jv_new(jv_type type)
{
	jv *v = g_new0(jv, 1);
	v->type = type;
	if (type == JV_ARRAY || type == JV_OBJECT)
		v->arr = g_ptr_array_new_with_free_func((GDestroyNotify)jv_free);
	if (type == JV_OBJECT)
		v->keys = g_ptr_array_new_with_free_func(g_free);
	return v;
}

static jv *jv_fail(struct jparser *ps, const char *what)
{
	if (!ps->error[0])
		snprintf(ps->error, sizeof(ps->error), "%s", what);
	return NULL;
}

static void jskip_ws(struct jparser *ps)
{
	while (ps->p < ps->end && (*ps->p == ' ' || *ps->p == '\t' || *ps->p == '\n' || *ps->p == '\r'))
		ps->p++;
}

static int jhex4(const char *p)
{
	int v = 0;
	for (int i = 0; i < 4; i++) {
		int c = g_ascii_xdigit_value(p[i]);
		if (c < 0)
			return -1;
		v = v * 16 + c;
	}
	return v;
}

static GString *jparse_string(struct jparser *ps)
{
	if (ps->p >= ps->end || *ps->p != '"')
		return NULL;
	ps->p++;
	GString *s = g_string_new(NULL);
	while (ps->p < ps->end && *ps->p != '"') {
		unsigned char c = *ps->p;
		if (c < 0x20) {
			jv_fail(ps, "control character in a string");
			g_string_free(s, TRUE);
			return NULL;
		}
		if (c != '\\') {
			g_string_append_c(s, c);
			ps->p++;
			continue;
		}
		if (ps->end - ps->p < 2)
			break;
		char e = ps->p[1];
		ps->p += 2;
		switch (e) {
			case '"': g_string_append_c(s, '"'); break;
			case '\\': g_string_append_c(s, '\\'); break;
			case '/': g_string_append_c(s, '/'); break;
			case 'b': g_string_append_c(s, '\b'); break;
			case 'f': g_string_append_c(s, '\f'); break;
			case 'n': g_string_append_c(s, '\n'); break;
			case 'r': g_string_append_c(s, '\r'); break;
			case 't': g_string_append_c(s, '\t'); break;
			case 'u': {
				int cp = ps->end - ps->p >= 4 ? jhex4(ps->p) : -1;
				if (cp < 0)
					goto bad_escape;
				ps->p += 4;
				if (cp >= 0xD800 && cp <= 0xDBFF) { /* a surrogate pair */
					int lo = (ps->end - ps->p >= 6 && ps->p[0] == '\\' && ps->p[1] == 'u') ? jhex4(ps->p + 2) : -1;
					if (lo < 0xDC00 || lo > 0xDFFF)
						goto bad_escape;
					ps->p += 6;
					cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
				} else if (cp >= 0xDC00 && cp <= 0xDFFF) {
					goto bad_escape;
				}
				if (cp == 0)
					goto bad_escape; /* text files only: no NUL bytes */
				g_string_append_unichar(s, cp);
				break;
			}
			default:
				goto bad_escape;
		}
	}
	if (ps->p >= ps->end) {
		jv_fail(ps, "unterminated string");
		g_string_free(s, TRUE);
		return NULL;
	}
	ps->p++; /* the closing quote */
	if (!g_utf8_validate_len(s->str, s->len, NULL)) {
		jv_fail(ps, "a string is not UTF-8");
		g_string_free(s, TRUE);
		return NULL;
	}
	return s;
bad_escape:
	jv_fail(ps, "bad escape in a string");
	g_string_free(s, TRUE);
	return NULL;
}

static jv *jparse_number(struct jparser *ps)
{
	const char *start = ps->p;
	gboolean integral = TRUE;
	if (ps->p < ps->end && *ps->p == '-')
		ps->p++;
	if (ps->p >= ps->end || !g_ascii_isdigit(*ps->p))
		return jv_fail(ps, "bad number");
	if (*ps->p == '0')
		ps->p++;
	else
		while (ps->p < ps->end && g_ascii_isdigit(*ps->p))
			ps->p++;
	if (ps->p < ps->end && *ps->p == '.') {
		integral = FALSE;
		ps->p++;
		if (ps->p >= ps->end || !g_ascii_isdigit(*ps->p))
			return jv_fail(ps, "bad number");
		while (ps->p < ps->end && g_ascii_isdigit(*ps->p))
			ps->p++;
	}
	if (ps->p < ps->end && (*ps->p == 'e' || *ps->p == 'E')) {
		integral = FALSE;
		ps->p++;
		if (ps->p < ps->end && (*ps->p == '+' || *ps->p == '-'))
			ps->p++;
		if (ps->p >= ps->end || !g_ascii_isdigit(*ps->p))
			return jv_fail(ps, "bad number");
		while (ps->p < ps->end && g_ascii_isdigit(*ps->p))
			ps->p++;
	}
	char *text = g_strndup(start, ps->p - start);
	jv *v;
	if (integral) {
		errno = 0;
		gint64 i = g_ascii_strtoll(text, NULL, 10);
		if (errno) {
			g_free(text);
			return jv_fail(ps, "integer out of range");
		}
		v = jv_new(JV_INT);
		v->i = i;
		v->d = (double)i;
	} else {
		v = jv_new(JV_DOUBLE);
		v->d = g_ascii_strtod(text, NULL);
	}
	g_free(text);
	return v;
}

static gboolean jliteral(struct jparser *ps, const char *word)
{
	size_t n = strlen(word);
	if ((size_t)(ps->end - ps->p) < n || memcmp(ps->p, word, n))
		return FALSE;
	ps->p += n;
	return TRUE;
}

static jv *jparse_value(struct jparser *ps)
{
	jskip_ws(ps);
	if (ps->p >= ps->end)
		return jv_fail(ps, "unexpected end");
	if (++ps->depth > 64)
		return jv_fail(ps, "nested too deeply");
	jv *v = NULL;
	char c = *ps->p;
	if (c == '{') {
		ps->p++;
		v = jv_new(JV_OBJECT);
		jskip_ws(ps);
		if (ps->p < ps->end && *ps->p == '}') {
			ps->p++;
		} else {
			for (;;) {
				jskip_ws(ps);
				GString *key = jparse_string(ps);
				if (!key) {
					jv_fail(ps, "expected a key");
					goto fail;
				}
				jskip_ws(ps);
				if (ps->p >= ps->end || *ps->p != ':') {
					g_string_free(key, TRUE);
					jv_fail(ps, "expected ':'");
					goto fail;
				}
				ps->p++;
				jv *member = jparse_value(ps);
				if (!member) {
					g_string_free(key, TRUE);
					goto fail;
				}
				g_ptr_array_add(v->keys, g_string_free(key, FALSE));
				g_ptr_array_add(v->arr, member);
				jskip_ws(ps);
				if (ps->p < ps->end && *ps->p == ',') {
					ps->p++;
					continue;
				}
				if (ps->p < ps->end && *ps->p == '}') {
					ps->p++;
					break;
				}
				jv_fail(ps, "expected ',' or '}'");
				goto fail;
			}
		}
	} else if (c == '[') {
		ps->p++;
		v = jv_new(JV_ARRAY);
		jskip_ws(ps);
		if (ps->p < ps->end && *ps->p == ']') {
			ps->p++;
		} else {
			for (;;) {
				jv *item = jparse_value(ps);
				if (!item)
					goto fail;
				g_ptr_array_add(v->arr, item);
				jskip_ws(ps);
				if (ps->p < ps->end && *ps->p == ',') {
					ps->p++;
					continue;
				}
				if (ps->p < ps->end && *ps->p == ']') {
					ps->p++;
					break;
				}
				jv_fail(ps, "expected ',' or ']'");
				goto fail;
			}
		}
	} else if (c == '"') {
		GString *s = jparse_string(ps);
		if (!s)
			return jv_fail(ps, "bad string");
		v = jv_new(JV_STRING);
		v->s = s;
	} else if (c == '-' || g_ascii_isdigit(c)) {
		v = jparse_number(ps);
	} else if (jliteral(ps, "true")) {
		v = jv_new(JV_BOOL);
		v->b = TRUE;
	} else if (jliteral(ps, "false")) {
		v = jv_new(JV_BOOL);
	} else if (jliteral(ps, "null")) {
		v = jv_new(JV_NULL);
	} else {
		return jv_fail(ps, "unexpected character");
	}
	ps->depth--;
	return v;
fail:
	jv_free(v);
	return NULL;
}

static jv *jparse(const char *text, size_t len, char *error, size_t error_len)
{
	struct jparser ps = {.p = text, .end = text + len};
	jv *v = jparse_value(&ps);
	if (v) {
		jskip_ws(&ps);
		if (ps.p != ps.end) {
			jv_free(v);
			v = jv_fail(&ps, "text after the value");
		}
	}
	if (!v)
		snprintf(error, error_len, "%s at byte %ld", ps.error[0] ? ps.error : "parse error", (long)(ps.p - text));
	return v;
}

static jv *jv_get(jv *obj, const char *key)
{
	if (!obj || obj->type != JV_OBJECT)
		return NULL;
	for (guint i = 0; i < obj->keys->len; i++)
		if (!strcmp(obj->keys->pdata[i], key))
			return obj->arr->pdata[i];
	return NULL;
}

static const char *jv_str(jv *v)
{
	return (v && v->type == JV_STRING) ? v->s->str : NULL;
}

/* ---------------------------------------------------------------- the overlays */

struct overlay {
	char *name;
	char *target;      /* the file's absolute, canonical path */
	const char *base;  /* its last component, inside target */
	GPtrArray *sha256; /* the verified originals for the running versionCode, lowercase hex */
	GPtrArray *from;   /* GString *: the replacements, or NULL with contents */
	GPtrArray *to;
	GString *contents; /* the whole file, or NULL */
	/* the last original seen (under overlays_lock) */
	gboolean seen;
	dev_t dev;
	ino_t ino;
	off_t size;
	struct timespec mtime;
	int memfd; /* the transformed copy of that original, or -1: the app reads its own file */
};

static GPtrArray *overlays; /* struct overlay *; set once by atl_file_overlays_init, then read-only */
static GMutex overlays_lock;

static gboolean is_sha256_hex(const char *s)
{
	if (!s || strlen(s) != 64)
		return FALSE;
	for (int i = 0; i < 64; i++)
		if (!g_ascii_isxdigit(s[i]))
			return FALSE;
	return TRUE;
}

static void gstring_free(gpointer s)
{
	g_string_free(s, TRUE);
}

/* a replacement list: [["FROM", "TO"], ...]; FROM must not be empty */
static gboolean read_replacements(jv *list, struct overlay *ov, const char **why)
{
	if (!list || list->type != JV_ARRAY || !list->arr->len) {
		*why = "\"replace\" is not a non-empty list of [FROM, TO] pairs";
		return FALSE;
	}
	ov->from = g_ptr_array_new_with_free_func(gstring_free);
	ov->to = g_ptr_array_new_with_free_func(gstring_free);
	for (guint i = 0; i < list->arr->len; i++) {
		jv *pair = list->arr->pdata[i];
		if (pair->type != JV_ARRAY || pair->arr->len != 2 || ((jv *)pair->arr->pdata[0])->type != JV_STRING ||
		    ((jv *)pair->arr->pdata[1])->type != JV_STRING || !((jv *)pair->arr->pdata[0])->s->len) {
			*why = "a replacement is not a [FROM, TO] pair of strings with a non-empty FROM";
			return FALSE;
		}
		GString *from = ((jv *)pair->arr->pdata[0])->s;
		GString *to = ((jv *)pair->arr->pdata[1])->s;
		g_ptr_array_add(ov->from, g_string_new_len(from->str, from->len));
		g_ptr_array_add(ov->to, g_string_new_len(to->str, to->len));
	}
	return TRUE;
}

static void overlay_free(struct overlay *ov)
{
	if (ov->memfd >= 0)
		close(ov->memfd);
	g_free(ov->name);
	g_free(ov->target);
	if (ov->sha256)
		g_ptr_array_free(ov->sha256, TRUE);
	if (ov->from)
		g_ptr_array_free(ov->from, TRUE);
	if (ov->to)
		g_ptr_array_free(ov->to, TRUE);
	if (ov->contents)
		g_string_free(ov->contents, TRUE);
	g_free(ov);
}

static gboolean has_dotdot(const char *path)
{
	gchar **parts = g_strsplit(path, "/", -1);
	gboolean found = FALSE;
	for (int i = 0; parts[i] && !found; i++)
		found = !strcmp(parts[i], "..");
	g_strfreev(parts);
	return found;
}

void atl_file_overlays_init(const char *package_name, gint64 version_code)
{
	const char *manifest_path = getenv("ATL_FILE_OVERLAYS");
	if (!manifest_path || !*manifest_path || overlays || !package_name)
		return;
	char *text = NULL;
	gsize len = 0;
	GError *error = NULL;
	if (!g_file_get_contents(manifest_path, &text, &len, &error)) {
		fprintf(stderr, "%s: cannot read %s (%s); the app reads its own files\n", TAG, manifest_path, error->message);
		g_error_free(error);
		return;
	}
	char perr[200];
	jv *root = jparse(text, len, perr, sizeof(perr));
	g_free(text);
	if (!root || root->type != JV_OBJECT) {
		fprintf(stderr, "%s: %s is not a JSON object (%s); the app reads its own files\n", TAG, manifest_path, root ? "not an object" : perr);
		jv_free(root);
		return;
	}
	jv *named = jv_get(root, "replacements");
	jv *list = jv_get(root, "overlays");
	if (!list || list->type != JV_ARRAY) {
		fprintf(stderr, "%s: %s has no \"overlays\" list; the app reads its own files\n", TAG, manifest_path);
		jv_free(root);
		return;
	}

	char *data_dir = get_app_data_dir();
	char *data_dir_real = data_dir ? realpath(data_dir, NULL) : NULL;
	GPtrArray *result = g_ptr_array_new();
	for (guint i = 0; i < list->arr->len; i++) {
		jv *e = list->arr->pdata[i];
		const char *why = NULL;
		const char *package = jv_str(jv_get(e, "package"));
		const char *path = jv_str(jv_get(e, "path"));
		const char *name = jv_str(jv_get(e, "name"));
		jv *verified = jv_get(e, "verified");
		jv *replace = jv_get(e, "replace");
		jv *contents = jv_get(e, "contents");
		char *label = name ? g_strdup(name) : g_strdup_printf("overlay %u", i + 1);
		if (!package || !path || !*path) {
			why = "it needs \"package\" and \"path\"";
		} else if (strcmp(package, package_name)) {
			g_free(label);
			continue; /* another app's */
		} else if (has_dotdot(path)) {
			why = "its path contains \"..\"";
		} else if (path[0] != '/' && !data_dir_real) {
			why = "the app's data directory is unknown";
		} else if (!verified || verified->type != JV_ARRAY) {
			why = "it has no \"verified\" list";
		} else if (!!replace == !!(contents && contents->type == JV_STRING)) {
			why = "it needs exactly one of \"replace\" and \"contents\" (a string)";
		}
		struct overlay *ov = NULL;
		if (!why) {
			ov = g_new0(struct overlay, 1);
			ov->memfd = -1;
			ov->name = g_strdup(label);
			ov->sha256 = g_ptr_array_new_with_free_func(g_free);
			GString *listed = g_string_new(NULL);
			for (guint j = 0; j < verified->arr->len && !why; j++) {
				jv *pair = verified->arr->pdata[j];
				jv *code = jv_get(pair, "versionCode");
				const char *sha = jv_str(jv_get(pair, "sha256"));
				if (!code || code->type != JV_INT || !is_sha256_hex(sha)) {
					why = "a \"verified\" item needs an integer \"versionCode\" and a 64-digit \"sha256\"";
					break;
				}
				g_string_append_printf(listed, "%s%" G_GINT64_FORMAT, listed->len ? ", " : "", code->i);
				if (code->i == version_code)
					g_ptr_array_add(ov->sha256, g_ascii_strdown(sha, -1));
			}
			if (!why && !ov->sha256->len) {
				fprintf(stderr, "%s: %s: not applied: versionCode %" G_GINT64_FORMAT " of %s is not verified (verified: %s); the app reads its own %s\n",
				        TAG, label, version_code, package_name, listed->len ? listed->str : "none", path);
				g_string_free(listed, TRUE);
				overlay_free(ov);
				g_free(label);
				continue;
			}
			g_string_free(listed, TRUE);
		}
		if (!why && replace) {
			jv *pairs = replace;
			if (replace->type == JV_STRING) {
				pairs = jv_get(named, replace->s->str);
				if (!pairs)
					why = "its \"replace\" names a list that \"replacements\" does not have";
			}
			if (!why)
				read_replacements(pairs, ov, &why);
		} else if (!why) {
			ov->contents = g_string_new_len(contents->s->str, contents->s->len);
		}
		if (why) {
			fprintf(stderr, "%s: %s: ignored: %s\n", TAG, label, why);
			if (ov)
				overlay_free(ov);
			g_free(label);
			continue;
		}
		char *joined = path[0] == '/' ? g_strdup(path) : g_build_filename(data_dir_real, path, NULL);
		char *real = realpath(joined, NULL); /* the file may not be unpacked yet: then as written */
		ov->target = real ? g_strdup(real) : g_canonicalize_filename(joined, "/");
		free(real);
		g_free(joined);
		ov->base = strrchr(ov->target, '/') + 1;
		g_ptr_array_add(result, ov);
		g_free(label);
	}
	free(data_dir_real);
	jv_free(root);
	if (result->len) {
		fprintf(stderr, "%s: %u file(s) of %s (versionCode %" G_GINT64_FORMAT ") have verified overlays from %s\n",
		        TAG, result->len, package_name, version_code, manifest_path);
		overlays = result; /* before any of the app's code runs */
	} else {
		g_ptr_array_free(result, TRUE);
	}
}

/* the overlay's transformed copy of the original's bytes, or NULL (and why) */
static GString *overlay_transform(struct overlay *ov, const char *orig, gsize len, char **why)
{
	if (ov->contents)
		return g_string_new_len(ov->contents->str, ov->contents->len);
	guint n = ov->from->len;
	gsize *at = g_new(gsize, n);
	for (guint k = 0; k < n; k++) {
		GString *from = ov->from->pdata[k];
		int count = 0;
		for (const char *p = orig; p && (gsize)(p - orig) + from->len <= len;) {
			const char *hit = memmem(p, len - (p - orig), from->str, from->len);
			if (!hit)
				break;
			if (!count)
				at[k] = hit - orig;
			count++;
			p = hit + 1;
		}
		if (count != 1) {
			*why = g_strdup_printf("replacement %u (\"%.40s\") occurs %d times, not once", k + 1, from->str, count);
			g_free(at);
			return NULL;
		}
	}
	/* in file order; the matches must not overlap */
	guint *order = g_new(guint, n);
	for (guint k = 0; k < n; k++)
		order[k] = k;
	for (guint a = 1; a < n; a++) /* insertion sort: the lists are short */
		for (guint b = a; b > 0 && at[order[b - 1]] > at[order[b]]; b--) {
			guint t = order[b];
			order[b] = order[b - 1];
			order[b - 1] = t;
		}
	GString *out = g_string_sized_new(len + 64);
	gsize pos = 0;
	for (guint r = 0; r < n; r++) {
		guint k = order[r];
		if (at[k] < pos) {
			*why = g_strdup_printf("replacement %u overlaps another one", k + 1);
			g_string_free(out, TRUE);
			out = NULL;
			break;
		}
		g_string_append_len(out, orig + pos, at[k] - pos);
		g_string_append_len(out, ((GString *)ov->to->pdata[k])->str, ((GString *)ov->to->pdata[k])->len);
		pos = at[k] + ((GString *)ov->from->pdata[k])->len;
	}
	if (out)
		g_string_append_len(out, orig + pos, len - pos);
	g_free(order);
	g_free(at);
	return out;
}

/* a sealed memfd holding data, or -1 */
static int sealed_memfd(const char *name, const char *data, gsize len)
{
	int fd = memfd_create(name, MFD_CLOEXEC | MFD_ALLOW_SEALING);
	if (fd < 0)
		return -1;
	for (gsize done = 0; done < len;) {
		ssize_t w = write(fd, data + done, len - done);
		if (w < 0 && errno == EINTR)
			continue;
		if (w <= 0) {
			close(fd);
			return -1;
		}
		done += w;
	}
	if (fcntl(fd, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE | F_SEAL_SEAL) < 0) {
		close(fd);
		return -1;
	}
	return fd;
}

/* the memfd for the original as it is on disk now, built when the original changed; under the lock */
static int overlay_memfd_locked(struct overlay *ov)
{
	struct stat st;
	if (stat(ov->target, &st) < 0 || !S_ISREG(st.st_mode))
		return -1;
	if (ov->seen && ov->dev == st.st_dev && ov->ino == st.st_ino && ov->size == st.st_size &&
	    ov->mtime.tv_sec == st.st_mtim.tv_sec && ov->mtime.tv_nsec == st.st_mtim.tv_nsec)
		return ov->memfd;
	if (ov->memfd >= 0)
		close(ov->memfd);
	ov->memfd = -1;
	ov->seen = TRUE;
	ov->dev = st.st_dev;
	ov->ino = st.st_ino;
	ov->size = st.st_size;
	ov->mtime = st.st_mtim;

	char *orig = NULL;
	gsize len = 0;
	if (!g_file_get_contents(ov->target, &orig, &len, NULL)) {
		ov->seen = FALSE; /* try again on the next open */
		return -1;
	}
	char *sum = g_compute_checksum_for_data(G_CHECKSUM_SHA256, (const guchar *)orig, len);
	gboolean verified = FALSE;
	for (guint k = 0; k < ov->sha256->len && !verified; k++)
		verified = !strcmp(sum, ov->sha256->pdata[k]);
	if (!verified) {
		fprintf(stderr, "%s: %s: not applied: %s has sha256 %s, which is not a verified original for this version; the app reads its own file\n",
		        TAG, ov->name, ov->target, sum);
	} else {
		char *why = NULL;
		GString *out = overlay_transform(ov, orig, len, &why);
		if (out) {
			ov->memfd = sealed_memfd(ov->base, out->str, out->len);
			if (ov->memfd >= 0)
				fprintf(stderr, "%s: %s: applied to %s (original sha256 verified, %u bytes served from memory instead of %u; the file on disk is unchanged)\n",
				        TAG, ov->name, ov->target, (unsigned)out->len, (unsigned)len);
			else
				fprintf(stderr, "%s: %s: not applied: no memfd for %s (%s); the app reads its own file\n", TAG, ov->name, ov->target, strerror(errno));
			g_string_free(out, TRUE);
		} else {
			fprintf(stderr, "%s: %s: not applied to %s: %s; the app reads its own file\n", TAG, ov->name, ov->target, why);
			g_free(why);
		}
	}
	g_free(sum);
	g_free(orig);
	return ov->memfd;
}

int atl_file_overlay_open(const char *path, int oflag)
{
	GPtrArray *list = overlays;
	if (!list || !path || (oflag & O_ACCMODE) != O_RDONLY)
		return -1;
	/* cheap first: the last component; most opens end here */
	const char *base = strrchr(path, '/');
	base = base ? base + 1 : path;
	gboolean candidate = FALSE;
	for (guint i = 0; i < list->len && !candidate; i++)
		candidate = !strcmp(base, ((struct overlay *)list->pdata[i])->base);
	if (!candidate)
		return -1;
	char *real = realpath(path, NULL);
	if (!real)
		return -1;
	struct overlay *ov = NULL;
	for (guint i = 0; i < list->len && !ov; i++)
		if (!strcmp(real, ((struct overlay *)list->pdata[i])->target))
			ov = list->pdata[i];
	free(real);
	if (!ov)
		return -1;

	g_mutex_lock(&overlays_lock);
	int memfd = overlay_memfd_locked(ov);
	int fd = -1;
	if (memfd >= 0) {
		/* a new open file description, with its own offset, of the same sealed copy */
		char proc[48];
		snprintf(proc, sizeof(proc), "/proc/self/fd/%d", memfd);
		fd = open(proc, O_RDONLY | (oflag & (O_CLOEXEC | O_NONBLOCK)));
		if (fd < 0)
			fprintf(stderr, "%s: %s: cannot reopen the copy (%s); the app reads its own file\n", TAG, ov->name, strerror(errno));
	}
	g_mutex_unlock(&overlays_lock);
	return fd;
}
