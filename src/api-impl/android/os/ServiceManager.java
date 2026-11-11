package android.os;

import java.util.HashMap;

/**
 * AOSP: the registry of system services by name (servicemanager). Here the platform's services
 * run in this process; the ones that exist are created on first lookup ("car_service": the car
 * service, only on an automotive device), and platform code can add others.
 *
 * @hide
 */
public final class ServiceManager {
	private static final HashMap<String, IBinder> sServices = new HashMap<>();

	/** The service named name, or null when there is none. */
	public static IBinder getService(String name) {
		if (name == null)
			return null;
		synchronized (sServices) {
			IBinder b = sServices.get(name);
			if (b != null)
				return b;
		}
		if ("car_service".equals(name))
			return android.atl.car.ATLCarService.getServiceBinder();
		return null;
	}

	/** AOSP: like getService, without waiting for a service that is still starting. */
	public static IBinder checkService(String name) {
		return getService(name);
	}

	/** AOSP: as getService; a service that does not exist on this device never comes. */
	public static IBinder waitForService(String name) {
		return getService(name);
	}

	/** Registers a platform service under name. */
	public static void addService(String name, IBinder service) {
		if (name == null || service == null)
			throw new NullPointerException("name and service must not be null");
		synchronized (sServices) {
			sServices.put(name, service);
		}
	}

	/** AOSP: the names of the registered services. */
	public static String[] listServices() {
		synchronized (sServices) {
			return sServices.keySet().toArray(new String[0]);
		}
	}
}
