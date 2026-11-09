package android.view;
import android.content.res.AssetManager;
import android.content.res.Configuration;

import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import java.lang.reflect.InvocationTargetException;

public class ContextThemeWrapper extends ContextWrapper {
	private LayoutInflater layout_inflater = null;
	/**
	 * While not being part of the official Android API, some applications use it to reset/reload the theme in the context.
	 */
	@UnsupportedAppUsage
	private Resources.Theme mTheme = null;
	/**
	 * While not being part of the official Android API, some application use it to get the theme resource ID.
	 */
	@UnsupportedAppUsage
	private int mThemeResource;

	public ContextThemeWrapper(Context base) {
		super(base);
	}

	public ContextThemeWrapper(Context context, int themeResId) {
		super(context);
		mThemeResource = themeResId;
	}

	public ContextThemeWrapper(Context context, Resources.Theme theme) {
		super(context);
		mTheme = context.getResources().newTheme();
		mTheme.setTo(theme);
	}

	@Override
	public Object getSystemService(String name) {
		if ("layout_inflater".equals(name)) {
			if (layout_inflater == null) {
				layout_inflater = LayoutInflater.from(this.getBaseContext()).cloneInContext(this);
			}
			return layout_inflater;
		}
		return super.getSystemService(name);
	}

	@Override
	public Object getSystemService(Class<?> serviceClass) throws InstantiationException, IllegalAccessException, InvocationTargetException {
		if (serviceClass == LayoutInflater.class) {
			if (layout_inflater == null) {
				layout_inflater = LayoutInflater.from(this.getBaseContext()).cloneInContext(this);
			}
			return layout_inflater;
		}
		return super.getSystemService(serviceClass);
	}

	@Override
	public void setTheme(int resid) {
		mThemeResource = resid;
		boolean first = mTheme == null;
		if (first) {
			mTheme = getResources().newTheme();
			mTheme.setTo(getBaseContext().getTheme());
		}
		if (resid != 0) {
			this.onApplyThemeResource(mTheme, resid, first);
		}
	}

	@Override
	public Resources.Theme getTheme() {
		if (mTheme == null) {
			setTheme(mThemeResource);
		}
		return mTheme;
	}

	protected void onApplyThemeResource(Resources.Theme theme, int resId, boolean first) {
		theme.applyStyle(resId, true);
	}

	// ---- teslalinux (vcore): AOSP override configuration (AppCompat applies its night mode / locale this way) ----
	private Configuration tlOverrideConfiguration;
	private Resources tlOverrideResources;
	private boolean tlResourcesRequested;

	/**
	 * AOSP: the resources of this context use the base configuration with overrideConfiguration
	 * applied. Must be called before getResources()/getAssets(), and only once.
	 */
	public void applyOverrideConfiguration(Configuration overrideConfiguration) {
		if (tlResourcesRequested) {
			throw new IllegalStateException("getResources() or getAssets() has already been called");
		}
		if (tlOverrideConfiguration != null) {
			throw new IllegalStateException("Override configuration has already been set");
		}
		tlOverrideConfiguration = new Configuration(overrideConfiguration);
	}

	/** AOSP: the configuration applied by applyOverrideConfiguration, null when none */
	public Configuration getOverrideConfiguration() {
		return tlOverrideConfiguration;
	}

	/** AOSP getResourcesInternal: the override configuration's resources, else the base context's */
	@Override
	public Resources getResources() {
		tlResourcesRequested = true;
		if (tlOverrideConfiguration == null)
			return super.getResources();
		if (tlOverrideResources == null)
			tlOverrideResources = createConfigurationContext(tlOverrideConfiguration).getResources();
		return tlOverrideResources;
	}

	@Override
	public AssetManager getAssets() {
		// AOSP: the assets of getResources(), so an override configuration is honoured here too
		return getResources().getAssets();
	}
}
