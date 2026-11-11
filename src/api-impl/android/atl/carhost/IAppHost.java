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

import android.location.Location;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.text.TextUtils;

/**
 * androidx.car.app.IAppHost (Car App Library stable AIDL, version 1) in the Java form the AIDL
 * compiler generates: the host service of type "app" (templates, toasts, the map surface, alerts,
 * location, microphone). The surface callback is handed over as its binder
 * (androidx.car.app.ISurfaceCallback).
 */
public interface IAppHost extends IInterface {
	String DESCRIPTOR = "androidx.car.app.IAppHost";
	int VERSION = 1;

	void invalidate() throws RemoteException;
	void showToast(CharSequence text, int duration) throws RemoteException;
	void setSurfaceCallback(IBinder surfaceCallback) throws RemoteException;
	void sendLocation(Location location) throws RemoteException;
	void showAlert(Bundleable alert) throws RemoteException;
	void dismissAlert(int alertId) throws RemoteException;
	Bundleable openMicrophone(Bundleable openMicrophoneRequest) throws RemoteException;
	int getInterfaceVersion() throws RemoteException;

	abstract class Stub extends android.os.Binder implements IAppHost {
		static final int TRANSACTION_invalidate = IBinder.FIRST_CALL_TRANSACTION + 1;
		static final int TRANSACTION_showToast = IBinder.FIRST_CALL_TRANSACTION + 2;
		static final int TRANSACTION_setSurfaceCallback = IBinder.FIRST_CALL_TRANSACTION + 3;
		static final int TRANSACTION_sendLocation = IBinder.FIRST_CALL_TRANSACTION + 4;
		static final int TRANSACTION_showAlert = IBinder.FIRST_CALL_TRANSACTION + 5;
		static final int TRANSACTION_dismissAlert = IBinder.FIRST_CALL_TRANSACTION + 6;
		static final int TRANSACTION_openMicrophone = IBinder.FIRST_CALL_TRANSACTION + 7;
		static final int TRANSACTION_getInterfaceVersion = 16777215;

		public Stub() {
			attachInterface(this, DESCRIPTOR);
		}

		public static IAppHost asInterface(IBinder obj) {
			if (obj == null)
				return null;
			IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
			if (iin instanceof IAppHost)
				return (IAppHost)iin;
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
				case TRANSACTION_invalidate:
					invalidate();
					reply.writeNoException();
					return true;
				case TRANSACTION_showToast: {
					CharSequence text = data.readTypedObject(TextUtils.CHAR_SEQUENCE_CREATOR);
					showToast(text, data.readInt());
					reply.writeNoException();
					return true;
				}
				case TRANSACTION_setSurfaceCallback:
					setSurfaceCallback(data.readStrongBinder());
					reply.writeNoException();
					return true;
				case TRANSACTION_sendLocation:
					sendLocation(data.readTypedObject(Location.CREATOR));
					reply.writeNoException();
					return true;
				case TRANSACTION_showAlert:
					showAlert(data.readTypedObject(Bundleable.CREATOR));
					reply.writeNoException();
					return true;
				case TRANSACTION_dismissAlert:
					dismissAlert(data.readInt());
					reply.writeNoException();
					return true;
				case TRANSACTION_openMicrophone: {
					Bundleable result = openMicrophone(data.readTypedObject(Bundleable.CREATOR));
					reply.writeNoException();
					reply.writeTypedObject(result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
					return true;
				}
				default:
					return super.onTransact(code, data, reply, flags);
			}
		}

		private static class Proxy implements IAppHost {
			private final IBinder mRemote;
			private int mCachedVersion = -1;

			Proxy(IBinder remote) {
				mRemote = remote;
			}

			@Override
			public IBinder asBinder() {
				return mRemote;
			}

			private Parcel call(int code, Parcel data) throws RemoteException {
				Parcel reply = Parcel.obtain();
				try {
					if (!mRemote.transact(code, data, reply, 0))
						throw new RemoteException("Method " + code + " is unimplemented.");
					reply.readException();
					return reply;
				} catch (RuntimeException | RemoteException e) {
					reply.recycle();
					throw e;
				} finally {
					data.recycle();
				}
			}

			private static Parcel start() {
				Parcel data = Parcel.obtain();
				data.writeInterfaceToken(DESCRIPTOR);
				return data;
			}

			@Override
			public void invalidate() throws RemoteException {
				call(TRANSACTION_invalidate, start()).recycle();
			}

			@Override
			public void showToast(CharSequence text, int duration) throws RemoteException {
				Parcel data = start();
				if (text != null) {
					data.writeInt(1);
					TextUtils.writeToParcel(text, data, 0);
				} else {
					data.writeInt(0);
				}
				data.writeInt(duration);
				call(TRANSACTION_showToast, data).recycle();
			}

			@Override
			public void setSurfaceCallback(IBinder surfaceCallback) throws RemoteException {
				Parcel data = start();
				data.writeStrongBinder(surfaceCallback);
				call(TRANSACTION_setSurfaceCallback, data).recycle();
			}

			@Override
			public void sendLocation(Location location) throws RemoteException {
				Parcel data = start();
				data.writeTypedObject(location, 0);
				call(TRANSACTION_sendLocation, data).recycle();
			}

			@Override
			public void showAlert(Bundleable alert) throws RemoteException {
				Parcel data = start();
				data.writeTypedObject(alert, 0);
				call(TRANSACTION_showAlert, data).recycle();
			}

			@Override
			public void dismissAlert(int alertId) throws RemoteException {
				Parcel data = start();
				data.writeInt(alertId);
				call(TRANSACTION_dismissAlert, data).recycle();
			}

			@Override
			public Bundleable openMicrophone(Bundleable openMicrophoneRequest) throws RemoteException {
				Parcel data = start();
				data.writeTypedObject(openMicrophoneRequest, 0);
				Parcel reply = call(TRANSACTION_openMicrophone, data);
				try {
					return reply.readTypedObject(Bundleable.CREATOR);
				} finally {
					reply.recycle();
				}
			}

			@Override
			public int getInterfaceVersion() throws RemoteException {
				if (mCachedVersion == -1) {
					Parcel reply = call(TRANSACTION_getInterfaceVersion, start());
					try {
						mCachedVersion = reply.readInt();
					} finally {
						reply.recycle();
					}
				}
				return mCachedVersion;
			}
		}
	}
}
