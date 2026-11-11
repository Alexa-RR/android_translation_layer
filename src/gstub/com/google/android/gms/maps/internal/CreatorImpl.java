package com.google.android.gms.maps.internal;

import android.os.Binder;
import android.os.IInterface;
import android.os.Parcel;

/* An in-process ICreator: a local android.os.Binder (death links, ping, dump and the rest of
 * IBinder behave as for any local object), answering the calls in onTransact. */
public class CreatorImpl extends Binder {

	@Override
	public String getInterfaceDescriptor() {
		return "com.google.android.gms.maps.internal.ICreator";
	}

	@Override
	public IInterface queryLocalInterface(String descriptor) {
		System.out.println("gms.maps CreatorImpl.queryLocalInterface(" + descriptor + ")");
		return null;
	}

	// Would normally be compiled from AIDL. Implemented manually for now to not depend on an AIDL compiler
	@Override
	protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
		System.out.println("gms.maps CreatorImpl.transact(" + code + ")");
		switch (code) {
			case 3: // newMapViewDelegate
				reply.writeNoException();
				reply.writeStrongBinder(null);
				break;
			case 4: // newCameraUpdateFactoryDelegate
				reply.writeNoException();
				reply.writeStrongBinder(new Binder());
				break;
			case 5: // newBitmapDescriptorFactoryDelegate
				reply.writeNoException();
				reply.writeStrongBinder(new Binder());
				break;
			case 6: // initV2
				reply.writeNoException();
				break;
			case 9: // getRenderType
				reply.writeNoException();
				reply.writeInt(0);
				break;
			case 10: // logInitialization
				reply.writeNoException();
				break;
			case 12: // unknown
				reply.writeNoException();
				break;
			default:
				try {
					reply.writeException(new UnsupportedOperationException("not implemented"));
				} catch (Exception e) {
				}
		}
		return true;
	}
}
