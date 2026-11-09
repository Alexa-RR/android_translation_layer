/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os;
import android.util.AndroidException;

/**
 * Parent exception for all Binder remote-invocation errors
 */
public class RemoteException extends AndroidException {
	public RemoteException() {
		super();
	}

	public RemoteException(String message) {
		super(message);
	}

	/** AOSP: a RemoteException with a cause. */
	public RemoteException(String message, Throwable cause) {
		super(message, cause);
	}

	/** AOSP (hidden): control over suppression and the stack trace (Parcel's remote-stack cause). */
	public RemoteException(String message, Throwable cause, boolean enableSuppression,
	                       boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

	/** AOSP (API 26): a RemoteException from a system service is a fatal RuntimeException. */
	public RuntimeException rethrowFromSystemServer() {
		if (this instanceof DeadObjectException) {
			throw new RuntimeException(new DeadSystemException());
		} else {
			throw new RuntimeException(this);
		}
	}

	/** AOSP (API 26): rethrown as a RuntimeException. */
	public RuntimeException rethrowAsRuntimeException() {
		throw new RuntimeException(this);
	}
}
