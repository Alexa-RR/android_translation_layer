/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.display;

import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the properties of attached displays (AOSP API).
 *
 * teslalinux: the runtime has one physical display, the app's window on the compositor (tacomp
 * streams it to the car), with id Display.DEFAULT_DISPLAY (0), plus the virtual displays the app
 * creates ({@link #createVirtualDisplay}), which get the ids after it, as AOSP's display manager
 * gives them. A virtual display's content goes to the surface it was created with; it is on while it
 * has one. Display listeners are told about added, changed and removed displays, as AOSP's
 * DisplayManagerGlobal dispatches EVENT_DISPLAY_ADDED/CHANGED/REMOVED; changes of the default
 * display come from {@link #tlDefaultDisplayChanged()} (the window being resized).
 */
public final class DisplayManager {
    public static final String DISPLAY_CATEGORY_PRESENTATION = "android.hardware.display.category.PRESENTATION";
    public static final String DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED = "android.hardware.display.category.ALL_INCLUDING_DISABLED";

    public static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 << 0;
    public static final int VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 << 1;
    public static final int VIRTUAL_DISPLAY_FLAG_SECURE = 1 << 2;
    public static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 << 3;
    public static final int VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 << 4;

    private static final int DEFAULT_DISPLAY = 0;

    private static final int EVENT_DISPLAY_ADDED = 1;
    private static final int EVENT_DISPLAY_CHANGED = 2;
    private static final int EVENT_DISPLAY_REMOVED = 3;

    /** Listens for changes in available display devices. */
    public interface DisplayListener {
        void onDisplayAdded(int displayId);

        void onDisplayRemoved(int displayId);

        void onDisplayChanged(int displayId);
    }

    private static final class ListenerRecord {
        final DisplayListener listener;
        final Handler handler;

        ListenerRecord(DisplayListener listener, Handler handler) {
            this.listener = listener;
            this.handler = handler;
        }
    }

    private static final CopyOnWriteArrayList<ListenerRecord> sListeners = new CopyOnWriteArrayList<>();

    /* the virtual displays that exist, by display id (DisplayManagerService's logical displays) */
    private static final Object sLock = new Object();
    private static final LinkedHashMap<Integer, VirtualDisplay> sVirtualDisplays = new LinkedHashMap<>();
    private static int sNextDisplayId = DEFAULT_DISPLAY + 1;

    /** @hide */
    public DisplayManager() {
    }

    /** The display with the id, or null if there is none. */
    public Display getDisplay(int displayId) {
        if (displayId == DEFAULT_DISPLAY)
            return new Display();
        synchronized (sLock) {
            VirtualDisplay vd = sVirtualDisplays.get(displayId);
            return vd != null ? vd.getDisplay() : null;
        }
    }

    /** All displays: the default display, then the virtual displays in the order they were created. */
    public Display[] getDisplays() {
        ArrayList<Display> out = new ArrayList<>();
        out.add(getDisplay(DEFAULT_DISPLAY));
        synchronized (sLock) {
            for (VirtualDisplay vd : sVirtualDisplays.values())
                out.add(vd.getDisplay());
        }
        return out.toArray(new Display[0]);
    }

    /**
     * AOSP: presentation displays are the secondary displays with FLAG_PRESENTATION (virtual
     * displays created with VIRTUAL_DISPLAY_FLAG_PRESENTATION here); the default display is never
     * one of them.
     */
    public Display[] getDisplays(String category) {
        if (DISPLAY_CATEGORY_PRESENTATION.equals(category)) {
            ArrayList<Display> out = new ArrayList<>();
            synchronized (sLock) {
                for (VirtualDisplay vd : sVirtualDisplays.values()) {
                    if ((vd.getDisplay().getFlags() & Display.FLAG_PRESENTATION) != 0)
                        out.add(vd.getDisplay());
                }
            }
            return out.toArray(new Display[0]);
        }
        return getDisplays();
    }

    /**
     * Creates a virtual display. Its content is rendered to the surface; without one the display
     * exists but is off until {@link VirtualDisplay#setSurface} gives it one.
     */
    public VirtualDisplay createVirtualDisplay(String name, int width, int height, int densityDpi, Surface surface, int flags) {
        return createVirtualDisplay(name, width, height, densityDpi, surface, flags, null, null);
    }

    public VirtualDisplay createVirtualDisplay(String name, int width, int height, int densityDpi, Surface surface, int flags,
                                               VirtualDisplay.Callback callback, Handler handler) {
        /* AOSP VirtualDisplayConfig.Builder */
        if (name == null)
            throw new NullPointerException("name must not be null");
        if (width <= 0 || height <= 0 || densityDpi <= 0)
            throw new IllegalArgumentException("width, height, and densityDpi must be greater than 0");
        /* VirtualDisplayAdapter: device flags of the virtual display, as the logical display reports them */
        int displayFlags = 0;
        if ((flags & VIRTUAL_DISPLAY_FLAG_SECURE) != 0)
            displayFlags |= Display.FLAG_SECURE;
        if ((flags & VIRTUAL_DISPLAY_FLAG_PUBLIC) == 0)
            displayFlags |= Display.FLAG_PRIVATE;
        if ((flags & VIRTUAL_DISPLAY_FLAG_PRESENTATION) != 0)
            displayFlags |= Display.FLAG_PRESENTATION;
        Handler callbackHandler = callback == null ? null
                : handler != null ? handler : new Handler(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper());
        VirtualDisplay vd;
        synchronized (sLock) {
            int id = sNextDisplayId++;
            Display display = Display.tlCreateVirtual(id, name, width, height, densityDpi, displayFlags, surface != null);
            vd = new VirtualDisplay(this, display, surface, callback, callbackHandler);
            sVirtualDisplays.put(id, vd);
        }
        dispatch(EVENT_DISPLAY_ADDED, vd.getDisplay().getDisplayId());
        return vd;
    }

    /* VirtualDisplay.setSurface: the display turns on or off; its callback is resumed or paused */
    void tlSetVirtualDisplaySurface(final VirtualDisplay vd, boolean hadSurface, boolean hasSurface) {
        vd.getDisplay().tlSetOn(hasSurface);
        dispatch(EVENT_DISPLAY_CHANGED, vd.getDisplay().getDisplayId());
        final VirtualDisplay.Callback callback = vd.getCallback();
        if (callback == null || hadSurface == hasSurface)
            return;
        final boolean resumed = hasSurface;
        vd.getHandler().post(new Runnable() {
            @Override
            public void run() {
                if (resumed)
                    callback.onResumed();
                else
                    callback.onPaused();
            }
        });
    }

    void tlResizeVirtualDisplay(VirtualDisplay vd, int width, int height, int densityDpi) {
        vd.getDisplay().tlResize(width, height, densityDpi);
        dispatch(EVENT_DISPLAY_CHANGED, vd.getDisplay().getDisplayId());
    }

    /* VirtualDisplay.release: the display is removed. Its callback is not told: onStopped is for a
     * display the system stops, and nothing stops a virtual display here but its own release. */
    void tlReleaseVirtualDisplay(VirtualDisplay vd) {
        int id = vd.getDisplay().getDisplayId();
        synchronized (sLock) {
            if (sVirtualDisplays.get(id) != vd)
                return;
            sVirtualDisplays.remove(id);
        }
        vd.getDisplay().tlInvalidate();
        dispatch(EVENT_DISPLAY_REMOVED, id);
    }

    public void registerDisplayListener(DisplayListener listener, Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        for (ListenerRecord r : sListeners) {
            if (r.listener == listener)
                return;
        }
        Handler h = handler != null ? handler
                : new Handler(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper());
        sListeners.add(new ListenerRecord(listener, h));
    }

    public void unregisterDisplayListener(DisplayListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        for (ListenerRecord r : sListeners) {
            if (r.listener == listener)
                sListeners.remove(r);
        }
    }

    private static void dispatch(final int event, final int displayId) {
        for (final ListenerRecord r : sListeners) {
            r.handler.post(new Runnable() {
                @Override
                public void run() {
                    switch (event) {
                        case EVENT_DISPLAY_ADDED:
                            r.listener.onDisplayAdded(displayId);
                            break;
                        case EVENT_DISPLAY_CHANGED:
                            r.listener.onDisplayChanged(displayId);
                            break;
                        case EVENT_DISPLAY_REMOVED:
                            r.listener.onDisplayRemoved(displayId);
                            break;
                    }
                }
            });
        }
    }

    /** @hide the default display changed (size, density): EVENT_DISPLAY_CHANGED to every listener */
    public static void tlDefaultDisplayChanged() {
        dispatch(EVENT_DISPLAY_CHANGED, DEFAULT_DISPLAY);
    }
}
