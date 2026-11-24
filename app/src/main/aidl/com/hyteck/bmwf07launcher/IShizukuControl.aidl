package com.hyteck.bmwf07launcher;

import android.view.MotionEvent;

/**
 * v3.4: runs inside a Shizuku user service (adb shell UID, 2000) so that the two virtual displays
 * of [com.hyteck.bmwf07launcher.engine.VirtualDisplayEngine] can receive touch.
 *
 * A normal app may not inject input events at all (`INJECT_EVENTS` is a signature permission); the
 * shell UID holds it, which is why `input tap` works from adb. Here the very same thing happens
 * from the launcher's own process pair: the launcher parcels a touch it received on a pane's
 * SurfaceView, and the shell-side service injects it into the display that pane shows.
 */
interface IShizukuControl {
    /**
     * Injects [event] into the display [displayId]. The coordinates in [event] are display
     * pixels; the launcher's SurfaceView is exactly the size of the display, so no scaling is
     * needed. Multi-touch pointers are carried along unchanged.
     */
    void injectTouch(int displayId, in MotionEvent event);

    /** True when the previous [injectTouch] had a usable InputManager. Diagnostics only. */
    boolean isReady();

    /** Shuts the user service down (the launcher asks for this when it releases its displays). */
    void destroy();
}
