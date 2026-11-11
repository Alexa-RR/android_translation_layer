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

import android.content.Intent;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * androidx.car.app.ICarHost (Car App Library stable AIDL, version 1) in the Java form the AIDL
 * compiler generates: the host's side of a session, which hands the app the host services
 * ("app", "navigation", ...) by type.
 */
public interface ICarHost extends IInterface {
	String DESCRIPTOR = "androidx.car.app.ICarHost";
	int VERSION = 1;

	void startCarApp(Intent intent) throws RemoteException;
	IBinder getHost(String type) throws RemoteException;
	void finish() throws RemoteException;
	int getInterfaceVersion() throws RemoteException;

	abstract class Stub extends android.os.Binder implements ICarHost {
		static final int TRANSACTION_startCarApp = IBinder.FIRST_CALL_TRANSACTION + 1;
		static final int TRANSACTION_getHost = IBinder.FIRST_CALL_TRANSACTION + 2;
		static final int TRANSACTION_finish = IBinder.FIRST_CALL_TRANSACTION + 3;
		static final int TRANSACTION_getInterfaceVersion = 16777215;

		public Stub() {
			attachInterface(this, DESCRIPTOR);
		}

		public static ICarHost asInterface(IBinder obj) {
			if (obj == null)
				return null;
			IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
			if (iin instanceof ICarHost)
				return (ICarHost)iin;
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
				case TRANSACTION_startCarApp:
					startCarApp(data.readTypedObject(Intent.CREATOR));
					reply.writeNoException();
					return true;
				case TRANSACTION_getHost: {
					IBinder host = getHost(data.readString());
					reply.writeNoException();
					reply.writeStrongBinder(host);
					return true;
				}
				case TRANSACTION_finish:
					finish();
					reply.writeNoException();
					return true;
				default:
					return super.onTransact(code, data, reply, flags);
			}
		}

		private static class Proxy implements ICarHost {
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
			public void startCarApp(Intent intent) throws RemoteException {
				Parcel data = Parcel.obtain();
				Parcel reply = Parcel.obtain();
				try {
					data.writeInterfaceToken(DESCRIPTOR);
					data.writeTypedObject(intent, 0);
					if (!mRemote.transact(TRANSACTION_startCarApp, data, reply, 0))
						throw new RemoteException("Method startCarApp is unimplemented.");
					reply.readException();
				} finally {
					reply.recycle();
					data.recycle();
				}
			}

			@Override
			public IBinder getHost(String type) throws RemoteException {
				Parcel data = Parcel.obtain();
				Parcel reply = Parcel.obtain();
				try {
					data.writeInterfaceToken(DESCRIPTOR);
					data.writeString(type);
					if (!mRemote.transact(TRANSACTION_getHost, data, reply, 0))
						throw new RemoteException("Method getHost is unimplemented.");
					reply.readException();
					return reply.readStrongBinder();
				} finally {
					reply.recycle();
					data.recycle();
				}
			}

			@Override
			public void finish() throws RemoteException {
				Parcel data = Parcel.obtain();
				Parcel reply = Parcel.obtain();
				try {
					data.writeInterfaceToken(DESCRIPTOR);
					if (!mRemote.transact(TRANSACTION_finish, data, reply, 0))
						throw new RemoteException("Method finish is unimplemented.");
					reply.readException();
				} finally {
					reply.recycle();
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
