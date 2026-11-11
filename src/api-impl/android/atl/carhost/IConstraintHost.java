/*
 * Copyright 2021 The Android Open Source Project
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
 * androidx.car.app.constraints.IConstraintHost (Car App Library stable AIDL, version 1) in the
 * Java form the AIDL compiler generates: the host service of type "constraints", which tells the
 * app how many items its templates may hold (ConstraintManager.getContentLimit) and whether the
 * host allows the app to refresh its templates by itself.
 */
public interface IConstraintHost extends IInterface {
	String DESCRIPTOR = "androidx.car.app.constraints.IConstraintHost";
	int VERSION = 1;

	int getContentLimit(int contentLimitType) throws RemoteException;

	boolean isAppDrivenRefreshEnabled() throws RemoteException;

	int getInterfaceVersion() throws RemoteException;

	abstract class Stub extends android.os.Binder implements IConstraintHost {
		static final int TRANSACTION_getContentLimit = IBinder.FIRST_CALL_TRANSACTION + 1;
		static final int TRANSACTION_isAppDrivenRefreshEnabled = IBinder.FIRST_CALL_TRANSACTION + 2;
		static final int TRANSACTION_getInterfaceVersion = 16777215;

		public Stub() {
			attachInterface(this, DESCRIPTOR);
		}

		public static IConstraintHost asInterface(IBinder obj) {
			if (obj == null)
				return null;
			IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
			if (iin instanceof IConstraintHost)
				return (IConstraintHost)iin;
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
				case TRANSACTION_getContentLimit: {
					int result = getContentLimit(data.readInt());
					reply.writeNoException();
					reply.writeInt(result);
					return true;
				}
				case TRANSACTION_isAppDrivenRefreshEnabled: {
					boolean result = isAppDrivenRefreshEnabled();
					reply.writeNoException();
					reply.writeInt(result ? 1 : 0);
					return true;
				}
				default:
					return super.onTransact(code, data, reply, flags);
			}
		}

		private static class Proxy implements IConstraintHost {
			private final IBinder mRemote;
			private int mCachedVersion = -1;

			Proxy(IBinder remote) {
				mRemote = remote;
			}

			@Override
			public IBinder asBinder() {
				return mRemote;
			}

			private int callInt(int code, int arg, boolean hasArg) throws RemoteException {
				Parcel data = Parcel.obtain();
				Parcel reply = Parcel.obtain();
				try {
					data.writeInterfaceToken(DESCRIPTOR);
					if (hasArg)
						data.writeInt(arg);
					if (!mRemote.transact(code, data, reply, 0))
						throw new RemoteException("Method " + code + " is unimplemented.");
					reply.readException();
					return reply.readInt();
				} finally {
					reply.recycle();
					data.recycle();
				}
			}

			@Override
			public int getContentLimit(int contentLimitType) throws RemoteException {
				return callInt(TRANSACTION_getContentLimit, contentLimitType, true);
			}

			@Override
			public boolean isAppDrivenRefreshEnabled() throws RemoteException {
				return callInt(TRANSACTION_isAppDrivenRefreshEnabled, 0, false) != 0;
			}

			@Override
			public int getInterfaceVersion() throws RemoteException {
				if (mCachedVersion == -1)
					mCachedVersion = callInt(TRANSACTION_getInterfaceVersion, 0, false);
				return mCachedVersion;
			}
		}
	}
}
