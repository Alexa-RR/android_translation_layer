package android.app;

public class KeyguardManager {
	public boolean inKeyguardRestrictedInputMode() {
		return false;
	}

	public boolean isKeyguardLocked() {
		return false;
	}

	/**
	 * AOSP: whether the keyguard asks for a PIN, pattern, password or SIM to unlock. This platform
	 * has no lock screen (no keyguard, no credential), so it never does; consistent with
	 * isDeviceSecure() and createConfirmDeviceCredentialIntent() returning null.
	 */
	public boolean isKeyguardSecure() {
		return false;
	}


	/* teslalinux: KeyguardManager additions */
	public static abstract class KeyguardDismissCallback {
		public void onDismissError() {}
		public void onDismissSucceeded() {}
		public void onDismissCancelled() {}
	}
	public boolean isDeviceSecure() { return false; }
	public boolean isDeviceLocked() { return false; }
	public android.content.Intent createConfirmDeviceCredentialIntent(CharSequence title, CharSequence description) { return null; }
	public void requestDismissKeyguard(Activity activity, KeyguardDismissCallback callback) { if (callback != null) callback.onDismissSucceeded(); }
}
