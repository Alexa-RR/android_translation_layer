#include <androidfw/androidfw_c_api.h>

#include "../generated_headers/android_content_res_XmlBlock.h"

#include "../defines.h"
#include "tl_xml_tree.h"

/* ResXMLParser getters androidfw's C API does not wrap (libandroidfw's C++ methods, called with the
 * parser as `this`, as ATL calls other androidfw/libutils methods; they return plain ints) */
int32_t _ZNK7android12ResXMLParser9getTextIDEv(const struct ResXMLParser *parser);
int32_t _ZNK7android12ResXMLParser21getElementNamespaceIDEv(const struct ResXMLParser *parser);
int32_t _ZNK7android12ResXMLParser23getAttributeNamespaceIDEm(const struct ResXMLParser *parser, size_t idx);

JNIEXPORT jlong JNICALL Java_android_content_res_XmlBlock_nativeCreate(JNIEnv *env, jclass this, jbyteArray data, jint offset, jint length)
{
	if (!data) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), NULL);
		return 0;
	}
	jsize bLen = (*env)->GetArrayLength(env, data);
	if (offset < 0 || offset >= bLen || length < 0 || length > bLen || (offset + length) > bLen) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IndexOutOfBoundsException"), NULL);
		return 0;
	}
	jbyte *xml_buf = (*env)->GetByteArrayElements(env, data, NULL);
	struct ResXMLTree *tree = tl_xml_tree_new(xml_buf + offset, length);
	(*env)->ReleaseByteArrayElements(env, data, xml_buf, JNI_ABORT);
	return _INTPTR(tree);
}

JNIEXPORT jlong JNICALL Java_android_content_res_XmlBlock_nativeGetStringBlock(JNIEnv *env, jclass this, jlong block)
{
	struct ResXMLTree *tree = (struct ResXMLTree *)_PTR(block);
	if (!tree) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), NULL);
		return 0;
	}
	return tl_xml_tree_string_block(tree);
}

JNIEXPORT jint JNICALL Java_android_content_res_XmlBlock_nativeGetText(JNIEnv *env, jclass this, jlong parser_ptr)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	if (!parser)
		return -1;
	return _ZNK7android12ResXMLParser9getTextIDEv(parser);
}

JNIEXPORT jint JNICALL Java_android_content_res_XmlBlock_nativeGetNamespace(JNIEnv *env, jclass this, jlong parser_ptr)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	if (!parser)
		return -1;
	return _ZNK7android12ResXMLParser21getElementNamespaceIDEv(parser);
}

JNIEXPORT jint JNICALL Java_android_content_res_XmlBlock_nativeGetAttributeNamespace(JNIEnv *env, jclass this, jlong parser_ptr, jint idx)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	if (!parser)
		return 0;
	return _ZNK7android12ResXMLParser23getAttributeNamespaceIDEm(parser, idx);
}

/* AOSP: the id attribute's string value index, -1 when the element has none */
JNIEXPORT jint JNICALL Java_android_content_res_XmlBlock_nativeGetIdAttribute(JNIEnv *env, jclass this, jlong parser_ptr)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	if (!parser)
		return 0;
	ssize_t idx = ResXMLParser_indexOfID(parser);
	return idx >= 0 ? ResXMLParser_getAttributeValueStringID(parser, idx) : -1;
}

JNIEXPORT jlong JNICALL Java_android_content_res_XmlBlock_nativeCreateParseState(JNIEnv *env, jobject this, jlong block)
{
	struct ResXMLTree *tree = (struct ResXMLTree *)_PTR(block);
	struct ResXMLParser *parser = ResXMLParser_new(tree);
	ResXMLParser_restart(parser);
	return _INTPTR(parser);
}

JNIEXPORT jint JNICALL Java_android_content_res_XmlBlock_nativeNext(JNIEnv *env, jobject this, jlong parser_ptr)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	while (1) {
		enum event_code_t code = ResXMLParser_next(parser);
		switch (code) {
			case START_TAG:
			case END_TAG:
			case TEXT:
				return code - 0x100;

			case START_DOCUMENT:
			case END_DOCUMENT:
				return code;

			case BAD_DOCUMENT:
				(*env)->ThrowNew(env, (*env)->FindClass(env, "org/xmlpull/v1/XmlPullParserException"), "ResXMLParser_next returned BAD_DOCUMENT");
				return code;
			default:
				continue;
		}
	}
}

JNIEXPORT jstring JNICALL Java_android_content_res_XmlBlock_nativeGetPooledString(JNIEnv *env, jobject this, jlong parser_ptr, jint index)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	const struct ResStringPool *string_pool = ResXMLParser_getStrings(parser);
	size_t len;
	const char16_t *string = ResStringPool_stringAt(string_pool, index, &len);
	if (!string)
		return NULL;
	return (*env)->NewString(env, string, len);
}

JNIEXPORT jstring JNICALL Java_android_content_res_XmlBlock_nativeGetName(JNIEnv *env, jobject this, jlong parser_ptr)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	int idx = ResXMLParser_getElementNameID(parser);
	if (idx < 0)
		return NULL;
	return Java_android_content_res_XmlBlock_nativeGetPooledString(env, this, parser_ptr, idx);
}

JNIEXPORT jint JNICALL Java_android_content_res_XmlBlock_nativeGetAttributeCount(JNIEnv *env, jobject this, jlong parser_ptr)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	return ResXMLParser_getAttributeCount(parser);
}

JNIEXPORT jint JNICALL Java_android_content_res_XmlBlock_nativeGetAttributeResource(JNIEnv *env, jobject this, jlong parser_ptr, jint index)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	return ResXMLParser_getAttributeNameResID(parser, index);
}

JNIEXPORT jint JNICALL Java_android_content_res_XmlBlock_nativeGetAttributeIndex(JNIEnv *env, jobject this, jlong parser_ptr, jstring namespace_str, jstring name_str)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	const char16_t *namespace = NULL;
	int namespace_len = 0;
	if (namespace_str) {
		namespace = (*env)->GetStringChars(env, namespace_str, NULL);
		namespace_len = (*env)->GetStringLength(env, namespace_str);
	}
	const char16_t *name = (*env)->GetStringChars(env, name_str, NULL);
	int name_len = (*env)->GetStringLength(env, name_str);
	int ret = ResXMLParser_indexOfAttribute(parser, namespace, namespace_len, name, name_len);
	if (namespace_str)
		(*env)->ReleaseStringChars(env, namespace_str, namespace);
	(*env)->ReleaseStringChars(env, name_str, name);
	return ret;
}

JNIEXPORT jstring JNICALL Java_android_content_res_XmlBlock_nativeGetAttributeStringValue(JNIEnv *env, jobject this, jlong parser_ptr, jint index)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	int idx = ResXMLParser_getAttributeValueStringID(parser, index);
	if (idx < 0)
		return NULL;
	return Java_android_content_res_XmlBlock_nativeGetPooledString(env, this, parser_ptr, idx);
}

JNIEXPORT jint JNICALL Java_android_content_res_XmlBlock_nativeGetLineNumber(JNIEnv *env, jobject this, jlong parser_ptr)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	return ResXMLParser_getLineNumber(parser);
}

JNIEXPORT jint JNICALL Java_android_content_res_XmlBlock_nativeGetAttributeDataType(JNIEnv *env, jobject this, jlong parser_ptr, jint index)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	return ResXMLParser_getAttributeDataType(parser, index);
}

JNIEXPORT jint JNICALL Java_android_content_res_XmlBlock_nativeGetAttributeData(JNIEnv *env, jobject this, jlong parser_ptr, jint index)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	return ResXMLParser_getAttributeData(parser, index);
}

JNIEXPORT jint JNICALL Java_android_content_res_XmlBlock_nativeGetAttributeName(JNIEnv *env, jclass this, jlong parser_ptr, jint index)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	return ResXMLParser_getAttributeNameID(parser, index);
}

JNIEXPORT void JNICALL Java_android_content_res_XmlBlock_nativeDestroyParseState(JNIEnv *env, jobject this, jlong parser_ptr)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	ResXMLParser_delete(parser);
}

JNIEXPORT void JNICALL Java_android_content_res_XmlBlock_nativeDestroy(JNIEnv *env, jobject this, jlong block)
{
	struct ResXMLTree *tree = (struct ResXMLTree *)_PTR(block);
	tl_xml_tree_delete(tree);
}

JNIEXPORT jstring JNICALL Java_android_content_res_XmlBlock_nativeGetClassAttribute(JNIEnv *env, jobject this, jlong parser_ptr)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	int idx = ResXMLParser_indexOfClass(parser);
	return Java_android_content_res_XmlBlock_nativeGetAttributeStringValue(env, this, parser_ptr, idx);
}

JNIEXPORT jint JNICALL Java_android_content_res_XmlBlock_nativeGetStyleAttribute(JNIEnv *env, jobject this, jlong parser_ptr)
{
	struct ResXMLParser *parser = (struct ResXMLParser *)_PTR(parser_ptr);
	int idx = ResXMLParser_indexOfStyle(parser);
	struct Res_value value;
	ResXMLParser_getAttributeValue(parser, idx, &value);
	return value.data;
}
