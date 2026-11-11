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

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The host side of androidx.car.app.serialization.Bundleable: the wire form of every model object
 * the Car App Library exchanges (a Bundle written with Parcel.writeBundle). The Bundle holds the
 * model's class names and fields as written by the library's Bundler, so it is encoded and decoded
 * with the Bundler of the app's own copy of the library (loaded from the app's class loader): the
 * host needs no model classes of its own, and whatever library version the app bundles reads its
 * own format.
 */
public final class Bundleable implements Parcelable {
	private static final String BUNDLER = "androidx.car.app.serialization.Bundler";

	private final Bundle mBundle;

	public Bundleable(Bundle bundle) {
		mBundle = bundle;
	}

	public Bundle getBundle() {
		return mBundle;
	}

	/** Bundler.toBundle(model) of the library in appClassLoader */
	public static Bundleable create(Object model, ClassLoader appClassLoader) throws ReflectiveOperationException {
		Method toBundle = Class.forName(BUNDLER, true, appClassLoader).getMethod("toBundle", Object.class);
		return new Bundleable((Bundle)invoke(toBundle, model));
	}

	/** Bundler.fromBundle of the library in appClassLoader: the model object */
	public Object get(ClassLoader appClassLoader) throws ReflectiveOperationException {
		mBundle.setClassLoader(appClassLoader);
		Method fromBundle = Class.forName(BUNDLER, true, appClassLoader).getMethod("fromBundle", Bundle.class);
		return invoke(fromBundle, mBundle);
	}

	private static Object invoke(Method m, Object arg) throws ReflectiveOperationException {
		try {
			return m.invoke(null, arg);
		} catch (InvocationTargetException e) {
			/* BundlerException and the runtime exceptions of a malformed bundle */
			Throwable cause = e.getCause();
			throw new ReflectiveOperationException(cause != null ? cause.toString() : e.toString(), cause != null ? cause : e);
		}
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeBundle(mBundle);
	}

	public static final Parcelable.Creator<Bundleable> CREATOR = new Parcelable.Creator<Bundleable>() {
		@Override
		public Bundleable createFromParcel(Parcel source) {
			return new Bundleable(source.readBundle(Bundleable.class.getClassLoader()));
		}

		@Override
		public Bundleable[] newArray(int size) {
			return new Bundleable[size];
		}
	};
}
