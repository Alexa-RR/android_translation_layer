#ifndef ATL_FILE_OVERLAYS_H
#define ATL_FILE_OVERLAYS_H

#include <glib.h>

/*
 * File overlays (ATL_FILE_OVERLAYS=/path/manifest.json): read-only opens of files named in the
 * manifest get a transformed copy held in memory, but only when the app's package and versionCode
 * and the exact original contents (sha256) are on the manifest's verified list. The files on disk
 * are never written. See file_overlays.c for the manifest format.
 */

/* the app's identity, known once its manifest is parsed; loads the manifest (no-op without the
 * environment variable) */
void atl_file_overlays_init(const char *package_name, gint64 version_code);

/* bionic_translation's open overlay hook: a descriptor of the transformed copy for a read-only open
 * of an overlaid file, or -1 to let the app open the real file */
int atl_file_overlay_open(const char *path, int oflag);

#endif
