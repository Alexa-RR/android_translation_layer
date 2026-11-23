/*
 * androidfw's C++ entry points that its C API (androidfw_c_api.h) does not wrap and that return a
 * C++ object, so they cannot be called from C: declared here with the ABI of art_standalone's
 * libandroidfw/libutils (android::String8 is one pointer with a non-trivial destructor, so it is
 * returned through memory as the C++ ABI requires).
 */

#include <stdlib.h>
#include <string.h>

namespace android {
class String8 {
public:
	~String8();
	const char *c_str() const { return mString; }

private:
	const char *mString;
};

class Asset {
public:
	static String8 getAssetAllocations();
};
}

/* Asset::getAssetAllocations(): a description of the assets open now (androidfw lists them only in
 * a debug build); a malloc'd copy, or NULL when it is empty */
extern "C" char *tl_asset_get_allocations(void)
{
	android::String8 allocations = android::Asset::getAssetAllocations();
	const char *s = allocations.c_str();
	if (!s || !s[0])
		return NULL;
	return strdup(s);
}
