#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <fontconfig/fontconfig.h>
#include <glib.h>

/* helpers */
static bool starts_with(const char *string, const char *substring)
{
	return !strncmp(string, substring, strlen(substring));
}

static bool ends_with(const char *str, const char *suffix)
{
	if (!str || !suffix)
		return 0;

	size_t str_len = strlen(str);
	size_t suffix_len = strlen(suffix);
	if (suffix_len > str_len)
		return 0;
	return (strcmp(str + (str_len - suffix_len), suffix) == 0);
}

static bool file_exists(char *path)
{
	return (!access(path, F_OK));
}

/* use fontconfig to find a font */
static char *get_font_path(char *filename)
{
	int ret;

	/* just load the list once on first call to this function */
	static FcFontSet *font_set = NULL;
	if (!font_set) {
		ret = FcInit();
		if (!ret)
			return NULL;

		// match all fonts
		FcPattern *pattern = FcPatternCreate();
		if (!pattern)
			return NULL;

		FcObjectSet *object_set = FcObjectSetBuild(FC_FILE, NULL);
		if (!object_set) {
			FcPatternDestroy(pattern);
			return NULL;
		}

		// Retrieve the list of fonts
		font_set = FcFontList(NULL, pattern, object_set);
		if (!font_set) {
			FcObjectSetDestroy(object_set);
			FcPatternDestroy(pattern);
			return NULL;
		}
	}

	for (int i = 0; i < font_set->nfont; i++) {
		FcPattern *font = font_set->fonts[i];
		FcChar8 *file = NULL;
		if (FcPatternGetString(font, FC_FILE, 0, &file) == FcResultMatch && file) {
			if (ends_with((char *)file, filename)) {
				return (char *)file;
			}
		}
	}

	return NULL;
}

static char *fonts_overrides[] = {
	"/etc/fonts.xml",
	/* INSTALL_DATADIR will be something like /usr/local/share */
	INSTALL_DATADIR "/atl/system/etc/fonts.xml",
	/* Relative path when running from ./android_translation_layer/builddir */
	"../res/fonts.xml",
	NULL,
};

extern char *get_app_data_dir(void);

bool apply_path_overrides(char **path)
{
	bool free_path = false;

	/* TODO: read the overrides from a config file */
	/* TODO: compare with canonicalized path */
	if (!strcmp(*path, "/system/etc/fonts.xml")) {
		for (char **override = fonts_overrides; *override; override++) {
			if (file_exists(*override)) {
				*path = *override;
				break;
			}
		}
	}

	/* AOSP seems to put all the fonts in `/system/fonts`. On a standard Linux distro, the font
	 * could be anywhere, so we need to get all the fonts fontconfig knows about and check
	 * if the filename matches. */
	if (starts_with(*path, "/system/fonts/")) {
		char *font_filename = strchr(*path + 8, '/'); // after /fonts
		char *new_path = get_font_path(font_filename);
		if (new_path)
			*path = new_path;
		else
			fprintf(stderr, "%s: !!! app trying to access a font at >%s<, but there is no >%s< in the fontconfig cache\n", __func__, *path, font_filename);
	}

	/* Some apps literally hardcode the external storage path (even with assumed userid=0). */
	if (starts_with(*path, "/storage/emulated/0/")) {
		char *suffix = *path + sizeof("/storage/emulated/0");
		char *new_path = g_strdup_printf("%s/%s", get_app_data_dir(), suffix);

		fprintf(stderr, "%s: !!! app trying to access >%s<, rewriting to >%s<\n", __func__, *path, new_path);

		*path = new_path;
		free_path = true;
	}

	if (starts_with(*path, "/system/") || starts_with(*path, "/data/")) {
		fprintf(stderr, "%s: !!! app trying to access >%s<, which will certainly fail\n", __func__, *path);
		fflush(stderr);
	}

	return free_path;
}

/*
 * Android's font fallback for fontconfig.
 *
 * On Android a character the requested typeface lacks comes from the system fallback chain:
 * fonts.xml's families without a name, in file order (Noto Sans Hebrew, Noto Naskh Arabic, the CJK
 * families, emoji, ...), tried right after the requested family, for system and app typefaces alike.
 * Pango asks fontconfig instead, whose default order puts DejaVu Sans before any Noto script family:
 * Hebrew text set in Roboto came out in DejaVu Sans.
 *
 * The fallback families of fonts.xml are read here, each font file is mapped to the family name
 * fontconfig knows it by, and one rule appends them, in fonts.xml's order and strongly bound, right
 * after the first family of every pattern. It runs before GTK creates its font map. Files fontconfig
 * does not have are skipped.
 */
struct fallback_parse {
	gboolean in_fallback_family; /* inside a <family> without a name attribute */
	gboolean in_font;
	GPtrArray *files; /* font file names, in order */
};

static void fallback_start(GMarkupParseContext *context, const gchar *element, const gchar **names, const gchar **values,
                           gpointer user_data, GError **error)
{
	struct fallback_parse *p = user_data;
	if (!strcmp(element, "family")) {
		p->in_fallback_family = TRUE;
		for (int i = 0; names[i]; i++)
			if (!strcmp(names[i], "name"))
				p->in_fallback_family = FALSE;
	} else if (!strcmp(element, "font")) {
		p->in_font = p->in_fallback_family;
	}
}

static void fallback_end(GMarkupParseContext *context, const gchar *element, gpointer user_data, GError **error)
{
	struct fallback_parse *p = user_data;
	if (!strcmp(element, "family"))
		p->in_fallback_family = FALSE;
	else if (!strcmp(element, "font"))
		p->in_font = FALSE;
}

static void fallback_text(GMarkupParseContext *context, const gchar *text, gsize len, gpointer user_data, GError **error)
{
	struct fallback_parse *p = user_data;
	if (!p->in_font)
		return;
	char *name = g_strstrip(g_strndup(text, len));
	if (*name)
		g_ptr_array_add(p->files, name);
	else
		g_free(name);
}

void install_android_font_fallback(void)
{
	char *path = "/system/etc/fonts.xml";
	apply_path_overrides(&path);
	char *xml = NULL;
	gsize xml_len = 0;
	if (!g_file_get_contents(path, &xml, &xml_len, NULL)) {
		fprintf(stderr, "%s: no fonts.xml at %s, fontconfig's own fallback order is used\n", __func__, path);
		return;
	}
	struct fallback_parse p = {.files = g_ptr_array_new_with_free_func(g_free)};
	static const GMarkupParser parser = {.start_element = fallback_start, .end_element = fallback_end, .text = fallback_text};
	GMarkupParseContext *context = g_markup_parse_context_new(&parser, 0, &p, NULL);
	GError *error = NULL;
	if (!g_markup_parse_context_parse(context, xml, xml_len, &error) || !g_markup_parse_context_end_parse(context, &error)) {
		fprintf(stderr, "%s: %s: %s\n", __func__, path, error->message);
		g_error_free(error);
	}
	g_markup_parse_context_free(context);
	g_free(xml);

	/* the families, each once, in the order their first file appears */
	GPtrArray *families = g_ptr_array_new_with_free_func(g_free);
	for (guint i = 0; i < p.files->len; i++) {
		char *suffix = g_strdup_printf("/%s", (char *)p.files->pdata[i]);
		char *file = get_font_path(suffix);
		g_free(suffix);
		if (!file)
			continue;
		int count = 0;
		FcPattern *query = FcFreeTypeQuery((const FcChar8 *)file, 0, NULL, &count);
		FcChar8 *family = NULL;
		if (query && FcPatternGetString(query, FC_FAMILY, 0, &family) == FcResultMatch) {
			gboolean seen = FALSE;
			for (guint j = 0; j < families->len && !seen; j++)
				seen = !strcmp(families->pdata[j], (char *)family);
			if (!seen)
				g_ptr_array_add(families, g_strdup((char *)family));
		}
		if (query)
			FcPatternDestroy(query);
	}
	g_ptr_array_free(p.files, TRUE);
	if (!families->len) {
		g_ptr_array_free(families, TRUE);
		return;
	}

	GString *rule = g_string_new("<?xml version=\"1.0\"?>\n<!DOCTYPE fontconfig SYSTEM \"urn:fontconfig:fonts.dtd\">\n<fontconfig>\n"
	                             "<match target=\"pattern\">\n"
	                             "<test qual=\"first\" name=\"family\" compare=\"not_eq\"><string></string></test>\n"
	                             "<edit name=\"family\" mode=\"append\" binding=\"strong\">\n");
	for (guint i = 0; i < families->len; i++) {
		char *escaped = g_markup_escape_text(families->pdata[i], -1);
		g_string_append_printf(rule, "<string>%s</string>\n", escaped);
		g_free(escaped);
	}
	g_string_append(rule, "</edit>\n</match>\n</fontconfig>\n");
	if (!FcConfigParseAndLoadFromMemory(FcConfigGetCurrent(), (const FcChar8 *)rule->str, FcTrue))
		fprintf(stderr, "%s: fontconfig did not take the fallback rule\n", __func__);
	g_string_free(rule, TRUE);
	g_ptr_array_free(families, TRUE);
}
