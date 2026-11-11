package com.google.android.gms.ads.measurement;

import android.os.Binder;
import android.os.IInterface;
import android.os.Parcel;

/* An in-process IMeasurementManager: a local android.os.Binder (death links, ping, dump and the
 * rest of IBinder behave as for any local object). */
public class DynamiteMeasurementManager extends Binder {

	@Override
	public String getInterfaceDescriptor() {
		return "com.google.android.gms.ads.measurement.IMeasurementManager";
	}

	public void initialize() {
	}

	@Override
	protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
		return true;
	}

	@Override
	public IInterface queryLocalInterface(String descriptor) {
		return null;
	}
}
