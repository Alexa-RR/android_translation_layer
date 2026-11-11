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
 * androidx.car.app.navigation.INavigationHost (Car App Library stable AIDL, version 1) in the Java
 * form the AIDL compiler generates: the host service of type "navigation" (navigation started and
 * ended, the trip for clusters and heads-up displays, voice assistant capabilities).
 */
public interface INavigationHost extends IInterface {
	String DESCRIPTOR = "androidx.car.app.navigation.INavigationHost";
	int VERSION = 1;

	void navigationStarted() throws RemoteException;
	void navigationEnded() throws RemoteException;
	void updateTrip(Bundleable trip) throws RemoteException;
	void setVoiceAssistantCapabilities(Bundleable capabilities) throws RemoteException;
	int getInterfaceVersion() throws RemoteException;

	abstract class Stub extends android.os.Binder implements INavigationHost {
		static final int TRANSACTION_navigationStarted = IBinder.FIRST_CALL_TRANSACTION + 1;
		static final int TRANSACTION_navigationEnded = IBinder.FIRST_CALL_TRANSACTION + 2;
		static final int TRANSACTION_updateTrip = IBinder.FIRST_CALL_TRANSACTION + 3;
		static final int TRANSACTION_setVoiceAssistantCapabilities = IBinder.FIRST_CALL_TRANSACTION + 4;
		static final int TRANSACTION_getInterfaceVersion = 16777215;

		public Stub() {
			attachInterface(this, DESCRIPTOR);
		}

		public static INavigationHost asInterface(IBinder obj) {
			if (obj == null)
				return null;
			IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
			if (iin instanceof INavigationHost)
				return (INavigationHost)iin;
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
				case TRANSACTION_navigationStarted:
					navigationStarted();
					reply.writeNoException();
					return true;
				case TRANSACTION_navigationEnded:
					navigationEnded();
					reply.writeNoException();
					return true;
				case TRANSACTION_updateTrip:
					updateTrip(data.readTypedObject(Bundleable.CREATOR));
					reply.writeNoException();
					return true;
				case TRANSACTION_setVoiceAssistantCapabilities:
					setVoiceAssistantCapabilities(data.readTypedObject(Bundleable.CREATOR));
					reply.writeNoException();
					return true;
				default:
					return super.onTransact(code, data, reply, flags);
			}
		}

		private static class Proxy implements INavigationHost {
			private final IBinder mRemote;
			private int mCachedVersion = -1;

			Proxy(IBinder remote) {
				mRemote = remote;
			}

			@Override
			public IBinder asBinder() {
				return mRemote;
			}

			private void call(int code, Bundleable arg, boolean hasArg) throws RemoteException {
				Parcel data = Parcel.obtain();
				Parcel reply = Parcel.obtain();
				try {
					data.writeInterfaceToken(DESCRIPTOR);
					if (hasArg)
						data.writeTypedObject(arg, 0);
					if (!mRemote.transact(code, data, reply, 0))
						throw new RemoteException("Method " + code + " is unimplemented.");
					reply.readException();
				} finally {
					reply.recycle();
					data.recycle();
				}
			}

			@Override
			public void navigationStarted() throws RemoteException {
				call(TRANSACTION_navigationStarted, null, false);
			}

			@Override
			public void navigationEnded() throws RemoteException {
				call(TRANSACTION_navigationEnded, null, false);
			}

			@Override
			public void updateTrip(Bundleable trip) throws RemoteException {
				call(TRANSACTION_updateTrip, trip, true);
			}

			@Override
			public void setVoiceAssistantCapabilities(Bundleable capabilities) throws RemoteException {
				call(TRANSACTION_setVoiceAssistantCapabilities, capabilities, true);
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
