package android.content;

import android.accounts.Account;
import android.atl.ATLActiveServices;
import android.atl.ATLLoadedApp;
import android.content.res.AssetFileDescriptor;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ContentResolver {
	public static final String SCHEME_CONTENT = "content";

	public static final String SYNC_EXTRAS_IGNORE_SETTINGS = "ignore_settings";

	/** AOSP: observe changes to uri (and to its descendants when notifyForDescendants). */
	public final void registerContentObserver(Uri uri, boolean notifyForDescendants, ContentObserver observer) {
		registerContentObserver(uri, notifyForDescendants, observer, getUserId());
	}
	/** AOSP: stops all registrations of the observer. */
	public final void unregisterContentObserver(ContentObserver observer) {
		tlUnregister(observer);
	}
	/** AOSP: notify registered observers that a row was updated (and sync to network). */
	public void notifyChange(Uri uri, ContentObserver observer) {
		notifyChange(uri, observer, true /* sync to network */);
	}
	/** AOSP: the user this resolver works for (the user of this process). */
	public int getUserId() {
		return android.os.UserHandle.myUserId();
	}
	/** AOSP (hidden): observe changes for a given user. */
	public final void registerContentObserver(Uri uri, boolean notifyForDescendants, ContentObserver observer, int userHandle) {
		tlRegister(uri, notifyForDescendants, observer, userHandle);
	}

	public ParcelFileDescriptor openFileDescriptor(Uri uri, String mode) throws FileNotFoundException {
		if ("file".equals(uri.getScheme())) {
			return ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.parseMode(mode));
		} else {
			ContentProvider provider = ContentProvider.atl_get_content_provider(uri.getAuthority());
			if (provider != null)
				return provider.openFile(uri, mode);
			else
				return null;
		}
	}

	public ParcelFileDescriptor openFileDescriptor(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
		return openFileDescriptor(uri, mode);
	}

	public AssetFileDescriptor openAssetFileDescriptor(Uri uri, String mode) throws FileNotFoundException {
		if ("file".equals(uri.getScheme())) {
			return new AssetFileDescriptor(ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.parseMode(mode)), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
		} else {
			ContentProvider provider = ContentProvider.atl_get_content_provider(uri.getAuthority());
			if (provider != null)
				return provider.openAssetFile(uri, mode);
			else
				return null;
		}
	}

	public AssetFileDescriptor openAssetFileDescriptor(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
		return openAssetFileDescriptor(uri, mode);
	}

	/** AOSP: a stream of the given MIME type from the provider of a content: URI. */
	public final AssetFileDescriptor openTypedAssetFileDescriptor(Uri uri, String mimeType, Bundle opts) throws FileNotFoundException {
		if (uri == null)
			throw new NullPointerException("uri");
		if (mimeType == null)
			throw new NullPointerException("mimeType");
		ContentProvider provider = ContentProvider.atl_get_content_provider(uri.getAuthority());
		if (provider == null)
			throw new FileNotFoundException("No content provider: " + uri);
		return provider.openTypedAssetFile(uri, mimeType, opts);
	}

	public final AssetFileDescriptor openTypedAssetFileDescriptor(Uri uri, String mimeType, Bundle opts, CancellationSignal cancellationSignal) throws FileNotFoundException {
		return openTypedAssetFileDescriptor(uri, mimeType, opts);
	}

	public InputStream openInputStream(Uri uri) throws FileNotFoundException {
		ParcelFileDescriptor fd = openFileDescriptor(uri, "r");
		return fd != null ? new ParcelFileDescriptor.AutoCloseInputStream(fd) : null;
	}

	public OutputStream openOutputStream(Uri uri) throws FileNotFoundException {
		ParcelFileDescriptor fd = openFileDescriptor(uri, "w");
		return fd != null ? new ParcelFileDescriptor.AutoCloseOutputStream(fd) : null;
	}

	public Cursor query(Uri uri, String[] projection, Bundle queryArgs, CancellationSignal cancellationSignal) {
		if ("file".equals(uri.getScheme())) {
			MatrixCursor cursor = new MatrixCursor(projection);
			Object[] row = new Object[projection.length];
			native_query_file_info(uri.getPath(), projection, row);
			cursor.addRow(row);
			return cursor;
		} else {
			return null;
		}
	}

	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		ContentProvider provider = ContentProvider.atl_get_content_provider(uri.getAuthority());
		if (provider != null) {
			return provider.query(uri, projection, selection, selectionArgs, sortOrder);
		} else if ("file".equals(uri.getScheme())) {
			if (projection == null)
				projection = new String[] {"_display_name", "mime_type"};
			MatrixCursor cursor = new MatrixCursor(projection);
			Object[] row = new Object[projection.length];
			native_query_file_info(uri.getPath(), projection, row);
			cursor.addRow(row);
			return cursor;
		} else {
			return null;
		}
	}

	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder, CancellationSignal cancellationSignal) {
		return query(uri, projection, selection, selectionArgs, sortOrder);
	}

	public int delete(Uri uri, String selection, String[] selectionArgs) {
		ContentProvider provider = ContentProvider.atl_get_content_provider(uri.getAuthority());
		if (provider != null)
			return provider.delete(uri, selection, selectionArgs);
		else
			return 0;
	}

	public Uri insert(Uri uri, ContentValues values) {
		ContentProvider provider = ContentProvider.atl_get_content_provider(uri.getAuthority());
		if (provider != null)
			return provider.insert(uri, values);
		else
			return null;
	}

	public String getType(Uri uri) {
		ContentProvider provider = ContentProvider.atl_get_content_provider(uri.getAuthority());
		if (provider != null)
			return provider.getType(uri);
		else
			return null;
	}

	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		ContentProvider provider = ContentProvider.atl_get_content_provider(uri.getAuthority());
		if (provider != null)
			return provider.update(uri, values, selection, selectionArgs);
		else
			return 0;
	}

	public ContentProviderResult[] applyBatch(String authority, ArrayList<ContentProviderOperation> operations) throws OperationApplicationException {
		ContentProvider provider = ContentProvider.atl_get_content_provider(authority);
		if (provider == null)
			throw new IllegalArgumentException("Unknown authority " + authority);
		return provider.applyBatch(authority, operations);
	}

	public List getPersistedUriPermissions() {
		return Collections.emptyList();
	}

	public static void requestSync(final Account account, final String authority, final Bundle extras) {
		// bind the app's sync adapter service and run the sync on a separate thread, like the SyncManager would
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		Intent intent = new Intent("android.content.SyncAdapter");
		intent.setPackage(app.pkg.packageName);
		ServiceConnection connection = new ServiceConnection() {
			@Override
			public void onServiceConnected(ComponentName name, IBinder service) {
				final ServiceConnection self = this;
				if (!(service instanceof AbstractThreadedSyncAdapter.SyncAdapterBinder)) {
					Log.w("ContentResolver", "requestSync: sync adapter service returned " + service);
					ATLActiveServices.unbindService(self);
					return;
				}
				final AbstractThreadedSyncAdapter adapter = ((AbstractThreadedSyncAdapter.SyncAdapterBinder)service).getSyncAdapter();
				new Thread(new Runnable() {
					@Override
					public void run() {
						ContentProvider provider = ContentProvider.atl_get_content_provider(authority);
						SyncResult syncResult = new SyncResult();
						try {
							adapter.onPerformSync(account, extras != null ? extras : new Bundle(), authority,
							                      provider != null ? new ContentProviderClient(new ContentResolver(), provider, authority, true) : null, syncResult);
							Log.i("ContentResolver", "requestSync: sync for " + authority + " finished: " + syncResult);
						} catch (Throwable t) {
							Log.e("ContentResolver", "requestSync: sync for " + authority + " failed", t);
						}
						// the SyncManager unbinds the adapter service when the sync is over
						ATLActiveServices.unbindService(self);
					}
				}, "SyncAdapterThread").start();
			}

			@Override
			public void onServiceDisconnected(ComponentName name) {
				// the SyncManager counts a sync whose adapter service went away as failed
				Log.w("ContentResolver", "requestSync: sync adapter service " + name + " disconnected during the sync for " + authority);
			}
		};
		if (!ATLActiveServices.bindService(app, intent, connection, Context.BIND_AUTO_CREATE))
			Log.w("ContentResolver", "requestSync: no sync adapter service found in " + app.pkg.packageName);
	}

	public static void cancelSync(Account account, String authority) {
	}

	public static void setMasterSyncAutomatically(boolean sync) {
	}

	public static boolean getMasterSyncAutomatically() {
		return true;
	}

	public static int getIsSyncable(Account account, String authority) {
		return 1;
	}

	public static boolean getSyncAutomatically(Account account, String authority) {
		return false;
	}

	public static void setSyncAutomatically(Account account, String authority, boolean sync) {
	}

	public static void addPeriodicSync(Account account, String authority, Bundle extras, long pollFrequency) {
	}

	public static void setIsSyncable(Account account, String authority, int syncable) {
	}

	public static boolean isSyncActive(Account account, String authority) {
		return false;
	}

	private static native void native_query_file_info(String path, String[] attributes, Object[] result);

	/* teslalinux: provider clients. ATL's providers are the app's own, in this process; a client is
	 * handed out for an authority that has one and null otherwise ("no such provider"), as on
	 * Android, which callers (GMS Phenotype, Play Core, Firebase) are written to handle. */
	public final ContentProviderClient acquireContentProviderClient(Uri uri) {
		if (uri == null)
			throw new NullPointerException("uri");
		return SCHEME_CONTENT.equals(uri.getScheme()) ? acquireContentProviderClient(uri.getAuthority()) : null;
	}

	public final ContentProviderClient acquireContentProviderClient(String name) {
		if (name == null)
			throw new NullPointerException("name");
		ContentProvider provider = ContentProvider.atl_get_content_provider(name);
		return provider != null ? new ContentProviderClient(this, provider, name, true) : null;
	}

	public final ContentProviderClient acquireUnstableContentProviderClient(Uri uri) {
		if (uri == null)
			throw new NullPointerException("uri");
		return SCHEME_CONTENT.equals(uri.getScheme()) ? acquireUnstableContentProviderClient(uri.getAuthority()) : null;
	}

	public final ContentProviderClient acquireUnstableContentProviderClient(String name) {
		if (name == null)
			throw new NullPointerException("name");
		ContentProvider provider = ContentProvider.atl_get_content_provider(name);
		return provider != null ? new ContentProviderClient(this, provider, name, false) : null;
	}

	/* AOSP query-argument keys (Bundle form of the SQL arguments) */
	public static final String QUERY_ARG_SQL_SELECTION = "android:query-arg-sql-selection";
	public static final String QUERY_ARG_SQL_SELECTION_ARGS = "android:query-arg-sql-selection-args";
	public static final String QUERY_ARG_SQL_SORT_ORDER = "android:query-arg-sql-sort-order";
	public static final String QUERY_ARG_SQL_GROUP_BY = "android:query-arg-sql-group-by";
	public static final String QUERY_ARG_SQL_HAVING = "android:query-arg-sql-having";
	public static final String QUERY_ARG_SQL_LIMIT = "android:query-arg-sql-limit";

	/* AOSP notifyChange flags */
	public static final int NOTIFY_SYNC_TO_NETWORK = 1 << 0;
	public static final int NOTIFY_SKIP_NOTIFY_FOR_DESCENDANTS = 1 << 1;
	public static final int NOTIFY_INSERT = 1 << 2;
	public static final int NOTIFY_UPDATE = 1 << 3;
	public static final int NOTIFY_DELETE = 1 << 4;
	public static final int NOTIFY_NO_DELAY = 1 << 15;

	/** AOSP (hidden): the Bundle query arguments for SQL selection, arguments and sort order. */
	public static android.os.Bundle createSqlQueryBundle(String selection, String[] selectionArgs, String sortOrder) {
		if (selection == null && selectionArgs == null && sortOrder == null) {
			return null;
		}
		android.os.Bundle queryArgs = new android.os.Bundle();
		if (selection != null) {
			queryArgs.putString(QUERY_ARG_SQL_SELECTION, selection);
		}
		if (selectionArgs != null) {
			queryArgs.putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
		}
		if (sortOrder != null) {
			queryArgs.putString(QUERY_ARG_SQL_SORT_ORDER, sortOrder);
		}
		return queryArgs;
	}

	/**
	 * teslalinux: the observer registry of AOSP's ContentService, for this process (the only one
	 * ATL runs an app in). Registrations are kept per observer; a change to a URI reaches observers
	 * of that URI, of its ancestors that asked for descendants, and of its descendants, exactly as
	 * ContentService.ObserverNode collects them.
	 */
	private static final class TlObserverEntry {
		final java.util.List<String> segments;
		final boolean notifyForDescendants;
		final ContentObserver observer;
		final int userHandle;

		TlObserverEntry(Uri uri, boolean notifyForDescendants, ContentObserver observer, int userHandle) {
			this.segments = tlUriSegments(uri);
			this.notifyForDescendants = notifyForDescendants;
			this.observer = observer;
			this.userHandle = userHandle;
		}
	}

	private static final java.util.ArrayList<TlObserverEntry> tlObservers = new java.util.ArrayList<>();

	/** ContentService.ObserverNode: the authority, then each path segment */
	private static java.util.List<String> tlUriSegments(Uri uri) {
		java.util.ArrayList<String> s = new java.util.ArrayList<>();
		s.add(uri.getAuthority());
		s.addAll(uri.getPathSegments());
		return s;
	}

	private static void tlRegister(Uri uri, boolean notifyForDescendants, ContentObserver observer, int userHandle) {
		if (uri == null)
			throw new NullPointerException("uri");
		if (observer == null)
			throw new NullPointerException("observer");
		synchronized (tlObservers) {
			tlObservers.add(new TlObserverEntry(uri, notifyForDescendants, observer, userHandle));
		}
	}

	private static void tlUnregister(ContentObserver observer) {
		if (observer == null)
			throw new IllegalArgumentException("You must pass a valid observer");
		synchronized (tlObservers) {
			java.util.Iterator<TlObserverEntry> it = tlObservers.iterator();
			while (it.hasNext()) {
				if (it.next().observer == observer)
					it.remove();
			}
		}
	}

	private static void tlNotify(java.util.Collection<Uri> uris, ContentObserver source, int flags, int userHandle) {
		// ContentService.notifyChange: collect per observer, then dispatch each its URIs once
		java.util.LinkedHashMap<ContentObserver, java.util.ArrayList<Uri>> targets = new java.util.LinkedHashMap<>();
		java.util.HashMap<ContentObserver, Boolean> selfChange = new java.util.HashMap<>();
		synchronized (tlObservers) {
			for (Uri uri : uris) {
				java.util.List<String> us = tlUriSegments(uri);
				for (TlObserverEntry e : tlObservers) {
					if (e.userHandle != android.os.UserHandle.USER_ALL && userHandle != android.os.UserHandle.USER_ALL
					    && e.userHandle != userHandle && e.userHandle != android.os.UserHandle.USER_CURRENT
					    && userHandle != android.os.UserHandle.USER_CURRENT)
						continue;
					boolean self = e.observer == source;
					if (self && !source.deliverSelfNotifications())
						continue;
					java.util.List<String> rs = e.segments;
					boolean leaf;
					if (rs.size() <= us.size() && us.subList(0, rs.size()).equals(rs)) {
						leaf = rs.size() == us.size();
					} else if (rs.size() > us.size() && rs.subList(0, us.size()).equals(us)) {
						leaf = true; // registered below the changed URI: every observer there hears it
					} else {
						continue;
					}
					if (leaf) {
						if ((flags & NOTIFY_SKIP_NOTIFY_FOR_DESCENDANTS) != 0 && e.notifyForDescendants)
							continue;
					} else if (!e.notifyForDescendants) {
						continue;
					}
					java.util.ArrayList<Uri> list = targets.get(e.observer);
					if (list == null) {
						list = new java.util.ArrayList<>();
						targets.put(e.observer, list);
					}
					if (!list.contains(uri))
						list.add(uri);
					selfChange.put(e.observer, self);
				}
			}
		}
		for (java.util.Map.Entry<ContentObserver, java.util.ArrayList<Uri>> t : targets.entrySet()) {
			t.getKey().dispatchChange(selfChange.get(t.getKey()), t.getValue(), flags);
		}
	}

	/** AOSP: notify with a sync-to-network request (no sync adapters in ATL: only observers). */
	public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork) {
		notifyChange(uri, observer, syncToNetwork ? NOTIFY_SYNC_TO_NETWORK : 0);
	}

	/** AOSP: notify registered observers that a row was updated. */
	public void notifyChange(Uri uri, ContentObserver observer, int flags) {
		if (uri == null)
			throw new NullPointerException("uri");
		tlNotify(java.util.Collections.singletonList(uri), observer, flags, getUserId());
	}

	/** AOSP: notify registered observers that several rows changed. */
	public void notifyChange(java.util.Collection<Uri> uris, ContentObserver observer, int flags) {
		if (uris == null)
			throw new NullPointerException("uris");
		tlNotify(uris, observer, flags, getUserId());
	}

	/** AOSP: calls a provider-defined method of the provider serving uri's authority. */
	public final android.os.Bundle call(Uri uri, String method, String arg, android.os.Bundle extras) {
		if (uri == null)
			throw new NullPointerException("uri");
		return call(uri.getAuthority(), method, arg, extras);
	}

	/** AOSP: calls a provider-defined method; no provider for the authority is an IllegalArgumentException. */
	public final android.os.Bundle call(String authority, String method, String arg, android.os.Bundle extras) {
		if (authority == null)
			throw new NullPointerException("authority");
		if (method == null)
			throw new NullPointerException("method");
		ContentProvider provider = ContentProvider.atl_get_content_provider(authority);
		if (provider == null)
			throw new IllegalArgumentException("Unknown authority " + authority);
		return provider.call(authority, method, arg, extras);
	}

	/** AOSP: inserts several rows through the provider (0 when there is no provider). */
	public final int bulkInsert(Uri url, ContentValues[] values) {
		ContentProvider provider = ContentProvider.atl_get_content_provider(url.getAuthority());
		if (provider == null)
			throw new IllegalArgumentException("Unknown URL " + url);
		return provider.bulkInsert(url, values);
	}

	/** AOSP: the MIME types the provider can stream for the URI matching the filter, or null. */
	public String[] getStreamTypes(Uri url, String mimeTypeFilter) {
		ContentProvider provider = ContentProvider.atl_get_content_provider(url.getAuthority());
		return provider != null ? provider.getStreamTypes(url, mimeTypeFilter) : null;
	}

	/** AOSP: the provider's canonical form of a URI, or null. */
	public final Uri canonicalize(Uri url) {
		ContentProvider provider = ContentProvider.atl_get_content_provider(url.getAuthority());
		return provider != null ? provider.canonicalize(url) : null;
	}

	/** AOSP: the inverse of canonicalize, or null. */
	public final Uri uncanonicalize(Uri url) {
		ContentProvider provider = ContentProvider.atl_get_content_provider(url.getAuthority());
		return provider != null ? provider.uncanonicalize(url) : null;
	}
}
