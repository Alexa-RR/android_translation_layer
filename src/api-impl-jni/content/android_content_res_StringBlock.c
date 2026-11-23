/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * android.content.res.StringBlock's natives (AOSP android_util_StringBlock.cpp) over androidfw's
 * ResStringPool, and the XML trees whose pools XmlBlock reads through a StringBlock
 * (XmlBlock.nativeGetStringBlock).
 *
 * androidfw's C API reads a pool's strings but not its style spans, so a block keeps the pool's
 * bytes too and reads the spans from them, by the ResStringPool_header layout: the string and
 * style offset arrays after the header, each style a list of ResStringPool_span {name, firstChar,
 * lastChar} ended by 0xFFFFFFFF.
 */

#include <glib.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <androidfw/androidfw_c_api.h>

#include "../defines.h"
#include "../util.h"
#include "../generated_headers/android_content_res_StringBlock.h"
#include "tl_xml_tree.h"

#define RES_STRING_POOL_TYPE 0x0001
#define RES_XML_TYPE 0x0003
#define SPAN_END 0xFFFFFFFFu

struct tl_string_block {
	const struct ResStringPool *pool; /* the strings */
	struct ResStringPool *owned_pool; /* nativeCreate's: deleted with the block */
	const uint8_t *chunk;             /* the pool chunk's bytes (styles), or NULL */
	size_t chunk_size;
	uint8_t *owned_data;              /* nativeCreate's copy of the app's bytes */
};

static uint32_t rd32(const uint8_t *p)
{
	return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static uint16_t rd16(const uint8_t *p)
{
	return (uint16_t)(p[0] | (p[1] << 8));
}

/* the style spans of string IDX as AOSP's nativeGetStyle returns them (3 ints a span), or NULL */
static jintArray block_style(JNIEnv *env, struct tl_string_block *b, int idx)
{
	if (!b->chunk || b->chunk_size < 28 || idx < 0)
		return NULL;
	const uint8_t *c = b->chunk;
	uint16_t header_size = rd16(c + 2);
	uint32_t size = rd32(c + 4);
	if (size > b->chunk_size)
		size = b->chunk_size;
	uint32_t string_count = rd32(c + 8);
	uint32_t style_count = rd32(c + 12);
	uint32_t styles_start = rd32(c + 24);
	if ((uint32_t)idx >= style_count || styles_start == 0)
		return NULL;
	uint64_t entry = (uint64_t)header_size + 4ull * string_count + 4ull * idx;
	if (entry + 4 > size)
		return NULL;
	uint64_t pos = (uint64_t)styles_start + rd32(c + entry);
	/* count the spans first (each 12 bytes), all within the chunk */
	int num = 0;
	for (uint64_t p = pos;; p += 12) {
		if (p + 4 > size)
			return NULL;
		if (rd32(c + p) == SPAN_END)
			break;
		if (p + 12 > size)
			return NULL;
		num++;
	}
	if (num == 0)
		return NULL;
	jintArray array = (*env)->NewIntArray(env, num * 3);
	if (!array)
		return NULL;
	jint *spans = (*env)->GetIntArrayElements(env, array, NULL);
	for (int i = 0; i < num; i++) {
		const uint8_t *s = c + pos + 12 * i;
		spans[3 * i] = (jint)rd32(s);
		spans[3 * i + 1] = (jint)rd32(s + 4);
		spans[3 * i + 2] = (jint)rd32(s + 8);
	}
	(*env)->ReleaseIntArrayElements(env, array, spans, 0);
	return array;
}

JNIEXPORT jlong JNICALL Java_android_content_res_StringBlock_nativeCreate(JNIEnv *env, jclass clazz, jbyteArray bArray, jint off, jint len)
{
	if (!bArray) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), NULL);
		return 0;
	}
	jsize bLen = (*env)->GetArrayLength(env, bArray);
	if (off < 0 || off >= bLen || len < 0 || len > bLen || (off + len) > bLen) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IndexOutOfBoundsException"), NULL);
		return 0;
	}
	struct tl_string_block *b = calloc(1, sizeof(*b));
	b->owned_data = malloc(len ? len : 1);
	(*env)->GetByteArrayRegion(env, bArray, off, len, (jbyte *)b->owned_data);
	b->owned_pool = ResStringPool_new(b->owned_data, len, false);
	if (!b->owned_pool || ResStringPool_getErrors(b->owned_pool) != 0) {
		if (b->owned_pool)
			ResStringPool_delete(b->owned_pool);
		free(b->owned_data);
		free(b);
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), NULL);
		return 0;
	}
	b->pool = b->owned_pool;
	b->chunk = b->owned_data;
	b->chunk_size = len;
	return _INTPTR(b);
}

JNIEXPORT jint JNICALL Java_android_content_res_StringBlock_nativeGetSize(JNIEnv *env, jclass clazz, jlong token)
{
	struct tl_string_block *b = _PTR(token);
	if (!b) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), NULL);
		return 0;
	}
	return ResStringPool_getSize(b->pool);
}

JNIEXPORT jstring JNICALL Java_android_content_res_StringBlock_nativeGetString(JNIEnv *env, jclass clazz, jlong token, jint idx)
{
	struct tl_string_block *b = _PTR(token);
	if (!b) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), NULL);
		return NULL;
	}
	if (idx < 0 || (size_t)idx >= ResStringPool_getSize(b->pool))
		return NULL;
	size_t len;
	const char16_t *str = ResStringPool_stringAt(b->pool, idx, &len);
	if (!str)
		return NULL;
	return (*env)->NewString(env, (const jchar *)str, len);
}

JNIEXPORT jintArray JNICALL Java_android_content_res_StringBlock_nativeGetStyle(JNIEnv *env, jclass clazz, jlong token, jint idx)
{
	struct tl_string_block *b = _PTR(token);
	if (!b) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), NULL);
		return NULL;
	}
	return block_style(env, b, idx);
}

JNIEXPORT void JNICALL Java_android_content_res_StringBlock_nativeDestroy(JNIEnv *env, jclass clazz, jlong token)
{
	struct tl_string_block *b = _PTR(token);
	if (!b || !b->owned_pool)
		return; /* a block over an XML tree's pool goes with the tree */
	ResStringPool_delete(b->owned_pool);
	free(b->owned_data);
	free(b);
}

/* ---- the XML trees (XmlBlock): their bytes, and the StringBlock over their pool ---- */

struct tl_xml_data {
	uint8_t *data;
	size_t size;
	struct tl_string_block *strings;
};

static GMutex xml_lock;
static GHashTable *xml_trees; /* struct ResXMLTree * -> struct tl_xml_data * */

struct ResXMLTree *tl_xml_tree_new(const void *data, size_t size)
{
	uint8_t *copy = malloc(size ? size : 1);
	memcpy(copy, data, size);
	struct ResXMLTree *tree = ResXMLTree_new();
	/* the tree reads the copy, which lives as long as the tree (tl_xml_tree_delete) */
	ResXMLTree_setTo(tree, copy, size, false);
	struct tl_xml_data *x = calloc(1, sizeof(*x));
	x->data = copy;
	x->size = size;
	g_mutex_lock(&xml_lock);
	if (!xml_trees)
		xml_trees = g_hash_table_new(g_direct_hash, g_direct_equal);
	g_hash_table_insert(xml_trees, tree, x);
	g_mutex_unlock(&xml_lock);
	return tree;
}

void tl_xml_tree_delete(struct ResXMLTree *tree)
{
	g_mutex_lock(&xml_lock);
	struct tl_xml_data *x = xml_trees ? g_hash_table_lookup(xml_trees, tree) : NULL;
	if (x)
		g_hash_table_remove(xml_trees, tree);
	g_mutex_unlock(&xml_lock);
	ResXMLTree_delete(tree);
	if (x) {
		free(x->strings);
		free(x->data);
		free(x);
	}
}

/* AOSP XmlBlock.nativeGetStringBlock: the tree's string pool, as a block the tree owns */
jlong tl_xml_tree_string_block(struct ResXMLTree *tree)
{
	g_mutex_lock(&xml_lock);
	struct tl_xml_data *x = xml_trees ? g_hash_table_lookup(xml_trees, tree) : NULL;
	if (x && !x->strings) {
		struct tl_string_block *b = calloc(1, sizeof(*b));
		b->pool = ResXMLTree_getStrings(tree);
		/* ResXMLTree_header, then its first chunk: the string pool */
		if (x->size >= 8 && rd16(x->data) == RES_XML_TYPE) {
			uint16_t header_size = rd16(x->data + 2);
			if ((size_t)header_size + 8 <= x->size && rd16(x->data + header_size) == RES_STRING_POOL_TYPE) {
				b->chunk = x->data + header_size;
				b->chunk_size = x->size - header_size;
			}
		}
		x->strings = b;
	}
	struct tl_string_block *b = x ? x->strings : NULL;
	g_mutex_unlock(&xml_lock);
	return _INTPTR(b);
}
