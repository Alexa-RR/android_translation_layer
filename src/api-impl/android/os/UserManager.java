package android.os;

public class UserManager {
	public boolean isUserUnlocked() {
		return true;
	}
	public static boolean supportsMultipleUsers() {
		return false;
	}

	public long getSerialNumberForUser(UserHandle user) {
		return user.getIdentifier();
	}

	/* teslalinux: UserManager additions */
	/** AOSP: whether the user is running; this device runs only the user of this process. */
	public boolean isUserRunning(UserHandle user) { return user != null && user.getIdentifier() == UserHandle.myUserId(); }
	public boolean isUserRunningOrStopping(UserHandle user) { return isUserRunning(user); }
	public boolean isUserAGoat() { return false; }
	public boolean isDemoUser() { return false; }
	public boolean isManagedProfile() { return false; }
	public boolean isUserForeground() { return true; }
	public static boolean isHeadlessSystemUserMode() { return false; }
}
