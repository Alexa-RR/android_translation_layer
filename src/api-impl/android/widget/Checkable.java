package android.widget;

public interface Checkable {

	public void setChecked(boolean checked);

	public boolean isChecked();

	/** AOSP: flips the checked state. */
	void toggle();
}
