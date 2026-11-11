package android.atl;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Slog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;

/**
 * The service lifecycle of AOSP's ActivityManagerService (ActiveServices) and the client side of
 * LoadedApk.ServiceDispatcher, for the services of the apps running in this process.
 *
 * <ul>
 * <li>A service is created when it is started or bound with {@link Context#BIND_AUTO_CREATE} and
 *     destroyed once it is neither started (startService without a matching stopService/stopSelf)
 *     nor bound with BIND_AUTO_CREATE.</li>
 * <li>Every start gets the next start id; stopSelf(startId) only stops on the latest one.</li>
 * <li>onBind runs once per {@link Intent.FilterComparison}; later binds of an equal intent get the
 *     same binder. When the last connection of an intent unbinds, onUnbind runs; if it returned true,
 *     the next bind of that intent calls onRebind.</li>
 * <li>A binding without BIND_AUTO_CREATE waits for the service to be created by someone else.</li>
 * <li>When a service is brought down while connections without BIND_AUTO_CREATE remain, they get
 *     onServiceDisconnected (if connected) and onBindingDied.</li>
 * </ul>
 *
 * As on Android, the service's callbacks and the connections' callbacks run on the main thread,
 * after the call that caused them has returned. Start ids, the started state and the registry of
 * connections change synchronously (the system server's bookkeeping); stopSelfResult can be called
 * from any thread.
 */
public final class ATLActiveServices {
	private static final String TAG = "ATLActiveServices";

	private static final Object sLock = new Object();
	/* guarded by sLock */
	private static final HashMap<ComponentName, ServiceRecord> sServices = new HashMap<>();
	/* guarded by sLock: the service instance a stopSelf call comes from */
	private static final IdentityHashMap<Service, ServiceRecord> sByService = new IdentityHashMap<>();
	/* guarded by sLock: LoadedApk's dispatchers, one list of connection records per ServiceConnection */
	private static final IdentityHashMap<ServiceConnection, ArrayList<ConnectionRecord>> sConnections = new IdentityHashMap<>();

	private static Handler sMain;

	private ATLActiveServices() {}

	/** ActiveServices.ServiceRecord */
	private static final class ServiceRecord {
		final ATLLoadedApp app;
		final ComponentName name;
		/* main thread */
		Service service;
		boolean destroyed;
		final LinkedHashMap<Intent.FilterComparison, IntentBindRecord> bindings = new LinkedHashMap<>();
		/* sLock */
		boolean startRequested;
		int lastStartId;

		ServiceRecord(ATLLoadedApp app, ComponentName name) {
			this.app = app;
			this.name = name;
		}
	}

	/** ActiveServices.IntentBindRecord: one binder per distinct intent */
	private static final class IntentBindRecord {
		final Intent intent;
		final ArrayList<ConnectionRecord> connections = new ArrayList<>();
		IBinder binder;
		boolean requested; /* onBind was asked for */
		boolean received;  /* onBind returned (binder may be null) */
		boolean hasBound;  /* onBind/onRebind ran and onUnbind did not yet */
		boolean doRebind;  /* onUnbind returned true: the next bind calls onRebind */

		IntentBindRecord(Intent intent) {
			this.intent = intent;
		}
	}

	/** ActiveServices.ConnectionRecord plus the client's ServiceDispatcher state */
	private static final class ConnectionRecord {
		final ServiceConnection conn;
		final int flags;
		final Intent intent;
		/* the party (uid, see Binder) of the client: its ServiceConnection runs for it */
		final int clientUid = android.os.Binder.getThreadActingUid();
		ServiceRecord service;      /* main thread */
		IntentBindRecord binding;   /* main thread */
		volatile boolean forgotten; /* unbindService was called: no more callbacks */
		boolean serviceDead;        /* the service was brought down under this connection */
		boolean delivered;
		IBinder deliveredBinder;

		ConnectionRecord(ServiceRecord service, ServiceConnection conn, int flags, Intent intent) {
			this.service = service;
			this.conn = conn;
			this.flags = flags;
			this.intent = intent;
		}
	}

	private static void post(Runnable r) {
		synchronized (sLock) {
			if (sMain == null)
				sMain = new Handler(Looper.getMainLooper());
		}
		sMain.post(r);
	}

	private static ServiceRecord retrieveLocked(ATLLoadedApp app, ComponentName name) {
		ServiceRecord r = sServices.get(name);
		if (r == null) {
			r = new ServiceRecord(app, name);
			sServices.put(name, r);
		}
		return r;
	}

	/** Context.startService: the resolved component, or null when the app has no such service. */
	public static ComponentName startService(ATLLoadedApp app, final Intent intent) {
		final ComponentName name = app.resolveService(intent);
		if (name == null) {
			Slog.w(TAG, "startService: no matching service found for intent: " + intent);
			return null;
		}
		final ServiceRecord r;
		final int startId;
		synchronized (sLock) {
			r = retrieveLocked(app, name);
			r.startRequested = true;
			startId = ++r.lastStartId;
		}
		post(new Runnable() {
			@Override
			public void run() {
				ServiceRecord live = r;
				if (live.destroyed) {
					/* stopped and destroyed between the call and now: AOSP would have queued the start
					 * on the record that the stop brought down and recreated it */
					synchronized (sLock) {
						live = retrieveLocked(r.app, r.name);
						live.startRequested = true;
					}
				}
				if (!bringUp(live))
					return;
				/* the START_* result only matters when the system kills the process, which never
				 * happens here: the process is the app */
				int prev = enterService(live);
				try {
					live.service.onStartCommand(intent, 0, startId);
				} finally {
					android.os.Binder.tlLeaveParty(prev);
				}
			}
		});
		return name;
	}

	/** Context.bindService: false when the app has no such service. */
	public static boolean bindService(ATLLoadedApp app, Intent intent, ServiceConnection conn, int flags) {
		if (conn == null)
			throw new IllegalArgumentException("connection is null");
		ComponentName name = app.resolveService(intent);
		if (name == null) {
			Slog.w(TAG, "bindService: no matching service found for intent: " + intent);
			return false;
		}
		final ConnectionRecord c;
		synchronized (sLock) {
			ServiceRecord r = retrieveLocked(app, name);
			c = new ConnectionRecord(r, conn, flags, new Intent(intent));
			ArrayList<ConnectionRecord> list = sConnections.get(conn);
			if (list == null) {
				list = new ArrayList<>();
				sConnections.put(conn, list);
			}
			list.add(c);
		}
		post(new Runnable() {
			@Override
			public void run() {
				attachConnection(c);
			}
		});
		return true;
	}

	/**
	 * A connection the framework delivered itself, to a service that is not in this process (the GCM
	 * Messenger of ContextImpl): it is registered like LoadedApk registers every dispatcher, so that
	 * unbindService accepts it, and there is nothing to unbind here.
	 */
	public static void registerExternalConnection(ServiceConnection conn) {
		synchronized (sLock) {
			if (!sConnections.containsKey(conn))
				sConnections.put(conn, new ArrayList<ConnectionRecord>());
		}
	}

	/** Context.unbindService */
	public static void unbindService(ServiceConnection conn) {
		final ArrayList<ConnectionRecord> list;
		synchronized (sLock) {
			list = sConnections.remove(conn);
		}
		if (list == null)
			throw new IllegalArgumentException("Service not registered: " + conn);
		for (ConnectionRecord c : list)
			c.forgotten = true; /* LoadedApk.ServiceDispatcher.doForget: nothing is delivered from now on */
		post(new Runnable() {
			@Override
			public void run() {
				for (ConnectionRecord c : list)
					removeConnection(c);
			}
		});
	}

	/** Context.stopService: whether the service existed (started or bound). */
	public static boolean stopService(ATLLoadedApp app, Intent intent) {
		ComponentName name = app.resolveService(intent);
		if (name == null)
			return false;
		final ServiceRecord r;
		synchronized (sLock) {
			r = sServices.get(name);
			if (r == null)
				return false;
			r.startRequested = false;
		}
		post(new Runnable() {
			@Override
			public void run() {
				bringDownIfNeeded(r);
			}
		});
		return true;
	}

	/**
	 * Service.stopSelfResult (ActiveServices.stopServiceTokenLocked): with startId >= 0, only when it
	 * is the most recent start; a service still bound with BIND_AUTO_CREATE keeps running.
	 */
	public static boolean stopSelf(Service service, int startId) {
		final ServiceRecord r;
		synchronized (sLock) {
			r = sByService.get(service);
			if (r == null)
				return false;
			if (startId >= 0 && r.lastStartId != startId)
				return false;
			r.startRequested = false;
		}
		post(new Runnable() {
			@Override
			public void run() {
				bringDownIfNeeded(r);
			}
		});
		return true;
	}

	/* ---- main thread ---- */

	private static void attachConnection(ConnectionRecord c) {
		if (c.forgotten)
			return;
		ServiceRecord r = c.service;
		if (r.destroyed) {
			synchronized (sLock) {
				r = retrieveLocked(r.app, r.name);
			}
			c.service = r;
		}
		Intent.FilterComparison key = new Intent.FilterComparison(c.intent);
		IntentBindRecord b = r.bindings.get(key);
		if (b == null) {
			b = new IntentBindRecord(c.intent);
			r.bindings.put(key, b);
		}
		c.binding = b;
		b.connections.add(c);
		if ((c.flags & Context.BIND_AUTO_CREATE) != 0) {
			if (!bringUp(r))
				return;
		}
		if (r.service == null)
			return; /* without BIND_AUTO_CREATE: connected once someone creates the service */
		if (b.received) {
			deliver(c, r.name, b.binder);
			if (b.connections.size() == 1 && b.doRebind)
				requestBinding(r, b, true);
		} else if (!b.requested) {
			requestBinding(r, b, false);
		}
	}

	private static boolean bringUp(ServiceRecord r) {
		if (r.service != null)
			return true;
		Service service;
		int prev = enterService(r);
		try {
			service = r.app.instantiateService(r.name.getClassName());
		} catch (ReflectiveOperationException e) {
			Slog.e(TAG, "failed to create service " + r.name, e);
			synchronized (sLock) {
				if (sServices.get(r.name) == r)
					sServices.remove(r.name);
			}
			r.destroyed = true;
			android.os.Binder.tlLeaveParty(prev);
			return false;
		}
		r.service = service;
		synchronized (sLock) {
			sByService.put(service, r);
		}
		try {
			service.onCreate();
		} finally {
			android.os.Binder.tlLeaveParty(prev);
		}
		/* ActiveServices.requestServiceBindingsLocked: the bindings that waited for the service */
		for (IntentBindRecord b : new ArrayList<>(r.bindings.values())) {
			if (!b.connections.isEmpty() && !b.requested && r.service != null)
				requestBinding(r, b, false);
		}
		return r.service != null;
	}

	private static void requestBinding(ServiceRecord r, IntentBindRecord b, boolean rebind) {
		if (!rebind)
			b.requested = true;
		b.hasBound = true;
		b.doRebind = false;
		IBinder binder;
		int prev = enterService(r);
		try {
			if (rebind) {
				r.service.onRebind(b.intent);
				return;
			}
			binder = r.service.onBind(b.intent);
		} finally {
			android.os.Binder.tlLeaveParty(prev);
		}
		/* ActiveServices.publishServiceLocked */
		b.binder = binder;
		b.received = true;
		for (ConnectionRecord c : new ArrayList<>(b.connections))
			deliver(c, r.name, binder);
	}

	/** LoadedApk.ServiceDispatcher.doConnected */
	private static void deliver(ConnectionRecord c, ComponentName name, IBinder binder) {
		if (c.forgotten || c.serviceDead)
			return;
		if (c.delivered && c.deliveredBinder == binder)
			return;
		boolean hadOld = c.delivered && c.deliveredBinder != null;
		c.delivered = true;
		c.deliveredBinder = binder;
		int prev = android.os.Binder.tlEnterParty(c.clientUid);
		try {
			if (hadOld)
				c.conn.onServiceDisconnected(name);
			if (binder != null)
				c.conn.onServiceConnected(name, binder);
			else
				c.conn.onNullBinding(name);
		} finally {
			android.os.Binder.tlLeaveParty(prev);
		}
	}

	/** ActiveServices.removeConnectionLocked + unbindFinishedLocked */
	private static void removeConnection(ConnectionRecord c) {
		ServiceRecord r = c.service;
		IntentBindRecord b = c.binding;
		if (b != null && b.connections.remove(c)) {
			if (b.connections.isEmpty() && b.hasBound && r.service != null && !r.destroyed) {
				b.hasBound = false;
				b.doRebind = false;
				int prev = enterService(r);
				try {
					if (r.service.onUnbind(b.intent))
						b.doRebind = true;
				} finally {
					android.os.Binder.tlLeaveParty(prev);
				}
			}
		}
		if ((c.flags & Context.BIND_AUTO_CREATE) != 0 && !r.destroyed)
			bringDownIfNeeded(r);
	}

	/** ActiveServices.bringDownServiceIfNeededLocked */
	private static void bringDownIfNeeded(ServiceRecord r) {
		if (r.destroyed)
			return;
		synchronized (sLock) {
			if (r.startRequested)
				return;
		}
		for (IntentBindRecord b : r.bindings.values()) {
			for (ConnectionRecord c : b.connections) {
				if ((c.flags & Context.BIND_AUTO_CREATE) != 0)
					return;
			}
		}
		bringDown(r);
	}

	/** ActiveServices.bringDownServiceLocked */
	private static void bringDown(ServiceRecord r) {
		/* the connections still there (without BIND_AUTO_CREATE) learn that the service is gone:
		 * ServiceDispatcher.doConnected(name, null, dead = true) */
		for (IntentBindRecord b : r.bindings.values()) {
			for (ConnectionRecord c : new ArrayList<>(b.connections)) {
				if (c.forgotten || c.serviceDead)
					continue;
				boolean wasConnected = c.delivered && c.deliveredBinder != null;
				c.serviceDead = true;
				int prev = android.os.Binder.tlEnterParty(c.clientUid);
				try {
					if (wasConnected)
						c.conn.onServiceDisconnected(r.name);
					c.conn.onBindingDied(r.name);
				} finally {
					android.os.Binder.tlLeaveParty(prev);
				}
			}
		}
		Service service = r.service;
		int prev = enterService(r);
		try {
		if (service != null) {
			for (IntentBindRecord b : r.bindings.values()) {
				if (b.hasBound) {
					b.hasBound = false;
					b.requested = false;
					service.onUnbind(b.intent);
				}
			}
		}
		r.destroyed = true;
		r.service = null;
		r.bindings.clear();
		synchronized (sLock) {
			if (sServices.get(r.name) == r)
				sServices.remove(r.name);
			if (service != null)
				sByService.remove(service);
		}
		if (service != null)
			service.onDestroy();
		} finally {
			android.os.Binder.tlLeaveParty(prev);
		}
	}

	/* a service's callbacks run for its package, as in that package's process (see Binder) */
	private static int enterService(ServiceRecord r) {
		return android.os.Binder.tlEnterParty(r.app.pkg.applicationInfo.uid);
	}
}
