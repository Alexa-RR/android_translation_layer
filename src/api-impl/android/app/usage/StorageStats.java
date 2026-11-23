/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app.usage;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

/**
 * Storage statistics for a UID, package, or {@link UserHandle} on a single
 * storage volume (AOSP main). StorageStatsManager fills the fields from the files on disk.
 *
 * @see StorageStatsManager
 */
public final class StorageStats implements Parcelable {
	/** @hide */ public long codeBytes;
	/** @hide */ public long dataBytes;
	/** @hide */ public long cacheBytes;
	/** @hide */ public long apkBytes;
	/** @hide */ public long libBytes;
	/** @hide */ public long dmBytes;
	/** @hide */ public long dexoptBytes;
	/** @hide */ public long curProfBytes;
	/** @hide */ public long refProfBytes;
	/** @hide */ public long externalCacheBytes;

	/** Dexopt artifacts (odex, vdex, art files) of the app's APKs. */
	public static final int APP_DATA_TYPE_FILE_TYPE_DEXOPT_ARTIFACT = 0;
	/** Reference profiles of the app. */
	public static final int APP_DATA_TYPE_FILE_TYPE_REFERENCE_PROFILE = 1;
	/** Current profiles of the app. */
	public static final int APP_DATA_TYPE_FILE_TYPE_CURRENT_PROFILE = 2;
	/** The APK files. */
	public static final int APP_DATA_TYPE_FILE_TYPE_APK = 3;
	/** Dex metadata (.dm) files. */
	public static final int APP_DATA_TYPE_FILE_TYPE_DM = 4;
	/** The unpacked native libraries. */
	public static final int APP_DATA_TYPE_LIB = 5;

	/**
	 * Return the size of app. This includes {@code APK} files, optimized
	 * compiler output, and unpacked native libraries.
	 * <p>
	 * If the primary external/shared storage is hosted on this storage device,
	 * then this includes files stored under {@link Context#getObbDir()}.
	 * <p>
	 * Code is shared between all users on a multiuser device.
	 */
	public long getAppBytes() {
		return codeBytes;
	}

	/**
	 * Return the size of the specified data type. This includes files stored under
	 * application code path.
	 * <p>
	 * If there is more than one package inside a uid, the return represents the aggregated
	 * stats when query StorageStat for package or uid.
	 * The data  is not collected and the return defaults to 0 when query StorageStats for user.
	 */
	public long getAppBytesByDataType(int dataType) {
		switch (dataType) {
			case APP_DATA_TYPE_FILE_TYPE_DEXOPT_ARTIFACT: return dexoptBytes;
			case APP_DATA_TYPE_FILE_TYPE_REFERENCE_PROFILE: return refProfBytes;
			case APP_DATA_TYPE_FILE_TYPE_CURRENT_PROFILE: return curProfBytes;
			case APP_DATA_TYPE_FILE_TYPE_APK: return apkBytes;
			case APP_DATA_TYPE_LIB: return libBytes;
			case APP_DATA_TYPE_FILE_TYPE_DM: return dmBytes;
			default: return 0;
		}
	}

	/**
	 * Return the size of all data. This includes files stored under
	 * {@link Context#getDataDir()}, {@link Context#getCacheDir()},
	 * {@link Context#getCodeCacheDir()}.
	 * <p>
	 * If the primary external/shared storage is hosted on this storage device,
	 * then this includes files stored under
	 * {@link Context#getExternalFilesDir(String)},
	 * {@link Context#getExternalCacheDir()}, and
	 * {@link Context#getExternalMediaDirs()}.
	 */
	public long getDataBytes() {
		return dataBytes;
	}

	/**
	 * Return the size of all cached data. This includes files stored under
	 * {@link Context#getCacheDir()} and {@link Context#getCodeCacheDir()}.
	 * <p>
	 * If the primary external/shared storage is hosted on this storage device,
	 * then this includes files stored under
	 * {@link Context#getExternalCacheDir()}.
	 */
	public long getCacheBytes() {
		return cacheBytes;
	}

	/**
	 * Return the size of all cached data in the primary external/shared storage.
	 * This includes files stored under
	 * {@link Context#getExternalCacheDir()}.
	 */
	public long getExternalCacheBytes() {
		return externalCacheBytes;
	}

	/** {@hide} */
	public StorageStats() {
	}

	/** {@hide} */
	public StorageStats(Parcel in) {
		this.codeBytes = in.readLong();
		this.dataBytes = in.readLong();
		this.cacheBytes = in.readLong();
		this.dexoptBytes = in.readLong();
		this.refProfBytes = in.readLong();
		this.curProfBytes = in.readLong();
		this.apkBytes = in.readLong();
		this.libBytes = in.readLong();
		this.dmBytes = in.readLong();
		this.externalCacheBytes = in.readLong();
	}

	/** {@hide} AOSP StorageStatsService: the stats of a uid or a user are the sum of its packages'. */
	public void add(StorageStats other) {
		codeBytes += other.codeBytes;
		dataBytes += other.dataBytes;
		cacheBytes += other.cacheBytes;
		apkBytes += other.apkBytes;
		libBytes += other.libBytes;
		dmBytes += other.dmBytes;
		dexoptBytes += other.dexoptBytes;
		curProfBytes += other.curProfBytes;
		refProfBytes += other.refProfBytes;
		externalCacheBytes += other.externalCacheBytes;
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeLong(codeBytes);
		dest.writeLong(dataBytes);
		dest.writeLong(cacheBytes);
		dest.writeLong(dexoptBytes);
		dest.writeLong(refProfBytes);
		dest.writeLong(curProfBytes);
		dest.writeLong(apkBytes);
		dest.writeLong(libBytes);
		dest.writeLong(dmBytes);
		dest.writeLong(externalCacheBytes);
	}

	public static final Creator<StorageStats> CREATOR = new Creator<StorageStats>() {
		@Override
		public StorageStats createFromParcel(Parcel in) {
			return new StorageStats(in);
		}

		@Override
		public StorageStats[] newArray(int size) {
			return new StorageStats[size];
		}
	};
}
