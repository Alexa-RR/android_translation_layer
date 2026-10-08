package android.app;

public class KeyguardManager {
	public boolean inKeyguardRestrictedInputMode() {
		return false;
	}

	public boolean isKeyguardLocked() {
		return false;
	}

	public boolean isKeyguardSecure() {
		return true;
	}

	/* teslalinux-frag 13ca4b5ac136 */
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
