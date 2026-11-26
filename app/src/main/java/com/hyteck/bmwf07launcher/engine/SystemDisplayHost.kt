package com.hyteck.bmwf07launcher.engine

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface

/**
 * Displays created by the launcher process itself while it holds the platform permissions of the
 * SystemUID build (v4.0) — the route CarCar Launcher takes.
 *
 * Read out of the original APK (`La8/c0`, `Lw7/g`):
 *
 *  1. `createVirtualDisplay(name, w, h, dpi, null, flags)` — the surface is attached afterwards,
 *     so the display exists even before the pane's SurfaceView has a buffer.
 *  2. flags `0xD09` = `PUBLIC | OWN_CONTENT_ONLY | DESTROY_CONTENT_ON_REMOVAL | TRUSTED |
 *     OWN_DISPLAY_GROUP`. `TRUSTED` is what allows another app's activity to be started on the
 *     display; `OWN_DISPLAY_GROUP` keeps the pane out of the main screen's display group.
 *  3. `IWindowManager.dontOverrideDisplayInfo(displayId)` — the firmware keeps our metrics.
 *  4. `VirtualDisplay.resize(w, h, dpi)`, then `setSurface(surface)` — **v4.0's order, which is
 *     what actually puts a picture on this unit's panes.** v4.1 swapped it (surface first, size
 *     after) and both panes went blank; see [VirtualDisplayEngine]'s header.
 *  5. A hidden pane is frozen with `VirtualDisplay.setDisplayState(false)` instead of being
 *     released, so its app survives a trip to fullscreen and back. That fullscreen toggle is the
 *     only place the display state is touched (v4.1 also forced `setDisplayState(true)` on create
 *     and on every resume, and the panes stopped drawing).
 *
 * On the car unit the launcher process runs as the platform UID, so no helper is involved: this
 * host is created in-process by [VirtualDisplayEngine.hostFor] when
 * [Privileges.canCreateTrustedDisplay] is true.
 */
class SystemDisplayHost(private val displayManager: DisplayManager) : DisplayHost {

    private val displays = HashMap<Int, VirtualDisplay>()

    override fun create(name: String, width: Int, height: Int, dpi: Int, surface: Surface): Int {
        if (width <= 0 || height <= 0) return -1
        for (flags in FLAG_SETS) {
            val created = try {
                displayManager.createVirtualDisplay(name, width, height, dpi, null, flags)
            } catch (t: Throwable) {
                null
            } ?: continue
            val id = created.display?.displayId ?: -1
            if (id < 0) {
                runCatching { created.release() }
                continue
            }
            displays[id] = created
            WindowService.dontOverrideDisplayInfo(id)
            // v4.2: v4.0's order, unchanged. The rectangle is fixed first and the output surface
            // is attached last, so the display never sits on the size it was created with.
            runCatching { created.resize(width, height, dpi) }
            runCatching { created.surface = surface }
            return id
        }
        return -1
    }

    override fun resize(displayId: Int, width: Int, height: Int, dpi: Int): Boolean {
        val display = displays[displayId] ?: return false
        // A display the platform already removed cannot be resized.
        if (display.display == null) return false
        return try {
            display.resize(width, height, dpi)
            WindowService.dontOverrideDisplayInfo(displayId)
            true
        } catch (t: Throwable) {
            false
        }
    }

    override fun setSurface(displayId: Int, surface: Surface?) {
        val display = displays[displayId] ?: return
        if (display.display == null) return
        try {
            // The same Surface object is a no-op on the platform side (`VirtualDisplay.setSurface`
            // returns early when the surface is unchanged), so re-attaching it costs nothing.
            display.surface = surface
        } catch (t: Throwable) {
            // A refused surface is retried by the next pass of the engine.
        }
    }

    override fun setState(displayId: Int, on: Boolean) {
        WindowService.setDisplayState(displays[displayId], on)
    }

    override fun release(displayId: Int) {
        displays.remove(displayId)?.let { display ->
            try {
                display.release()
            } catch (t: Throwable) {
                // Already gone.
            }
        }
    }

    companion object {
        // DisplayManager.VIRTUAL_DISPLAY_FLAG_*
        private const val FLAG_PUBLIC = 1               // 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6

        /**
         * CarCar's exact flag set first (3337 = 0xD09), then progressively weaker sets for a
         * firmware that refuses one of the newer flags.
         */
        private val FLAG_SETS = intArrayOf(
            FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_DESTROY_CONTENT_ON_REMOVAL or
                FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP,
            FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_DESTROY_CONTENT_ON_REMOVAL or
                FLAG_TRUSTED,
            FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH,
            FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH,
            FLAG_SUPPORTS_TOUCH,
            0
        )
    }
}
