/* NELEM from AOSP's system/core libutils utils/misc.h, for AOSP's generated GL bindings */
#ifndef ATL_UTILS_MISC_H
#define ATL_UTILS_MISC_H

#ifndef NELEM
#define NELEM(x) ((int)(sizeof(x) / sizeof((x)[0])))
#endif

#endif
