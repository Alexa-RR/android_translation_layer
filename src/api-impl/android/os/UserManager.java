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
	public boolean isUserRunning(UserHandle user) { return true; }
	public boolean isUserRunningOrStopping(UserHandle user) { return true; }
	public boolean isUserAGoat() { return false; }
	public boolean isDemoUser() { return false; }
	public boolean isManagedProfile() { return false; }
	public boolean isUserForeground() { return true; }
	public static boolean isHeadlessSystemUserMode() { return false; }
}
