package android.atl.car;

import android.atl.ATLLoadedApp;
import android.car.Car;
import android.car.CarAppFocusManager;
import android.car.IAppFocus;
import android.car.IAppFocusListener;
import android.car.IAppFocusOwnershipCallback;
import android.car.ICar;
import android.car.cluster.renderer.IInstrumentClusterNavigation;
import android.car.drivingstate.CarDrivingStateEvent;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsConfiguration;
import android.car.drivingstate.ICarDrivingState;
import android.car.drivingstate.ICarDrivingStateChangeListener;
import android.car.drivingstate.ICarUxRestrictionsChangeListener;
import android.car.drivingstate.ICarUxRestrictionsManager;
import android.car.navigation.CarNavigationInstrumentCluster;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.view.Display;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The car service of this runtime (AOSP packages/services/Car, ICarImpl), running in-process for
 * the platform (system uid): what an automotive device's car service offers apps without a vehicle
 * HAL. It exists only on a device that reports android.hardware.type.automotive, as on AOSP
 * (ServiceManager "car_service").
 *
 * <ul>
 * <li>Driving state (CarDrivingStateService): from the vehicle speed, here the speed of ATL's
 *     location feed (the car's GPS): moving above zero, idling at zero. The vehicle has no gear or
 *     parking brake signal here, so it is never known to be parked (AOSP infers PARKED only from
 *     those).</li>
 * <li>UX restrictions (CarUxRestrictionsManagerService): the AOSP default restrictions map
 *     (service/res/xml/car_ux_restrictions_map.xml) applied to the driving state and speed, on the
 *     default display; listeners hear every change.</li>
 * <li>App focus (AppFocusService): one owner per focus type, the previous owner loses it,
 *     listeners hear focus changes.</li>
 * <li>Navigation state for the instrument cluster (ClusterNavigationService): the caller must
 *     hold CAR_NAVIGATION_MANAGER and own navigation focus; there is no instrument cluster, so the
 *     state reaches no renderer and there is no cluster info (null).</li>
 * </ul>
 * Other car services (vehicle properties, audio zones, cluster home, users, ...) do not exist on
 * this device: getCarService returns no binder for them, and Car.getCarManager gives null.
 */
public final class ATLCarService extends ICar.Stub {
	private static final String TAG = "ATLCarService";
	private static ATLCarService sInstance;

	private final Context mContext;
	private final Handler mHandler = new Handler(Looper.getMainLooper());
	private final DrivingStateService mDrivingState;
	private final UxRestrictionsService mUxRestrictions;
	private final AppFocusService mAppFocus;
	private final ClusterNavigationService mNavigation;

	/** The car service, or null on a device that is not automotive (AOSP: no car_service). */
	public static synchronized IBinder getServiceBinder() {
		if (sInstance == null) {
			Context context = new android.app.ContextImpl(android.content.res.Resources.getSystem(), ATLLoadedApp.getSystemApplication(), 0);
			if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
				return null;
			/* the car service's binders belong to the platform */
			int previous = Binder.tlEnterParty(Process.SYSTEM_UID);
			try {
				sInstance = new ATLCarService(context);
			} finally {
				Binder.tlLeaveParty(previous);
			}
		}
		return sInstance;
	}

	private ATLCarService(Context context) {
		mContext = context;
		mDrivingState = new DrivingStateService();
		mUxRestrictions = new UxRestrictionsService();
		mAppFocus = new AppFocusService();
		mNavigation = new ClusterNavigationService();
		mHandler.post(mDrivingState::start);
	}

	private void enforcePermission(String permission) {
		if (Binder.getCallingUid() == Process.SYSTEM_UID)
			return;
		if (mContext.checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
			throw new SecurityException("requires " + permission);
	}

	/* ---- ICar ---- */

	@Override
	public void setSystemServerConnections(IBinder helper, IBinder receiver) {
		/* AOSP: CarServiceHelperService connects here; the platform is in this process */
		enforcePermission("android.permission.MANAGE_USERS");
	}

	@Override
	public IBinder getCarService(String serviceName) {
		if (serviceName == null)
			return null;
		switch (serviceName) {
			case Car.CAR_DRIVING_STATE_SERVICE:
				return mDrivingState;
			case Car.CAR_UX_RESTRICTION_SERVICE:
				return mUxRestrictions;
			case Car.APP_FOCUS_SERVICE:
				return mAppFocus;
			case Car.CAR_NAVIGATION_SERVICE:
				return mNavigation;
			default:
				Slog.w(TAG, "getCarService: no service " + serviceName + " on this car");
				return null;
		}
	}

	@Override
	public int getCarConnectionType() {
		return Car.CONNECTION_TYPE_EMBEDDED;
	}

	/* optional features (CarFeatureController): none of AOSP's optional features exist here */
	@Override
	public boolean isFeatureEnabled(String featureName) {
		return false;
	}

	@Override
	public int enableFeature(String featureName) {
		enforcePermission(Car.PERMISSION_CONTROL_CAR_FEATURES);
		return Car.FEATURE_REQUEST_NOT_EXISTING;
	}

	@Override
	public int disableFeature(String featureName) {
		enforcePermission(Car.PERMISSION_CONTROL_CAR_FEATURES);
		return Car.FEATURE_REQUEST_NOT_EXISTING;
	}

	@Override
	public List<String> getAllEnabledFeatures() {
		return new ArrayList<>();
	}

	@Override
	public List<String> getAllPendingDisabledFeatures() {
		return new ArrayList<>();
	}

	@Override
	public List<String> getAllPendingEnabledFeatures() {
		return new ArrayList<>();
	}

	@Override
	public String getCarManagerClassForFeature(String featureName) {
		return null;
	}

	/* ---- CarDrivingStateService ---- */

	private final class DrivingStateService extends ICarDrivingState.Stub implements LocationListener {
		private final ArrayList<ICarDrivingStateChangeListener> mListeners = new ArrayList<>();
		private CarDrivingStateEvent mCurrent = new CarDrivingStateEvent(CarDrivingStateEvent.DRIVING_STATE_UNKNOWN, SystemClock.elapsedRealtimeNanos());
		private float mSpeed = -1;

		void start() {
			LocationManager lm = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
			if (lm == null) {
				Slog.w(TAG, "no location service: the driving state stays unknown");
				return;
			}
			try {
				lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this, Looper.getMainLooper());
			} catch (RuntimeException e) {
				Slog.w(TAG, "no speed source: the driving state stays unknown", e);
			}
		}

		@Override
		public void onLocationChanged(Location location) {
			if (!location.hasSpeed())
				return;
			onSpeed(location.getSpeed());
		}

		@Override
		public void onStatusChanged(String provider, int status, Bundle extras) {}

		@Override
		public void onProviderEnabled(String provider) {}

		@Override
		public void onProviderDisabled(String provider) {}

		/* CarDrivingStateService.inferDrivingStateLocked without gear and parking brake */
		void onSpeed(float speed) {
			int state = speed > 0f ? CarDrivingStateEvent.DRIVING_STATE_MOVING : CarDrivingStateEvent.DRIVING_STATE_IDLING;
			boolean changed;
			synchronized (this) {
				mSpeed = speed;
				changed = state != mCurrent.eventValue;
				if (changed)
					mCurrent = new CarDrivingStateEvent(state, SystemClock.elapsedRealtimeNanos());
			}
			if (changed)
				dispatch(mCurrent);
			mUxRestrictions.update(state, speed);
		}

		synchronized float speed() {
			return mSpeed;
		}

		synchronized int state() {
			return mCurrent.eventValue;
		}

		private void dispatch(CarDrivingStateEvent event) {
			ArrayList<ICarDrivingStateChangeListener> listeners;
			synchronized (this) {
				listeners = new ArrayList<>(mListeners);
			}
			for (ICarDrivingStateChangeListener l : listeners) {
				try {
					l.onDrivingStateChanged(event);
				} catch (RemoteException e) {
					Slog.w(TAG, "driving state listener failed", e);
				}
			}
		}

		@Override
		public void registerDrivingStateChangeListener(ICarDrivingStateChangeListener listener) {
			enforcePermission(Car.PERMISSION_CAR_DRIVING_STATE);
			if (listener == null)
				throw new IllegalArgumentException("listener is null");
			synchronized (this) {
				for (ICarDrivingStateChangeListener l : mListeners) {
					if (l.asBinder() == listener.asBinder())
						return;
				}
				mListeners.add(listener);
			}
		}

		@Override
		public void unregisterDrivingStateChangeListener(ICarDrivingStateChangeListener listener) {
			enforcePermission(Car.PERMISSION_CAR_DRIVING_STATE);
			synchronized (this) {
				for (int i = mListeners.size() - 1; i >= 0; i--) {
					if (mListeners.get(i).asBinder() == listener.asBinder())
						mListeners.remove(i);
				}
			}
		}

		@Override
		public synchronized CarDrivingStateEvent getCurrentDrivingState() {
			enforcePermission(Car.PERMISSION_CAR_DRIVING_STATE);
			return mCurrent;
		}

		@Override
		public void injectDrivingState(CarDrivingStateEvent event) {
			enforcePermission(Car.PERMISSION_CONTROL_APP_BLOCKING);
			synchronized (this) {
				mCurrent = event;
			}
			dispatch(event);
			mUxRestrictions.update(event.eventValue, Math.max(0f, speed()));
		}
	}

	/* ---- CarUxRestrictionsManagerService ---- */

	/**
	 * The vehicle's UX restrictions configuration: AOSP's default map
	 * (service/res/xml/car_ux_restrictions_map.xml). The car service applies it on an automotive
	 * device; the car app host applies the same map for the car it projects to.
	 */
	public static CarUxRestrictionsConfiguration defaultUxRestrictionsConfiguration() {
		int moving = CarUxRestrictions.UX_RESTRICTIONS_NO_DIALPAD | CarUxRestrictions.UX_RESTRICTIONS_NO_FILTERING
		    | CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH | CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD
		    | CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO | CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT
		    | CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP | CarUxRestrictions.UX_RESTRICTIONS_NO_TEXT_MESSAGE;
		CarUxRestrictionsConfiguration.Builder b = new CarUxRestrictionsConfiguration.Builder();
		b.setUxRestrictions(CarDrivingStateEvent.DRIVING_STATE_PARKED, false, CarUxRestrictions.UX_RESTRICTIONS_BASELINE);
		b.setUxRestrictions(CarDrivingStateEvent.DRIVING_STATE_IDLING, false, CarUxRestrictions.UX_RESTRICTIONS_BASELINE);
		b.setUxRestrictions(CarDrivingStateEvent.DRIVING_STATE_MOVING, new CarUxRestrictionsConfiguration.DrivingStateRestrictions()
		    .setDistractionOptimizationRequired(true).setRestrictions(moving)
		    .setSpeedRange(new CarUxRestrictionsConfiguration.Builder.SpeedRange(0f, 5.0f)));
		b.setUxRestrictions(CarDrivingStateEvent.DRIVING_STATE_MOVING, new CarUxRestrictionsConfiguration.DrivingStateRestrictions()
		    .setDistractionOptimizationRequired(true).setRestrictions(moving)
		    .setSpeedRange(new CarUxRestrictionsConfiguration.Builder.SpeedRange(5.0f)));
		b.setMaxStringLength(120);
		b.setMaxCumulativeContentItems(21);
		b.setMaxContentDepth(3);
		return b.build();
	}

	private final class UxRestrictionsService extends ICarUxRestrictionsManager.Stub {
		/* AOSP service/res/xml/car_ux_restrictions_map.xml */
		private final CarUxRestrictionsConfiguration mConfig;
		private final ArrayList<ICarUxRestrictionsChangeListener> mListeners = new ArrayList<>();
		private final HashMap<Integer, Integer> mVirtualToPhysical = new HashMap<>();
		private String mMode = CarUxRestrictionsManagerModes.BASELINE;
		private CarUxRestrictions mCurrent;

		UxRestrictionsService() {
			mConfig = defaultUxRestrictionsConfiguration();
			/* AOSP: until the driving state is known, the restrictions are fully restricted */
			mCurrent = createUnrestricted(false);
		}

		/* AOSP CarUxRestrictionsManagerService: fully restricted while the state is unknown */
		private CarUxRestrictions createUnrestricted(boolean known) {
			return new CarUxRestrictions.Builder(!known, known ? CarUxRestrictions.UX_RESTRICTIONS_BASELINE : CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED, SystemClock.elapsedRealtimeNanos()).build();
		}

		void update(int drivingState, float speed) {
			CarUxRestrictions next;
			synchronized (this) {
				next = drivingState == CarDrivingStateEvent.DRIVING_STATE_UNKNOWN ? createUnrestricted(false)
				     : mConfig.getUxRestrictions(drivingState, speed, mMode);
				if (mCurrent.isSameRestrictions(next))
					return;
				mCurrent = next;
			}
			ArrayList<ICarUxRestrictionsChangeListener> listeners;
			synchronized (this) {
				listeners = new ArrayList<>(mListeners);
			}
			for (ICarUxRestrictionsChangeListener l : listeners) {
				try {
					l.onUxRestrictionsChanged(next);
				} catch (RemoteException e) {
					Slog.w(TAG, "UX restrictions listener failed", e);
				}
			}
		}

		@Override
		public void registerUxRestrictionsChangeListener(ICarUxRestrictionsChangeListener listener, int displayId) {
			if (listener == null)
				throw new IllegalArgumentException("listener is null");
			synchronized (this) {
				for (ICarUxRestrictionsChangeListener l : mListeners) {
					if (l.asBinder() == listener.asBinder())
						return;
				}
				mListeners.add(listener);
			}
		}

		@Override
		public void unregisterUxRestrictionsChangeListener(ICarUxRestrictionsChangeListener listener) {
			synchronized (this) {
				for (int i = mListeners.size() - 1; i >= 0; i--) {
					if (mListeners.get(i).asBinder() == listener.asBinder())
						mListeners.remove(i);
				}
			}
		}

		/* one physical display: every display (and virtual display) has the same restrictions */
		@Override
		public synchronized CarUxRestrictions getCurrentUxRestrictions(int displayId) {
			return mCurrent;
		}

		@Override
		public boolean saveUxRestrictionsConfigurationForNextBoot(List<CarUxRestrictionsConfiguration> configs) {
			enforcePermission(Car.PERMISSION_CAR_UX_RESTRICTIONS_CONFIGURATION);
			/* AOSP writes a staged config file; this device's restrictions are the AOSP defaults
			 * and there is no persistent staging area, so nothing is staged */
			return false;
		}

		@Override
		public List<CarUxRestrictionsConfiguration> getStagedConfigs() {
			enforcePermission(Car.PERMISSION_CAR_UX_RESTRICTIONS_CONFIGURATION);
			return new ArrayList<>();
		}

		@Override
		public List<CarUxRestrictionsConfiguration> getConfigs() {
			enforcePermission(Car.PERMISSION_CAR_UX_RESTRICTIONS_CONFIGURATION);
			return new ArrayList<>(Collections.singletonList(mConfig));
		}

		@Override
		public void reportVirtualDisplayToPhysicalDisplay(IRemoteCallback binder, int virtualDisplayId, int physicalDisplayId) {
			synchronized (this) {
				mVirtualToPhysical.put(virtualDisplayId, physicalDisplayId);
			}
		}

		@Override
		public synchronized int getMappedPhysicalDisplayOfVirtualDisplay(int displayId) {
			Integer physical = mVirtualToPhysical.get(displayId);
			return physical != null ? physical : Display.DEFAULT_DISPLAY;
		}

		@Override
		public boolean setRestrictionMode(String mode) {
			enforcePermission(Car.PERMISSION_CAR_UX_RESTRICTIONS_CONFIGURATION);
			if (mode == null)
				return false;
			synchronized (this) {
				mMode = mode;
			}
			update(mDrivingState.state(), Math.max(0f, mDrivingState.speed()));
			return true;
		}

		@Override
		public synchronized String getRestrictionMode() {
			enforcePermission(Car.PERMISSION_CAR_UX_RESTRICTIONS_CONFIGURATION);
			return mMode;
		}
	}

	/** AOSP CarUxRestrictionsManager.UX_RESTRICTION_MODE_BASELINE */
	private static final class CarUxRestrictionsManagerModes {
		static final String BASELINE = "baseline";
	}

	/* ---- AppFocusService ---- */

	private final class AppFocusService extends IAppFocus.Stub {
		private final HashMap<Integer, IAppFocusOwnershipCallback> mOwners = new HashMap<>();
		private final HashMap<IBinder, Set<Integer>> mListeners = new HashMap<>();
		private final HashMap<IBinder, IAppFocusListener> mListenerObjects = new HashMap<>();

		@Override
		public void registerFocusListener(IAppFocusListener callback, int appType) {
			synchronized (this) {
				Set<Integer> types = mListeners.get(callback.asBinder());
				if (types == null) {
					mListeners.put(callback.asBinder(), types = new HashSet<>());
					mListenerObjects.put(callback.asBinder(), callback);
				}
				types.add(appType);
			}
		}

		@Override
		public void unregisterFocusListener(IAppFocusListener callback, int appType) {
			synchronized (this) {
				Set<Integer> types = mListeners.get(callback.asBinder());
				if (types == null)
					return;
				types.remove(appType);
				if (types.isEmpty()) {
					mListeners.remove(callback.asBinder());
					mListenerObjects.remove(callback.asBinder());
				}
			}
		}

		@Override
		public synchronized int[] getActiveAppTypes() {
			int[] types = new int[mOwners.size()];
			int i = 0;
			for (Integer t : mOwners.keySet())
				types[i++] = t;
			return types;
		}

		@Override
		public synchronized boolean isOwningFocus(IAppFocusOwnershipCallback callback, int appType) {
			IAppFocusOwnershipCallback owner = mOwners.get(appType);
			return owner != null && owner.asBinder() == callback.asBinder();
		}

		@Override
		public int requestAppFocus(IAppFocusOwnershipCallback callback, int appType) {
			if (appType < CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION || appType > CarAppFocusManager.APP_FOCUS_MAX)
				throw new IllegalArgumentException("invalid app type " + appType);
			IAppFocusOwnershipCallback previous;
			synchronized (this) {
				previous = mOwners.get(appType);
				if (previous != null && previous.asBinder() == callback.asBinder())
					return CarAppFocusManager.APP_FOCUS_REQUEST_SUCCEEDED;
				mOwners.put(appType, callback);
				mOwnerUids.put(appType, Binder.getCallingUid());
			}
			if (previous != null) {
				try {
					previous.onAppFocusOwnershipLost(appType);
				} catch (RemoteException e) {
					Slog.w(TAG, "focus owner gone", e);
				}
			}
			try {
				callback.onAppFocusOwnershipGranted(appType);
			} catch (RemoteException e) {
				Slog.w(TAG, "new focus owner gone", e);
			}
			if (previous == null)
				dispatchFocusChange(appType, true);
			return CarAppFocusManager.APP_FOCUS_REQUEST_SUCCEEDED;
		}

		@Override
		public void abandonAppFocus(IAppFocusOwnershipCallback callback, int appType) {
			synchronized (this) {
				IAppFocusOwnershipCallback owner = mOwners.get(appType);
				if (owner == null || owner.asBinder() != callback.asBinder())
					return;
				mOwners.remove(appType);
				mOwnerUids.remove(appType);
			}
			dispatchFocusChange(appType, false);
		}

		boolean owns(int uid, int appType) {
			synchronized (this) {
				return mOwners.containsKey(appType) && mOwnerUids.get(appType) != null && mOwnerUids.get(appType) == uid;
			}
		}

		private final HashMap<Integer, Integer> mOwnerUids = new HashMap<>();

		private void dispatchFocusChange(int appType, boolean active) {
			ArrayList<IAppFocusListener> targets = new ArrayList<>();
			synchronized (this) {
				for (Map.Entry<IBinder, Set<Integer>> e : mListeners.entrySet()) {
					if (e.getValue().contains(appType))
						targets.add(mListenerObjects.get(e.getKey()));
				}
			}
			for (IAppFocusListener l : targets) {
				try {
					l.onAppFocusChanged(appType, active);
				} catch (RemoteException e) {
					Slog.w(TAG, "focus listener gone", e);
				}
			}
		}
	}

	/* ---- ClusterNavigationService ---- */

	private final class ClusterNavigationService extends IInstrumentClusterNavigation.Stub {
		@Override
		public void onNavigationStateChanged(Bundle bundle) {
			enforcePermission(Car.PERMISSION_CAR_NAVIGATION_MANAGER);
			/* AOSP assertNavigationFocus: the caller must own navigation focus */
			if (!mAppFocus.owns(Binder.getCallingUid(), CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION))
				throw new IllegalStateException("caller does not own navigation focus");
			/* no instrument cluster renderer on this car: the state has nowhere to go */
		}

		@Override
		public CarNavigationInstrumentCluster getInstrumentClusterInfo() {
			enforcePermission(Car.PERMISSION_CAR_NAVIGATION_MANAGER);
			return null;
		}
	}
}
