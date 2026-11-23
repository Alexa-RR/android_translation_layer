package android.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Insets;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * teslalinux: the on-screen keyboard as the windows' IME inset source (AOSP ImeInsetsSourceConsumer
 * with InsetsController's IME animation). While the built-in keyboard shows, every window of the
 * process reports WindowInsets.Type.ime() as the keyboard's height at the bottom (the keyboard is a
 * layer at the bottom of the main window, over every window), and the system window insets include
 * it for a window whose soft input mode resizes it (adjustResize, or unspecified with a scroll
 * container), so the framework's fitting decor shrinks its content above the keyboard.
 *
 * A change is animated as AOSP animates the IME (285 ms, the synchronized IME interpolator): each
 * window root gets dispatchWindowInsetsAnimationPrepare, then its insets in the end state
 * (dispatchApplyWindowInsets: the layout is done once, for the end state), then onStart with the
 * bounds, a dispatchWindowInsetsAnimationProgress per frame with the frame's IME inset, and the end.
 */
public final class TlImeInsets {
	/* AOSP InsetsController: ANIMATION_DURATION_SYNC_IME_MS, SYNC_IME_INTERPOLATOR */
	static final long ANIMATION_DURATION_MS = 285;
	static final Interpolator INTERPOLATOR = new PathInterpolator(0.2f, 0f, 0f, 1f);

	private static boolean sVisible;
	private static int sHeight;          // the keyboard's height while it shows
	private static int sAnimatedBottom;  // the inset of the current animation frame
	private static ValueAnimator sAnimator;

	private TlImeInsets() {}

	/** whether the keyboard shows (its end state) */
	static boolean isVisible() {
		return sVisible;
	}

	/** the IME inset at the bottom of a window, in its end state */
	static int bottom() {
		return sVisible ? sHeight : 0;
	}

	/** the IME's height (also while it is hidden: the last height it showed with) */
	static int height() {
		return sHeight;
	}

	/** InputMethodManager: the keyboard was shown (with its height) or hidden */
	public static void set(boolean visible, int height) {
		if (height < 0)
			height = 0;
		if (visible == sVisible && (!visible || height == sHeight))
			return;
		final int from = sAnimator != null ? sAnimatedBottom : bottom();
		if (sAnimator != null) {
			ValueAnimator a = sAnimator;
			sAnimator = null;
			a.cancel();
		}
		sVisible = visible;
		if (visible)
			sHeight = height;
		final int to = bottom();
		final WindowInsetsAnimation animation = new WindowInsetsAnimation(WindowInsets.Type.ime(), INTERPOLATOR, ANIMATION_DURATION_MS);
		final List<WindowInsetsAnimation> running = Collections.singletonList(animation);
		final List<View> roots = roots();
		for (View root : roots)
			root.dispatchWindowInsetsAnimationPrepare(animation);
		// the layout of the end state
		for (View root : roots) {
			WindowInsets insets = root.getRootWindowInsets();
			if (insets != null)
				root.dispatchApplyWindowInsets(insets);
			root.requestLayout();
		}
		final WindowInsetsAnimation.Bounds bounds = new WindowInsetsAnimation.Bounds(Insets.NONE, Insets.of(0, 0, 0, sHeight));
		for (View root : roots)
			root.dispatchWindowInsetsAnimationStart(animation, bounds);
		sAnimatedBottom = from;
		final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
		animator.setDuration(ANIMATION_DURATION_MS);
		animator.setInterpolator(null); // the animation's own fraction; its interpolator is applied below
		animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
			@Override
			public void onAnimationUpdate(ValueAnimator a) {
				float fraction = (Float) a.getAnimatedValue();
				animation.setFraction(fraction);
				sAnimatedBottom = Math.round(from + (to - from) * INTERPOLATOR.getInterpolation(fraction));
				for (View root : roots) {
					if (!root.isAttachedToWindow())
						continue;
					WindowInsets insets = frameInsets(root, sAnimatedBottom);
					if (insets != null)
						root.dispatchWindowInsetsAnimationProgress(insets, running);
				}
			}
		});
		animator.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator a) {
				if (sAnimator == animator)
					sAnimator = null;
				sAnimatedBottom = to;
				for (View root : roots)
					root.dispatchWindowInsetsAnimationEnd(animation);
			}
		});
		sAnimator = animator;
		animator.start();
	}

	/** the window roots of the process: activity and dialog decors, and the app windows */
	private static List<View> roots() {
		ArrayList<View> roots = new ArrayList<>();
		for (Window w : Window.tlLiveWindows()) {
			View decor = w.peekDecorView();
			if (decor != null && decor.isAttachedToWindow() && !roots.contains(decor))
				roots.add(decor);
		}
		for (View v : WindowManagerImpl.tlWindowRoots()) {
			if (v.isAttachedToWindow() && !roots.contains(v))
				roots.add(v);
		}
		return roots;
	}

	/** a root's insets with the IME at the given bottom inset (an animation frame) */
	private static WindowInsets frameInsets(View root, int bottom) {
		WindowInsets end = root.getRootWindowInsets();
		if (end == null)
			return null;
		return new WindowInsets.Builder(end).setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, bottom))
		    .setVisible(WindowInsets.Type.ime(), sVisible || bottom > 0).build();
	}
}
