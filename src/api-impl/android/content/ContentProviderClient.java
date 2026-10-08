package android.content;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.RemoteException;

/* teslalinux: a client for a provider that is never found; every call behaves like an absent provider. */
public class ContentProviderClient implements AutoCloseable {
	private final ContentResolver resolver;
	public ContentProviderClient(ContentResolver resolver) { this.resolver = resolver; }
	public Cursor query(Uri url, String[] projection, String selection, String[] selectionArgs, String sortOrder) throws RemoteException { return resolver.query(url, projection, selection, selectionArgs, sortOrder); }
	public Cursor query(Uri url, String[] projection, String selection, String[] selectionArgs, String sortOrder, CancellationSignal signal) throws RemoteException { return resolver.query(url, projection, selection, selectionArgs, sortOrder); }
	public Cursor query(Uri uri, String[] projection, Bundle queryArgs, CancellationSignal signal) throws RemoteException { return resolver.query(uri, projection, queryArgs, signal); }
	public String getType(Uri url) throws RemoteException { return resolver.getType(url); }
	public Uri insert(Uri url, ContentValues initialValues) throws RemoteException { return resolver.insert(url, initialValues); }
	public int delete(Uri url, String selection, String[] selectionArgs) throws RemoteException { return 0; }
	public int update(Uri url, ContentValues values, String selection, String[] selectionArgs) throws RemoteException { return 0; }
	public Bundle call(String method, String arg, Bundle extras) throws RemoteException { return null; }
	public Bundle call(String authority, String method, String arg, Bundle extras) throws RemoteException { return null; }
	public ContentProvider getLocalContentProvider() { return null; }
	public boolean release() { return true; }
	@Override public void close() {}
}
