package android.app;

import android.atl.ATLLoadedApp;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.GestureDetector;

public class PendingIntent implements Parcelable {

	private int requestCode;
	Intent intent;
	int type; // 0: activity, 1: service, 2: broadcast

	private PendingIntent(int requestCode, Intent intent, int type) {
		this.requestCode = requestCode;
		this.intent = intent;
		this.type = type;
	}
	public static PendingIntent getBroadcast(Context context, int requestCode, Intent intent, int flags) {
		return tlObtain(new PendingIntent(requestCode, new Intent(intent), 2), flags, null);
	}

	/**
	 * AOSP: performs the operation with the fill-in intent merged in (Intent.fillIn); a canceled
	 * PendingIntent throws CanceledException, a FLAG_ONE_SHOT one is canceled by its first send.
	 * (AOSP declares the CanceledException; ATL's signature never did and framework callers rely on
	 * that, so it is thrown undeclared: the same exception reaches the app's catch.)
	 */
	public void send(Context context, int code, Intent fillIn) {
		Intent target = tlFillIn(fillIn, 0, 0);
		if (target == null)
			PendingIntent.<RuntimeException>tlThrow(new CanceledException());
		if (tlFlags >= 0 && (tlFlags & FLAG_ONE_SHOT) != 0)
			cancel();
		Context ctx = ATLLoadedApp.getPrimaryApplication().getApplication();
		if (type == 0) {
			if (tlIntents != null && tlIntents.length > 1) {
				for (int i = 0; i < tlIntents.length - 1; i++)
					ctx.startActivity(tlIntents[i]);
			}
			ctx.startActivity(target);
		} else if (type == 1) {
			ctx.startService(target);
		} else if (type == 2) {
			ctx.sendBroadcast(target);
		}
	}

	public void send() {
		send(null, 0, null);
	}

	@SuppressWarnings("unchecked")
	private static <E extends Throwable> void tlThrow(Throwable e) throws E {
		throw (E) e;
	}

	public static PendingIntent getActivity(Context context, int requestCode, Intent intent, int flags) {
		return tlObtain(new PendingIntent(requestCode, new Intent(intent), 0), flags, null);
	}

	public static PendingIntent getService(Context context, int requestCode, Intent intent, int flags) {
		return tlObtain(new PendingIntent(requestCode, new Intent(intent), 1), flags, null);
	}

	/** AOSP: the intents are started in order when sent (the last one is on top); the PendingIntent is identified by the last. */
	public static PendingIntent getActivities(Context context, int requestCode, Intent[] intents, int flags, Bundle options) {
		Intent[] copies = new Intent[intents.length];
		for (int i = 0; i < intents.length; i++)
			copies[i] = new Intent(intents[i]);
		return tlObtain(new PendingIntent(requestCode, copies[copies.length - 1], 0), flags, copies);
	}

	public String toString() {
		return "PendingIntent [requestCode=" + requestCode + ", intent=" + intent + ", type="
		     + new String[] {"activity", "service", "broadcast"}[type] + "]";
	}

	/** AOSP: thrown when sending a PendingIntent that was canceled. */
	public static class CanceledException extends android.util.AndroidException {
		public CanceledException() {
		}

		public CanceledException(String name) {
			super(name);
		}

		public CanceledException(Exception cause) {
			super(cause);
		}
	}

	public String getCreatorPackage() {
		return ATLLoadedApp.getPrimaryApplication().pkg.packageName;
	}

	public int getCreatorUid() {
		return ATLLoadedApp.getPrimaryApplication().pkg.applicationInfo.uid;
	}

	public static PendingIntent getForegroundService(Context context, int requestCode, Intent intent, int flags) {
		PendingIntent pi = getService(context, requestCode, intent, flags);
		if (pi != null)
			pi.tlForegroundService = true;
		return pi;
	}

	/** Callback for the result of a sent PendingIntent (AOSP). */
	public interface OnFinished {
		void onSendFinished(PendingIntent pendingIntent, Intent intent, int resultCode, String resultData, Bundle resultExtras);
	}

	public void send(int code) throws CanceledException {
		send(null, code, null, null, null, null, null);
	}

	public void send(Bundle options) throws CanceledException {
		send(null, 0, null, null, null, null, options);
	}

	public void send(int code, OnFinished onFinished, android.os.Handler handler) throws CanceledException {
		send(null, code, null, onFinished, handler, null, null);
	}

	public void send(Context context, int code, Intent intent, OnFinished onFinished, android.os.Handler handler) throws CanceledException {
		send(context, code, intent, onFinished, handler, null, null);
	}

	public void send(Context context, int code, Intent intent, OnFinished onFinished, android.os.Handler handler, String requiredPermission) throws CanceledException {
		send(context, code, intent, onFinished, handler, requiredPermission, null);
	}

	/** The full send: performs the intent, then reports completion to onFinished on the handler. */
	public void send(final Context context, final int code, final Intent intent, final OnFinished onFinished, android.os.Handler handler, String requiredPermission, Bundle options) throws CanceledException {
		send(context, code, intent);
		if (onFinished != null) {
			Runnable done = new Runnable() {
				@Override
				public void run() {
					onFinished.onSendFinished(PendingIntent.this, intent, code, null, null);
				}
			};
			if (handler != null)
				handler.post(done);
			else
				done.run();
		}
	}

	/* ==== teslalinux (app): AOSP PendingIntent behaviour ==== */
	public static final int FLAG_ONE_SHOT = 1 << 30;
	public static final int FLAG_NO_CREATE = 1 << 29;
	public static final int FLAG_CANCEL_CURRENT = 1 << 28;
	public static final int FLAG_UPDATE_CURRENT = 1 << 27;
	public static final int FLAG_IMMUTABLE = 1 << 26;
	public static final int FLAG_MUTABLE = 1 << 25;
	public static final int FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT = 1 << 24;

	/* Intent.fillIn flags (AOSP Intent.FILL_IN_*) */
	private static final int FILL_IN_ACTION = 1 << 0;
	private static final int FILL_IN_DATA = 1 << 1;
	private static final int FILL_IN_CATEGORIES = 1 << 2;
	private static final int FILL_IN_COMPONENT = 1 << 3;
	private static final int FILL_IN_PACKAGE = 1 << 4;

	private boolean tlCanceled;
	private int tlFlags = -1;
	private Intent[] tlIntents;
	private TlIntentSender tlSender;

	/** AOSP: the IntentSender of this PendingIntent (the same target; sending it sends this). */
	public IntentSender getIntentSender() {
		synchronized (this) {
			if (tlSender == null)
				tlSender = new TlIntentSender(this);
			return tlSender;
		}
	}

	/** The IntentSender of a PendingIntent (AOSP wraps the same IIntentSender in both). */
	public static final class TlIntentSender extends IntentSender {
		final PendingIntent mPendingIntent;

		TlIntentSender(PendingIntent pendingIntent) {
			mPendingIntent = pendingIntent;
		}

		public String getCreatorPackage() {
			return mPendingIntent.getCreatorPackage();
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof TlIntentSender && ((TlIntentSender) o).mPendingIntent.equals(mPendingIntent);
		}

		@Override
		public int hashCode() {
			return mPendingIntent.hashCode();
		}

		@Override
		public String toString() {
			return "IntentSender{" + Integer.toHexString(System.identityHashCode(this)) + ": " + mPendingIntent + "}";
		}
	}

	/**
	 * The intent this PendingIntent sends with a fill-in intent, after AOSP Intent.fillIn: the fill-in
	 * gives the fields the original leaves empty (component and package only when flagsMask/flagsValues
	 * ask for it), its extras are merged in, and FLAG_IMMUTABLE intents take nothing from it.
	 * null when the PendingIntent was canceled.
	 */
	Intent tlFillIn(Intent fillIn, int flagsMask, int flagsValues) {
		if (tlCanceled)
			return null;
		Intent target = new Intent(intent);
		if (fillIn != null && (tlFlags < 0 || (tlFlags & FLAG_IMMUTABLE) == 0)) {
			if (fillIn.getAction() != null && target.getAction() == null)
				target.setAction(fillIn.getAction());
			if ((fillIn.getData() != null || fillIn.getType() != null) && target.getData() == null && target.getType() == null)
				target.setDataAndType(fillIn.getData(), fillIn.getType());
			if (fillIn.getCategories() != null && target.getCategories() == null) {
				for (String c : fillIn.getCategories())
					target.addCategory(c);
			}
			if (fillIn.getComponent() != null && (flagsValues & FILL_IN_COMPONENT) != 0 && target.getComponent() == null)
				target.setComponent(fillIn.getComponent());
			if (fillIn.getPackage() != null && (flagsValues & FILL_IN_PACKAGE) != 0 && target.getPackage() == null && target.getComponent() == null)
				target.setPackage(fillIn.getPackage());
			if (fillIn.getExtras() != null)
				target.putExtras(fillIn.getExtras());
			target.addFlags(fillIn.getFlags());
		}
		if (flagsMask != 0)
			target.setFlags((target.getFlags() & ~flagsMask) | (flagsValues & flagsMask));
		return target;
	}

	/** AOSP: the package the intent goes to (the creator's, here the app's). */
	@Deprecated
	public String getTargetPackage() {
		return getCreatorPackage();
	}

	public android.os.UserHandle getCreatorUserHandle() {
		return new android.os.UserHandle(getCreatorUid() / android.os.UserHandle.PER_USER_RANGE);
	}

	public boolean isActivity() {
		return type == 0;
	}

	public boolean isService() {
		return type == 1;
	}

	public boolean isBroadcast() {
		return type == 2;
	}

	public boolean isForegroundService() {
		return type == 1 && tlForegroundService;
	}

	private boolean tlForegroundService;

	public boolean isImmutable() {
		return tlFlags >= 0 && (tlFlags & FLAG_IMMUTABLE) != 0;
	}

	/** AOSP: activities started from all the intents, the last one on top. */
	public static PendingIntent getActivities(Context context, int requestCode, Intent[] intents, int flags) {
		return getActivities(context, requestCode, intents, flags, null);
	}

	public static PendingIntent getActivity(Context context, int requestCode, Intent intent, int flags, Bundle options) {
		return getActivity(context, requestCode, intent, flags);
	}

	/** PendingIntent creation with AOSP's flag semantics: an equal PendingIntent (same kind, request code and filterEquals intent) is reused. */
	static PendingIntent tlObtain(PendingIntent created, int flags, Intent[] intents) {
		synchronized (tlActive) {
			PendingIntent existing = null;
			for (PendingIntent p : tlActive) {
				if (p.type == created.type && p.requestCode == created.requestCode && p.intent.filterEquals(created.intent)) {
					existing = p;
					break;
				}
			}
			if ((flags & FLAG_NO_CREATE) != 0)
				return existing;
			if (existing != null && (flags & FLAG_CANCEL_CURRENT) != 0) {
				existing.cancel();
				existing = null;
			}
			if (existing != null) {
				if ((flags & FLAG_UPDATE_CURRENT) != 0 && created.intent.getExtras() != null) {
					existing.intent.replaceExtras(created.intent.getExtras());
					if (intents != null)
						existing.tlIntents = intents;
				}
				return existing;
			}
			created.tlFlags = flags;
			created.tlIntents = intents;
			tlActive.add(created);
			return created;
		}
	}

	private static final java.util.ArrayList<PendingIntent> tlActive = new java.util.ArrayList<>();

	/** AOSP: the PendingIntent stops working (send throws CanceledException) and a later get* creates a new one. */
	public void cancel() {
		tlCanceled = true;
		synchronized (tlActive) {
			tlActive.remove(this);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof PendingIntent))
			return false;
		PendingIntent other = (PendingIntent) o;
		return other.type == type && other.requestCode == requestCode && other.intent.filterEquals(intent);
	}

	@Override
	public int hashCode() {
		return (type * 31 + requestCode) * 31 + intent.filterHashCode();
	}
}
