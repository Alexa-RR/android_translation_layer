/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.provider;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import java.util.List;

public final class DocumentsContract {
	public static final String EXTRA_LOADING = "loading";
	public static final String EXTRA_INFO = "info";
	public static final String EXTRA_ERROR = "error";
	public static final String EXTRA_RESULT = "result";
	public static final String METHOD_CREATE_DOCUMENT = "android:createDocument";
	public static final String METHOD_RENAME_DOCUMENT = "android:renameDocument";
	public static final String METHOD_DELETE_DOCUMENT = "android:deleteDocument";
	public static final String METHOD_COPY_DOCUMENT = "android:copyDocument";
	public static final String METHOD_MOVE_DOCUMENT = "android:moveDocument";
	public static final String METHOD_IS_CHILD_DOCUMENT = "android:isChildDocument";
	public static final String METHOD_REMOVE_DOCUMENT = "android:removeDocument";
	public static final String METHOD_EJECT_ROOT = "android:ejectRoot";
	public static final String METHOD_FIND_DOCUMENT_PATH = "android:findDocumentPath";
	public static final String METHOD_CREATE_WEB_LINK_INTENT = "android:createWebLinkIntent";
	public static final String METHOD_GET_DOCUMENT_METADATA = "android:getDocumentMetadata";
	public static final String EXTRA_PARENT_URI = "parentUri";
	public static final String EXTRA_URI = "uri";
	public static final String EXTRA_URI_PERMISSIONS = "uriPermissions";
	public static final String EXTRA_OPTIONS = "options";
	/* seems these are not supposed to be ABI but some apps disagree */
	private static final String PATH_ROOT = "root";
	private static final String PATH_RECENT = "recent";
	private static final String PATH_DOCUMENT = "document";
	private static final String PATH_CHILDREN = "children";
	private static final String PATH_SEARCH = "search";
	private static final String PATH_TREE = "tree";
	private static final String PARAM_QUERY = "query";
	private static final String PARAM_MANAGE = "manage";

	public static String getDocumentId(Uri documentUri) {
		final List<String> paths = documentUri.getPathSegments();
		if (paths.size() >= 2 && PATH_DOCUMENT.equals(paths.get(0))) {
			return paths.get(1);
		}
		if (paths.size() >= 4 && PATH_TREE.equals(paths.get(0))
		    && PATH_DOCUMENT.equals(paths.get(2))) {
			return paths.get(3);
		}
		throw new IllegalArgumentException("Invalid URI: " + documentUri);
	}

	public static String getTreeDocumentId(Uri documentUri) {
		final List<String> paths = documentUri.getPathSegments();
		if (paths.size() >= 2 && PATH_TREE.equals(paths.get(0))) {
			return paths.get(1);
		}
		throw new IllegalArgumentException("Invalid URI: " + documentUri);
	}

	public static boolean isContentUri(Uri uri) {
		return uri != null && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme());
	}

	private static boolean isDocumentsProvider(Context context, String authority) {
		throw new RuntimeException("DocumentsContract.isDocumentsProvider not implemented yet");
	}

	public static boolean isDocumentUri(Context context, Uri uri) {
		if (isContentUri(uri) && isDocumentsProvider(context, uri.getAuthority())) {
			final List<String> paths = uri.getPathSegments();
			if (paths.size() == 2) {
				return PATH_DOCUMENT.equals(paths.get(0));
			} else if (paths.size() == 4) {
				return PATH_TREE.equals(paths.get(0)) && PATH_DOCUMENT.equals(paths.get(2));
			}
		}
		return false;
	}

	public static boolean isTreeUri(Uri uri) {
		final List<String> paths = uri.getPathSegments();
		return (paths.size() >= 2 && PATH_TREE.equals(paths.get(0)));
	}

	public static Uri buildChildDocumentsUriUsingTree(Uri treeUri, String parentDocumentId) {
		return new Uri.Builder()
		    .scheme(ContentResolver.SCHEME_CONTENT)
		    .authority(treeUri.getAuthority())
		    .appendPath(PATH_TREE)
		    .appendPath(getTreeDocumentId(treeUri))
		    .appendPath(PATH_DOCUMENT)
		    .appendPath(parentDocumentId)
		    .appendPath(PATH_CHILDREN)
		    .build();
	}

	public static Uri buildDocumentUriUsingTree(Uri treeUri, String documentId) {
		return new Uri.Builder()
		    .scheme(ContentResolver.SCHEME_CONTENT)
		    .authority(treeUri.getAuthority())
		    .appendPath(PATH_TREE)
		    .appendPath(getTreeDocumentId(treeUri))
		    .appendPath(PATH_DOCUMENT)
		    .appendPath(documentId)
		    .build();
	}

	/*
	 * AOSP: the document operations are calls (ContentResolver.call) to the DocumentsProvider of the
	 * URI's authority; a missing provider or a failed operation logs and returns null/false, and a
	 * provider's RuntimeException is rethrown, as for apps targeting O and later.
	 */
	private static android.os.Bundle tlCall(ContentResolver content, Uri uri, String method, android.os.Bundle in) {
		return content.call(uri.getAuthority(), method, null, in);
	}

	private static void tlRethrow(Exception e) {
		if (e instanceof RuntimeException && !(e instanceof IllegalArgumentException && e.getMessage() != null
		                                       && e.getMessage().startsWith("Unknown authority")))
			throw (RuntimeException) e;
	}

	public static Uri copyDocument(ContentResolver content, Uri sourceDocumentUri, Uri targetParentDocumentUri) {
		try {
			final android.os.Bundle in = new android.os.Bundle();
			in.putParcelable(EXTRA_URI, sourceDocumentUri);
			in.putParcelable(EXTRA_TARGET_URI, targetParentDocumentUri);
			final android.os.Bundle out = tlCall(content, sourceDocumentUri, METHOD_COPY_DOCUMENT, in);
			return out.getParcelable(EXTRA_URI);
		} catch (Exception e) {
			android.util.Log.w(TAG, "Failed to copy document", e);
			tlRethrow(e);
			return null;
		}
	}

	public static Uri createDocument(ContentResolver content, Uri parentDocumentUri, String mimeType, String displayName) {
		try {
			final android.os.Bundle in = new android.os.Bundle();
			in.putParcelable(EXTRA_URI, parentDocumentUri);
			in.putString(COLUMN_MIME_TYPE, mimeType);
			in.putString(COLUMN_DISPLAY_NAME, displayName);
			final android.os.Bundle out = tlCall(content, parentDocumentUri, METHOD_CREATE_DOCUMENT, in);
			return out.getParcelable(EXTRA_URI);
		} catch (Exception e) {
			android.util.Log.w(TAG, "Failed to create document", e);
			tlRethrow(e);
			return null;
		}
	}

	public static boolean deleteDocument(ContentResolver content, Uri documentUri) {
		try {
			final android.os.Bundle in = new android.os.Bundle();
			in.putParcelable(EXTRA_URI, documentUri);
			tlCall(content, documentUri, METHOD_DELETE_DOCUMENT, in);
			return true;
		} catch (Exception e) {
			android.util.Log.w(TAG, "Failed to delete document", e);
			tlRethrow(e);
			return false;
		}
	}

	public static Uri renameDocument(ContentResolver content, Uri documentUri, String displayName) {
		try {
			final android.os.Bundle in = new android.os.Bundle();
			in.putParcelable(EXTRA_URI, documentUri);
			in.putString(COLUMN_DISPLAY_NAME, displayName);
			final android.os.Bundle out = tlCall(content, documentUri, METHOD_RENAME_DOCUMENT, in);
			final Uri outUri = out.getParcelable(EXTRA_URI);
			return (outUri != null) ? outUri : documentUri;
		} catch (Exception e) {
			android.util.Log.w(TAG, "Failed to rename document", e);
			tlRethrow(e);
			return null;
		}
	}

	public static boolean isChildDocument(ContentResolver content, Uri parentDocumentUri, Uri childDocumentUri) {
		try {
			final android.os.Bundle in = new android.os.Bundle();
			in.putParcelable(EXTRA_URI, parentDocumentUri);
			in.putParcelable(EXTRA_TARGET_URI, childDocumentUri);
			final android.os.Bundle out = tlCall(content, parentDocumentUri, METHOD_IS_CHILD_DOCUMENT, in);
			if (out == null)
				throw new RuntimeException("Failed to get a response from isChildDocument query.");
			if (!out.containsKey(EXTRA_RESULT))
				throw new RuntimeException("Response did not include result field..");
			return out.getBoolean(EXTRA_RESULT);
		} catch (Exception e) {
			android.util.Log.w(TAG, "Failed to create document", e);
			tlRethrow(e);
			return false;
		}
	}

	public static Uri buildDocumentUri(String authority, String documentId) {
		return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).appendPath(PATH_DOCUMENT).appendPath(documentId).build();
	}

	public final static class Document {
		public static final String COLUMN_DOCUMENT_ID = "document_id";
		public static final String COLUMN_MIME_TYPE = "mime_type";
		public static final String COLUMN_DISPLAY_NAME = "_display_name"; //OpenableColumns.DISPLAY_NAME;
		public static final String COLUMN_SUMMARY = "summary";
		public static final String COLUMN_LAST_MODIFIED = "last_modified";
		public static final String COLUMN_ICON = "icon";
		public static final String COLUMN_FLAGS = "flags";
		public static final String COLUMN_SIZE = "_size"; //OpenableColumns.SIZE;
		public static final String MIME_TYPE_DIR = "vnd.android.document/directory";

		public static final int FLAG_SUPPORTS_THUMBNAIL = 1;
		public static final int FLAG_SUPPORTS_WRITE = 1 << 1;
		public static final int FLAG_SUPPORTS_DELETE = 1 << 2;
		public static final int FLAG_DIR_SUPPORTS_CREATE = 1 << 3;
		public static final int FLAG_DIR_PREFERS_GRID = 1 << 4;
		public static final int FLAG_DIR_PREFERS_LAST_MODIFIED = 1 << 5;
		public static final int FLAG_SUPPORTS_RENAME = 1 << 6;
		public static final int FLAG_SUPPORTS_COPY = 1 << 7;
		public static final int FLAG_SUPPORTS_MOVE = 1 << 8;
		public static final int FLAG_VIRTUAL_DOCUMENT = 1 << 9;
		public static final int FLAG_SUPPORTS_REMOVE = 1 << 10;
		public static final int FLAG_SUPPORTS_SETTINGS = 1 << 11;
		public static final int FLAG_WEB_LINKABLE = 1 << 12;
		public static final int FLAG_PARTIAL = 1 << 13;
		public static final int FLAG_SUPPORTS_METADATA = 1 << 14;
		public static final int FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE = 1 << 15;
	}

	private static final String TAG = "DocumentsContract";
	/** AOSP (hidden): the target of copy/move/isChild calls */
	public static final String EXTRA_TARGET_URI = "android.content.extra.TARGET_URI";
	/* Document column names (AOSP DocumentsContract.Document) used in the call extras */
	private static final String COLUMN_MIME_TYPE = "mime_type";
	private static final String COLUMN_DISPLAY_NAME = "_display_name";

	/** AOSP: the URI of the children of a document of a provider. */
	public static Uri buildChildDocumentsUri(String authority, String parentDocumentId) {
		return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority)
		    .appendPath(PATH_DOCUMENT).appendPath(parentDocumentId).appendPath(PATH_CHILDREN).build();
	}

	/** AOSP: the URI of a document tree (granted with ACTION_OPEN_DOCUMENT_TREE). */
	public static Uri buildTreeDocumentUri(String authority, String documentId) {
		return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority)
		    .appendPath(PATH_TREE).appendPath(documentId).build();
	}

	/** AOSP: removes a document from a parent (a document may be in several). */
	public static boolean removeDocument(ContentResolver content, Uri documentUri, Uri parentDocumentUri) {
		try {
			final android.os.Bundle in = new android.os.Bundle();
			in.putParcelable(EXTRA_URI, documentUri);
			in.putParcelable(EXTRA_PARENT_URI, parentDocumentUri);
			tlCall(content, documentUri, METHOD_REMOVE_DOCUMENT, in);
			return true;
		} catch (Exception e) {
			android.util.Log.w(TAG, "Failed to remove document", e);
			tlRethrow(e);
			return false;
		}
	}
}
