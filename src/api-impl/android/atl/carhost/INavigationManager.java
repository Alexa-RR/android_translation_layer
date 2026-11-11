/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.atl.carhost;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * androidx.car.app.navigation.INavigationManager (Car App Library stable AIDL, version 1, oneway)
 * in the Java form the AIDL compiler generates: the app's manager of type "navigation", which the
 * host tells to stop navigating (another app took over navigation, or the user ended it in the car).
 */
public interface INavigationManager extends IInterface {
	String DESCRIPTOR = "androidx.car.app.navigation.INavigationManager";
	int VERSION = 1;

	void onStopNavigation(IOnDoneCallback callback) throws RemoteException;
	int getInterfaceVersion() throws RemoteException;

	abstract class Stub extends android.os.Binder implements INavigationManager {
		static final int TRANSACTION_onStopNavigation = IBinder.FIRST_CALL_TRANSACTION + 1;
		static final int TRANSACTION_getInterfaceVersion = 16777215;

		public Stub() {
			attachInterface(this, DESCRIPTOR);
		}

		public static INavigationManager asInterface(IBinder obj) {
			if (obj == null)
				return null;
			IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
			if (iin instanceof INavigationManager)
				return (INavigationManager)iin;
			return new Proxy(obj);
		}

		@Override
		public IBinder asBinder() {
			return this;
		}

		@Override
		public int getInterfaceVersion() {
			return VERSION;
		}

		@Override
		protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
			if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION)
				data.enforceInterface(DESCRIPTOR);
			switch (code) {
				case INTERFACE_TRANSACTION:
					reply.writeString(DESCRIPTOR);
					return true;
				case TRANSACTION_getInterfaceVersion:
					reply.writeNoException();
					reply.writeInt(getInterfaceVersion());
					return true;
				case TRANSACTION_onStopNavigation:
					onStopNavigation(IOnDoneCallback.Stub.asInterface(data.readStrongBinder()));
					return true;
				default:
					return super.onTransact(code, data, reply, flags);
			}
		}

		private static class Proxy implements INavigationManager {
			private final IBinder mRemote;
			private int mCachedVersion = -1;

			Proxy(IBinder remote) {
				mRemote = remote;
			}

			@Override
			public IBinder asBinder() {
				return mRemote;
			}

			@Override
			public void onStopNavigation(IOnDoneCallback callback) throws RemoteException {
				Parcel data = Parcel.obtain();
				try {
					data.writeInterfaceToken(DESCRIPTOR);
					data.writeStrongInterface(callback);
					if (!mRemote.transact(TRANSACTION_onStopNavigation, data, null, IBinder.FLAG_ONEWAY))
						throw new RemoteException("Method onStopNavigation is unimplemented.");
				} finally {
					data.recycle();
				}
			}

			@Override
			public int getInterfaceVersion() throws RemoteException {
				if (mCachedVersion == -1) {
					Parcel data = Parcel.obtain();
					Parcel reply = Parcel.obtain();
					try {
						data.writeInterfaceToken(DESCRIPTOR);
						mRemote.transact(TRANSACTION_getInterfaceVersion, data, reply, 0);
						reply.readException();
						mCachedVersion = reply.readInt();
					} finally {
						reply.recycle();
						data.recycle();
					}
				}
				return mCachedVersion;
			}
		}
	}
}
