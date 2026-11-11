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
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * androidx.car.app.ICarApp (Car App Library stable AIDL, version 1), in the Java form the AIDL
 * compiler generates: the app side of a car app session, implemented by the app's CarAppService
 * binder. Same descriptor and transaction codes as the library; the host owns this copy so that it
 * never touches the app's classes.
 */
public interface ICarApp extends IInterface {
	String DESCRIPTOR = "androidx.car.app.ICarApp";
	int VERSION = 1;

	void onAppCreate(ICarHost host, Intent intent, Configuration configuration, IOnDoneCallback callback) throws RemoteException;
	void onAppStart(IOnDoneCallback callback) throws RemoteException;
	void onAppResume(IOnDoneCallback callback) throws RemoteException;
	void onAppPause(IOnDoneCallback callback) throws RemoteException;
	void onAppStop(IOnDoneCallback callback) throws RemoteException;
	void onNewIntent(Intent intent, IOnDoneCallback callback) throws RemoteException;
	void onConfigurationChanged(Configuration configuration, IOnDoneCallback callback) throws RemoteException;
	void getManager(String type, IOnDoneCallback callback) throws RemoteException;
	void getAppInfo(IOnDoneCallback callback) throws RemoteException;
	void onHandshakeCompleted(Bundleable handshakeInfo, IOnDoneCallback callback) throws RemoteException;
	void getAppTheme(IOnDoneCallback callback) throws RemoteException;
	int getInterfaceVersion() throws RemoteException;

	abstract class Stub extends android.os.Binder implements ICarApp {
		static final int TRANSACTION_onAppCreate = IBinder.FIRST_CALL_TRANSACTION + 1;
		static final int TRANSACTION_onAppStart = IBinder.FIRST_CALL_TRANSACTION + 2;
		static final int TRANSACTION_onAppResume = IBinder.FIRST_CALL_TRANSACTION + 3;
		static final int TRANSACTION_onAppPause = IBinder.FIRST_CALL_TRANSACTION + 4;
		static final int TRANSACTION_onAppStop = IBinder.FIRST_CALL_TRANSACTION + 5;
		static final int TRANSACTION_onNewIntent = IBinder.FIRST_CALL_TRANSACTION + 6;
		static final int TRANSACTION_onConfigurationChanged = IBinder.FIRST_CALL_TRANSACTION + 7;
		static final int TRANSACTION_getManager = IBinder.FIRST_CALL_TRANSACTION + 8;
		static final int TRANSACTION_getAppInfo = IBinder.FIRST_CALL_TRANSACTION + 9;
		static final int TRANSACTION_onHandshakeCompleted = IBinder.FIRST_CALL_TRANSACTION + 10;
		static final int TRANSACTION_getAppTheme = IBinder.FIRST_CALL_TRANSACTION + 11;
		static final int TRANSACTION_getInterfaceVersion = 16777215;

		public Stub() {
			attachInterface(this, DESCRIPTOR);
		}

		public static ICarApp asInterface(IBinder obj) {
			if (obj == null)
				return null;
			IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
			if (iin instanceof ICarApp)
				return (ICarApp)iin;
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
				case TRANSACTION_onAppCreate:
					onAppCreate(ICarHost.Stub.asInterface(data.readStrongBinder()), data.readTypedObject(Intent.CREATOR),
					            data.readTypedObject(Configuration.CREATOR), IOnDoneCallback.Stub.asInterface(data.readStrongBinder()));
					return true;
				case TRANSACTION_onAppStart:
					onAppStart(IOnDoneCallback.Stub.asInterface(data.readStrongBinder()));
					return true;
				case TRANSACTION_onAppResume:
					onAppResume(IOnDoneCallback.Stub.asInterface(data.readStrongBinder()));
					return true;
				case TRANSACTION_onAppPause:
					onAppPause(IOnDoneCallback.Stub.asInterface(data.readStrongBinder()));
					return true;
				case TRANSACTION_onAppStop:
					onAppStop(IOnDoneCallback.Stub.asInterface(data.readStrongBinder()));
					return true;
				case TRANSACTION_onNewIntent:
					onNewIntent(data.readTypedObject(Intent.CREATOR), IOnDoneCallback.Stub.asInterface(data.readStrongBinder()));
					return true;
				case TRANSACTION_onConfigurationChanged:
					onConfigurationChanged(data.readTypedObject(Configuration.CREATOR), IOnDoneCallback.Stub.asInterface(data.readStrongBinder()));
					return true;
				case TRANSACTION_getManager:
					getManager(data.readString(), IOnDoneCallback.Stub.asInterface(data.readStrongBinder()));
					return true;
				case TRANSACTION_getAppInfo:
					getAppInfo(IOnDoneCallback.Stub.asInterface(data.readStrongBinder()));
					return true;
				case TRANSACTION_onHandshakeCompleted:
					onHandshakeCompleted(data.readTypedObject(Bundleable.CREATOR), IOnDoneCallback.Stub.asInterface(data.readStrongBinder()));
					return true;
				case TRANSACTION_getAppTheme:
					getAppTheme(IOnDoneCallback.Stub.asInterface(data.readStrongBinder()));
					return true;
				default:
					return super.onTransact(code, data, reply, flags);
			}
		}

		private static class Proxy implements ICarApp {
			private final IBinder mRemote;
			private int mCachedVersion = -1;

			Proxy(IBinder remote) {
				mRemote = remote;
			}

			@Override
			public IBinder asBinder() {
				return mRemote;
			}

			/* every method is oneway: no reply parcel */
			private void call(int code, Parcel data) throws RemoteException {
				try {
					if (!mRemote.transact(code, data, null, IBinder.FLAG_ONEWAY))
						throw new RemoteException("Method " + code + " is unimplemented.");
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
			public void onAppCreate(ICarHost host, Intent intent, Configuration configuration, IOnDoneCallback callback) throws RemoteException {
				Parcel data = start();
				data.writeStrongInterface(host);
				data.writeTypedObject(intent, 0);
				data.writeTypedObject(configuration, 0);
				data.writeStrongInterface(callback);
				call(TRANSACTION_onAppCreate, data);
			}

			@Override
			public void onAppStart(IOnDoneCallback callback) throws RemoteException {
				Parcel data = start();
				data.writeStrongInterface(callback);
				call(TRANSACTION_onAppStart, data);
			}

			@Override
			public void onAppResume(IOnDoneCallback callback) throws RemoteException {
				Parcel data = start();
				data.writeStrongInterface(callback);
				call(TRANSACTION_onAppResume, data);
			}

			@Override
			public void onAppPause(IOnDoneCallback callback) throws RemoteException {
				Parcel data = start();
				data.writeStrongInterface(callback);
				call(TRANSACTION_onAppPause, data);
			}

			@Override
			public void onAppStop(IOnDoneCallback callback) throws RemoteException {
				Parcel data = start();
				data.writeStrongInterface(callback);
				call(TRANSACTION_onAppStop, data);
			}

			@Override
			public void onNewIntent(Intent intent, IOnDoneCallback callback) throws RemoteException {
				Parcel data = start();
				data.writeTypedObject(intent, 0);
				data.writeStrongInterface(callback);
				call(TRANSACTION_onNewIntent, data);
			}

			@Override
			public void onConfigurationChanged(Configuration configuration, IOnDoneCallback callback) throws RemoteException {
				Parcel data = start();
				data.writeTypedObject(configuration, 0);
				data.writeStrongInterface(callback);
				call(TRANSACTION_onConfigurationChanged, data);
			}

			@Override
			public void getManager(String type, IOnDoneCallback callback) throws RemoteException {
				Parcel data = start();
				data.writeString(type);
				data.writeStrongInterface(callback);
				call(TRANSACTION_getManager, data);
			}

			@Override
			public void getAppInfo(IOnDoneCallback callback) throws RemoteException {
				Parcel data = start();
				data.writeStrongInterface(callback);
				call(TRANSACTION_getAppInfo, data);
			}

			@Override
			public void onHandshakeCompleted(Bundleable handshakeInfo, IOnDoneCallback callback) throws RemoteException {
				Parcel data = start();
				data.writeTypedObject(handshakeInfo, 0);
				data.writeStrongInterface(callback);
				call(TRANSACTION_onHandshakeCompleted, data);
			}

			@Override
			public void getAppTheme(IOnDoneCallback callback) throws RemoteException {
				Parcel data = start();
				data.writeStrongInterface(callback);
				call(TRANSACTION_getAppTheme, data);
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
