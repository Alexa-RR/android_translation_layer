The following environment variables are recognized by the main executable:

`JDWP_LISTEN=<port>` - if set, art will listen for a jdb connection at `<port>`

---

`RUN_FROM_BUILDDIR=` - if set, will search for `api-impl.jar` and `libtranslation_layer.so` in current working directory

                       (may need `LD_LIBRARY_PATH=.` as well for `libandroid.so.0`)

---

`ANDROID_APP_DATA_DIR=<path>` - if set, overrides the default path of `~/.local/android_translation_layer` for storing app data

---

`ATL_DISABLE_WINDOW_DECORATIONS=` - if set, window decorations will be disabled; 
                                    this is useful for saving screen space on phone screens

---

`ATL_UGLY_ENABLE_LOCATION=` - if set, apps will be able to get location data using the relevant android APIs. (TODO: use bubblewrap)  

---

`ATL_UGLY_ENABLE_MICROPHONE=` - if set, apps will be able record microphone audio using the relevant android APIs. (TODO: use bubblewrap)

---

`ATL_UGLY_ENABLE_WEBVIEW=` - if not set, WebView will be stubbed as a generic View; this will avoid
                             wasting resources on WebViews which are only used for fingerprinting and ads

                             (set this for apps that use WebView for it's intended purpose)

---

`ATL_FORCE_FULLSCREEN` - if set, will fullscreen the app window on start;
                         this is useful for saving screen space on phone screens,
                         as well as making apps that can't handle arbitrary screen dimensions
                         for some reason happier (may need gamescope if the hardcoded resolution
                         doesn't match your device)

---

`ATL_IS_AUTOMOTIVE` - if set, when an app checks if it's running in a vehicle, ATL will return true.

---

`ATL_IS_TELEVISION` - if set, when an app checks if it's running on a television, ATL will return true.

---

`ATL_IS_WATCH` - if set, when an app checks if it's running on a watch, ATL will return true.

---

`ATL_SKIP_NATIVES_EXTRACTION` - if set, natives will not be extracted automatically;
                                it's already possible to replace a native lib, but removing it entirely will normally result
                                in it getting re-extracted, which would prevent you from replacing libs with native ones
                                (since bionic_translation linker considers everything inside the app's lib dir non-native)

---

`ATL_DIRECT_EGL` - if set, SurfaceViews will be mapped directly to a Wayland subsurface or X11 window
                   instead of using GtkGraphicsOffload. This might be beneficial for CPU usage and rendering latency,
                   but (on Gtk < 4.22 and on X11) does not allow the application to render other Views on top of
                   the SurfaceView

                   On Gtk >= 4.22, we punch a hole through the Gtk scene graph and (on Wayland) put the native surface below the Gtk window.

---

`ATL_VALIDATE_CERTS` - if set, the signing certificate of the APK file will be validated on startup. This adds a few extra seconds to the startup time for large APKs.

---

`ATL_CAR_HOST=1` - runs a Car App Library host (androidx.car.app) inside the runtime: once the app's first activity is resumed,
                   the host binds the app's `androidx.car.app.CarAppService` as the platform (system uid, package `android`,
                   signed with ATL's platform key, so the app's host validator accepts it as a system binding), performs the
                   handshake and the session lifecycle, and publishes the car connection state (`androidx.car.app.connection`,
                   projection). It does not render the templates yet: every call the app makes to the host and every template
                   it returns is logged with the tag `ATLCarHost` (info level, e.g. `ANDROID_LOG_TAGS='*:w ATLCarHost:i'`).

---

`ATL_CAR_HOST_DELAY_MS=<ms>` - with `ATL_CAR_HOST=1`, how long the host waits after the first resumed activity before it binds the app's car service (default 0)

---

`ATL_FILE_OVERLAYS=/path/manifest.json` - the app reads a transformed copy of some of its own files: each entry of the JSON manifest names a
                   package, a path (relative to the app's data directory, or absolute), the (versionCode, sha256 of the original)
                   pairs it was checked with, and either a list of `[FROM, TO]` text replacements (each FROM exactly once in the
                   original) or the whole `contents`. A read-only `open()`/`fopen()`/`freopen()`/`__open_2()` from the app's native code
                   of a listed file, when the running package and versionCode and the file's sha256 match a verified pair, gets a
                   sealed memfd with the transformed copy; anything else opens the real file. The files on disk are never written.
                   Needs bionic_translation's open overlay hook (`libc_bio_set_open_overlay_func`). The format is described in
                   `src/api-impl-jni/file_overlays.c`; each file is logged once (applied, or why not) with the prefix `ATL file overlays`.
