package android.atl.carhost;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * The car connection provider a Car App Library host publishes (authority
 * "androidx.car.app.connection"): androidx.car.app.connection.CarConnection queries it for one row
 * with the column "CarConnectionState" and queries again on the broadcast
 * "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED".
 *
 * The state is the one of ATL's car host ({@link ATLCarHost}): CONNECTION_TYPE_PROJECTION (2)
 * while it drives a car screen, CONNECTION_TYPE_NOT_CONNECTED (0) otherwise. Projection, not
 * native: the app runs on a separate device (this one, not an Android Automotive head unit) and the
 * host shows its templates on the car's screen, which is what a projection host does. The
 * provider is only published when the car host is enabled; without it the runtime is a device
 * without a car host, where the query has no provider, as on a phone without one.
 */
public final class ATLCarConnectionProvider extends ContentProvider {
	public static final String AUTHORITY = "androidx.car.app.connection";
	public static final String CAR_CONNECTION_STATE = "CarConnectionState";
	public static final String ACTION_CAR_CONNECTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED";
	public static final int CONNECTION_TYPE_NOT_CONNECTED = 0;
	public static final int CONNECTION_TYPE_PROJECTION = 2;

	@Override
	public boolean onCreate() {
		return true;
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		MatrixCursor cursor = new MatrixCursor(new String[] {CAR_CONNECTION_STATE}, 1);
		cursor.addRow(new Object[] {ATLCarHost.getConnectionType()});
		return cursor;
	}

	@Override
	public String getType(Uri uri) {
		return null;
	}

	/* the connection state is the host's; clients cannot change it */
	@Override
	public Uri insert(Uri uri, ContentValues values) {
		throw new UnsupportedOperationException("the car connection state is read-only");
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		throw new UnsupportedOperationException("the car connection state is read-only");
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		throw new UnsupportedOperationException("the car connection state is read-only");
	}
}
