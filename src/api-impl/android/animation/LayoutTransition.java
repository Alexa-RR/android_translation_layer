package android.animation;

public class LayoutTransition {

	public void enableTransitionType(int transitionType) {}

	public void setStartDelay(int transitionType, long startDelay) {}

	public void setAnimator(int transitionType, Animator animator) {}

	public void setDuration(long duration) {}

	public Animator getAnimator(int transitionType) { return null; }

	public void setDuration(int transitionType, long duration) {}

	public void setInterpolator(int transitionType, TimeInterpolator interpolator) {}

	/* teslalinux: AOSP LayoutTransition listener registration. ATL does not run layout transitions
	 * (views are laid out by GTK without animated appearance/disappearance), so the listeners are
	 * kept but never invoked, exactly as for a transition type that is disabled. */
	public interface TransitionListener {
		public void startTransition(LayoutTransition transition, android.view.ViewGroup container, android.view.View view, int transitionType);
		public void endTransition(LayoutTransition transition, android.view.ViewGroup container, android.view.View view, int transitionType);
	}

	private java.util.ArrayList<TransitionListener> mListeners;

	public void addTransitionListener(TransitionListener listener) {
		if (mListeners == null) {
			mListeners = new java.util.ArrayList<TransitionListener>();
		}
		mListeners.add(listener);
	}

	public void removeTransitionListener(TransitionListener listener) {
		if (mListeners == null) {
			return;
		}
		mListeners.remove(listener);
	}

	public java.util.List<TransitionListener> getTransitionListeners() {
		return mListeners;
	}
}
