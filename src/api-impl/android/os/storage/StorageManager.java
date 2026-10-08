package android.os.storage;

import java.io.File;

public class StorageManager {
	public static final java.util.UUID UUID_DEFAULT = java.util.UUID.fromString("41217664-9172-527a-b3d5-edabb50a7d69");
	public java.util.UUID getUuidForPath(java.io.File path) { return UUID_DEFAULT; }
	public boolean isAllocationSupported(java.io.FileDescriptor fd) { return false; }
	public StorageVolume[] getVolumeList() {
		StorageVolume[] sVolumes = {new StorageVolume()};
		return sVolumes;
	}

	public StorageVolume getStorageVolume(File file) { return null; }
}
