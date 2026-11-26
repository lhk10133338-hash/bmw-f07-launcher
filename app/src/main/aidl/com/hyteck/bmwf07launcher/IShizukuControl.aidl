package com.hyteck.bmwf07launcher;

import android.view.MotionEvent;
import android.view.Surface;

/**
 * v3.4: runs inside a Shizuku user service (adb shell UID, 2000) so that the two virtual displays
 * of [com.hyteck.bmwf07launcher.engine.VirtualDisplayEngine] can be created and touched.
 *
 * Two privileges are needed and an ordinary app has neither:
 *  - `ADD_TRUSTED_DISPLAY` — only a trusted virtual display may host another app's activity. A
 *    display made by the app itself refuses every launch (`SecurityException`), so the display is
 *    created here, where the flag is allowed.
 *  - `INJECT_EVENTS` — the shell UID may push a touch into a display that is not the default one,
 *    exactly like `adb shell input`.
 */
interface IShizukuControl {
    /**
     * Creates a virtual display showing [surface] and returns its display id, or -1 when even the
     * plain flags were refused. Trusted/public flag combinations are tried from strongest to
     * weakest.
     */
    int createDisplay(String name, int width, int height, int dpi, in Surface surface);

    /** Resizes an existing display (a ratio change or a new per-pane density). */
    boolean resizeDisplay(int displayId, int width, int height, int dpi);

    /** Attaches a new surface (the launcher's SurfaceView came back after a pause). */
    boolean setDisplaySurface(int displayId, in Surface surface);

    /** Releases the display and the app running on it. */
    void releaseDisplay(int displayId);

    /**
     * Freezes (false) or resumes (true) the app on this pane's display without releasing it,
     * the way CarCar Launcher pauses the pane that fullscreen left behind.
     */
    boolean setDisplayState(int displayId, boolean on);

    /**
     * Injects [event] into the display [displayId]. The coordinates in [event] are display
     * pixels; the launcher's SurfaceView is exactly the size of the display, so no scaling is
     * needed. Multi-touch pointers are carried along unchanged.
     */
    void injectTouch(int displayId, in MotionEvent event);

    /** True when this service found an InputManager and can inject touches. */
    boolean isReady();

    /** Shuts the user service down (the launcher asks for this when it releases its displays). */
    void destroy();
}
