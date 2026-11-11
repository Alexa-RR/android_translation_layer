package android.content;

import android.atl.ATLLoadedApp;
import android.atl.ATLMediaContentProvider;
import android.atl.ATLProvider;
import android.atl.annotation.Export;
import android.content.pm.PackageParser;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

public abstract class ContentProvider {

	static final HashMap<String, ATLProvider> atl_providers = new HashMap<>();

	@Export
	static void createContentProviders() {
		atl_providers.put("media", new ATLProvider(new ATLMediaContentProvider()));
		ATLLoadedApp primary = ATLLoadedApp.getPrimaryApplication();
		for (PackageParser.Provider provider_parsed : primary.pkg.providers) {
			String process_name = provider_parsed.info.processName;
			if (process_name != null && process_name.contains(":")) {
				/* NOTE: even if it doesn't contain `:`, if it's not null we probably
				 * need to check what it's requesting; `:` means it wants us to spawn
				 * a new process, which we currently don't support */
				System.out.println("not creating provider " + provider_parsed.className + ", it wants to be started in a new process (" + process_name + ")");
				continue;
			}
			atl_providers.put(provider_parsed.info.authority,
			                  new ATLProvider(primary, provider_parsed));
		}
		// getContentProvider() initializes the content providers,
		// we also opportunistically remove the ones that failed to load
		atl_providers.values().removeIf(new Predicate<ATLProvider>() {
			@Override
			public boolean test(ATLProvider atlProvider) {
				return atlProvider.getContentProvider() == null;
			}
		});
	}

	static ContentProvider atl_get_content_provider(String authority) {
		ATLProvider atlProvider = atl_providers.get(authority);
		return atlProvider == null ? null : atlProvider.getContentProvider();
	}

	public boolean onCreate() { return false; }

	/** AOSP: the context given to attachInfo (the app's; before that, null on Android). */
	public Context getContext() {
		return tlContext != null ? tlContext : ATLLoadedApp.getPrimaryApplication().getApplication();
	}

	public ContentProviderResult[] applyBatch(String authority, ArrayList<ContentProviderOperation> operations) throws OperationApplicationException {
		return applyBatch(operations);
	}

	public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations) throws OperationApplicationException {
		final int numOperations = operations.size();
		final ContentProviderResult[] results = new ContentProviderResult[numOperations];
		for (int i = 0; i < numOperations; i++) {
			results[i] = operations.get(i).apply(this, results, i);
		}
		return results;
	}

	public abstract Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder);

	public abstract Uri insert(Uri uri, ContentValues values);

	public abstract int update(Uri uri, ContentValues values, String selection, String[] selectionArgs);

	public abstract int delete(Uri uri, String selection, String[] selectionArgs);

	public abstract String getType(Uri uri);

	public abstract ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException;

	public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
		ParcelFileDescriptor fd = openFile(uri, mode);
		return fd != null ? new AssetFileDescriptor(fd, 0, -1) : null;
	}

	/**
	 * AOSP: attaches the context and the manifest's permissions, export flag and authorities.
	 * AOSP calls onCreate() at the end of this; ATL's ATLProvider calls attachInfo and then
	 * onCreate() itself (the same order), so onCreate is not called a second time here.
	 */
	public void attachInfo(Context context, ProviderInfo provider) {
		tlAttachInfo(context, provider);
	}

	public String getCallingPackage() {
		return ATLLoadedApp.getPrimaryApplication().pkg.packageName;
	}

	/* AOSP (hidden): user id prefixes of content URIs ("userId@authority") */
	public static int getUserIdFromAuthority(String auth, int defaultUserId) {
		if (auth == null)
			return defaultUserId;
		int end = auth.lastIndexOf(64);
		if (end == -1)
			return defaultUserId;
		String userIdString = auth.substring(0, end);
		try {
			return Integer.parseInt(userIdString);
		} catch (NumberFormatException e) {
			android.util.Log.w("ContentProvider", "Error parsing userId.", e);
			return android.os.UserHandle.USER_NULL;
		}
	}

	public static int getUserIdFromAuthority(String auth) {
		return getUserIdFromAuthority(auth, android.os.UserHandle.USER_CURRENT);
	}

	public static int getUserIdFromUri(android.net.Uri uri, int defaultUserId) {
		if (uri == null)
			return defaultUserId;
		return getUserIdFromAuthority(uri.getAuthority(), defaultUserId);
	}

	public static int getUserIdFromUri(android.net.Uri uri) {
		return getUserIdFromUri(uri, android.os.UserHandle.USER_CURRENT);
	}

	public static boolean uriHasUserId(android.net.Uri uri) {
		if (uri == null)
			return false;
		return !android.text.TextUtils.isEmpty(uri.getUserInfo());
	}

	public static android.net.Uri maybeAddUserId(android.net.Uri uri, int userId) {
		if (uri == null)
			return null;
		if (userId != android.os.UserHandle.USER_CURRENT && "content".equals(uri.getScheme())) {
			if (!uriHasUserId(uri)) {
				android.net.Uri.Builder builder = uri.buildUpon();
				builder.encodedAuthority("" + userId + "@" + uri.getEncodedAuthority());
				return builder.build();
			}
		}
		return uri;
	}

	/* AOSP ContentProvider state set by attachInfo */
	private Context tlContext;
	private String tlReadPermission;
	private String tlWritePermission;
	private android.content.pm.PathPermission[] tlPathPermissions;
	private boolean tlExported;
	private boolean tlSingleUser;
	private String[] tlAuthorities;
	private int tlMyUid;

	/** AOSP: the provider's own uid (its process's). */
	final int tlGetMyUid() {
		return tlMyUid;
	}

	/** AOSP: the context and the manifest data of the provider (once). */
	void tlAttachInfo(Context context, ProviderInfo info) {
		if (tlContext == null) {
			tlContext = context;
			tlMyUid = android.os.Process.myUid();
			if (info != null) {
				setReadPermission(info.readPermission);
				setWritePermission(info.writePermission);
				setPathPermissions(info.pathPermissions);
				tlExported = info.exported;
				tlSingleUser = (info.flags & ProviderInfo.FLAG_SINGLE_USER) != 0;
				setAuthorities(info.authority);
			}
		}
	}

	/** AOSP: sets the authorities (';' separated) this provider serves. */
	protected final void setAuthorities(String authorities) {
		if (authorities != null) {
			if (authorities.indexOf(';') == -1) {
				tlAuthorities = new String[] {authorities};
			} else {
				tlAuthorities = authorities.split(";");
			}
		} else {
			tlAuthorities = null;
		}
	}

	/** AOSP (hidden): whether this provider serves the authority. */
	protected final boolean matchesOurAuthorities(String authority) {
		if (tlAuthorities != null) {
			for (String a : tlAuthorities) {
				if (a.equals(authority))
					return true;
			}
		}
		return false;
	}

	/** AOSP: the permission readers need, from the manifest or setReadPermission. */
	public final String getReadPermission() {
		return tlReadPermission;
	}

	protected final void setReadPermission(String permission) {
		tlReadPermission = permission;
	}

	/** AOSP: the permission writers need. */
	public final String getWritePermission() {
		return tlWritePermission;
	}

	protected final void setWritePermission(String permission) {
		tlWritePermission = permission;
	}

	/** AOSP: the path-specific permissions. */
	public final android.content.pm.PathPermission[] getPathPermissions() {
		return tlPathPermissions;
	}

	protected final void setPathPermissions(android.content.pm.PathPermission[] permissions) {
		tlPathPermissions = permissions;
	}

	/** AOSP: whether this provider runs in a single user only (FLAG_SINGLE_USER). */
	public boolean isSingleUser() {
		return tlSingleUser;
	}

	/** AOSP: whether the manifest exports the provider. */
	public boolean isExported() {
		return tlExported;
	}

	/** AOSP: the context the provider was attached to (null before attachInfo). */
	public final Context requireContext() {
		final Context ctx = getContext();
		if (ctx == null) {
			throw new IllegalStateException("Cannot find context from the provider.");
		}
		return ctx;
	}

	/** AOSP: inserts each value with insert(); providers override it to batch. */
	public int bulkInsert(Uri uri, ContentValues[] values) {
		int numValues = values.length;
		for (int i = 0; i < numValues; i++) {
			insert(uri, values[i]);
		}
		return numValues;
	}

	/** AOSP: insert with extras; the default ignores them. */
	public Uri insert(Uri uri, ContentValues values, android.os.Bundle extras) {
		return insert(uri, values);
	}

	/** AOSP: the query of the Bundle arguments with the SQL ones taken from them. */
	public Cursor query(Uri uri, String[] projection, android.os.Bundle queryArgs,
	                    android.os.CancellationSignal cancellationSignal) {
		queryArgs = queryArgs != null ? queryArgs : android.os.Bundle.EMPTY;
		String sortClause = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER);
		return query(uri, projection, queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
		             queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS), sortClause, cancellationSignal);
	}

	/** AOSP: the cancellable query; the default ignores the signal. */
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
	                    String sortOrder, android.os.CancellationSignal cancellationSignal) {
		return query(uri, projection, selection, selectionArgs, sortOrder);
	}

	/** AOSP: provider-defined calls; the default implements none and returns null. */
	public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
		return null;
	}

	/** AOSP: call with the authority it was addressed to; the default drops it. */
	public android.os.Bundle call(String authority, String method, String arg, android.os.Bundle extras) {
		return call(method, arg, extras);
	}

	/** AOSP: the MIME types of streams openTypedAssetFile can give; none by default. */
	public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
		return null;
	}

	/** AOSP: providers without canonical URIs return null. */
	public Uri canonicalize(Uri url) {
		return null;
	}

	/** AOSP: the inverse of canonicalize; the default returns the URI unchanged. */
	public Uri uncanonicalize(Uri url) {
		return url;
	}

	/** AOSP: a provider that cannot refresh returns false. */
	public boolean refresh(Uri uri, android.os.Bundle extras, android.os.CancellationSignal cancellationSignal) {
		return false;
	}

	/** AOSP: openFile with a cancellation signal; the default ignores the signal. */
	public ParcelFileDescriptor openFile(Uri uri, String mode, android.os.CancellationSignal signal)
	    throws FileNotFoundException {
		return openFile(uri, mode);
	}

	/** AOSP: openAssetFile with a cancellation signal; the default ignores the signal. */
	public AssetFileDescriptor openAssetFile(Uri uri, String mode, android.os.CancellationSignal signal)
	    throws FileNotFoundException {
		return openAssetFile(uri, mode);
	}

	/**
	 * AOSP: a stream of the given MIME type. "*" + "/" + "*" or a filter the provider's type of the
	 * URI matches opens it as is; anything else is not available.
	 */
	public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, android.os.Bundle opts)
	    throws FileNotFoundException {
		if ("*/*".equals(mimeTypeFilter)) {
			// If they can take anything, the untyped open call is good enough.
			return openAssetFile(uri, "r");
		}
		String baseType = getType(uri);
		if (baseType != null && ClipDescription.compareMimeTypes(baseType, mimeTypeFilter)) {
			// Use old untyped open call if this provider has a type for this
			// URI and it matches the request.
			return openAssetFile(uri, "r");
		}
		throw new FileNotFoundException("Can't open " + uri + " as type " + mimeTypeFilter);
	}

	/** AOSP: openTypedAssetFile with a cancellation signal; the default ignores the signal. */
	public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, android.os.Bundle opts,
	                                              android.os.CancellationSignal signal) throws FileNotFoundException {
		return openTypedAssetFile(uri, mimeTypeFilter, opts);
	}

	/** AOSP: called when the provider is shut down (never in a running app); the default logs. */
	public void shutdown() {
		android.util.Log.w("ContentProvider", "implement ContentProvider shutdown() to make sure all database "
		                                      + "connections are gracefully shutdown");
	}

	/** AOSP: memory trim callback; the default does nothing (ComponentCallbacks2 contract). */
	public void onTrimMemory(int level) {
	}

	/** AOSP: low-memory callback; the default does nothing. */
	public void onLowMemory() {
	}
}
