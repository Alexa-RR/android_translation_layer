#ifndef ATL_CONTENT_TL_XML_TREE_H
#define ATL_CONTENT_TL_XML_TREE_H

#include <jni.h>
#include <stddef.h>

struct ResXMLTree;

/* an XML tree over a copy of DATA, kept with the tree (its string pool's styles are read from it) */
struct ResXMLTree *tl_xml_tree_new(const void *data, size_t size);
/* deletes a tree from tl_xml_tree_new, its bytes and its string block */
void tl_xml_tree_delete(struct ResXMLTree *tree);
/* the StringBlock native handle over the tree's string pool (owned by the tree) */
jlong tl_xml_tree_string_block(struct ResXMLTree *tree);

#endif
