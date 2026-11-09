package android.content.pm;

import android.content.IntentFilter;
import android.graphics.drawable.Drawable;

public class ResolveInfo {
	public ActivityInfo activityInfo = new ActivityInfo();
	public ServiceInfo serviceInfo = new ServiceInfo();
	public IntentFilter filter = new IntentFilter();
	public int priority = -500;

	public Drawable loadIcon(PackageManager pm) {
		Drawable icon = activityInfo.loadIcon(pm);
		if (icon == null) {
			icon = new Drawable();
		}
		return icon;
	}

	/** AOSP: the matched filter's label, else the component's label (which falls back to the app's, then its name). */
	public CharSequence loadLabel(PackageManager pm) {
		if (nonLocalizedLabel != null) {
			return nonLocalizedLabel;
		}
		ComponentInfo ci = tlGetComponentInfo();
		if (labelRes != 0 && ci != null) {
			CharSequence label = pm.getText(ci.packageName, labelRes, ci.applicationInfo);
			if (label != null) {
				return label.toString().trim();
			}
		}
		CharSequence data = ci != null ? ci.loadLabel(pm) : null;
		if (data != null) {
			data = data.toString().trim();
		}
		return data;
	}

	public static class DisplayNameComparator {

		public DisplayNameComparator(PackageManager pm) {}
	}

	/** AOSP: the provider that matched (queryIntentContentProviders), else null. */
	public ProviderInfo providerInfo;
	/** AOSP: the label of the matched filter, if it has one. */
	public int labelRes;
	public CharSequence nonLocalizedLabel;
	/** AOSP: the icon of the matched filter, if it has one. */
	public int icon;

	/**
	 * The component this resolution is for: the provider or service when one was resolved, else the
	 * activity (ATL keeps an ActivityInfo/ServiceInfo object in every ResolveInfo, so the one with a
	 * class name is the resolved one).
	 */
	ComponentInfo tlGetComponentInfo() {
		if (providerInfo != null)
			return providerInfo;
		if (serviceInfo != null && serviceInfo.name != null)
			return serviceInfo;
		return activityInfo;
	}
}
