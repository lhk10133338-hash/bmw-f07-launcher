package com.hyteck.bmwf07launcher.engine

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface
import com.hyteck.bmwf07launcher.IShizukuControl

/**
 * Where a pane's virtual display comes from (v3.4).
 *
 * The engine only needs four operations; who performs them decides whether the apps may actually
 * be placed on that display:
 *
 *  - [ShizukuDisplayHost] — the display is made by the shell-side service, which may set
 *    `VIRTUAL_DISPLAY_FLAG_TRUSTED`. Only a trusted display accepts another app's activity, so
 *    this is the route that works on a stock Android.
 *  - [LocalDisplayHost] — the display is made by the launcher itself, as every version before 3.4
 *    did. Kept as a fallback for a head unit whose firmware allows launches on its own virtual
 *    displays (the MEKEDE unit did in 2.x), and for the case where Shizuku is not installed.
 */
interface DisplayHost {
    /** Creates a display rendering into [surface]; returns its display id or -1. */
    fun create(name: String, width: Int, height: Int, dpi: Int, surface: Surface): Int

    /** Resizes an existing display; false when it has to be rebuilt. */
    fun resize(displayId: Int, width: Int, height: Int, dpi: Int): Boolean

    fun setSurface(displayId: Int, surface: Surface?)

    fun release(displayId: Int)
}

/** Displays created by the Shizuku user service (trusted, so apps can be launched on them). */
class ShizukuDisplayHost(private val control: IShizukuControl) : DisplayHost {

    override fun create(name: String, width: Int, height: Int, dpi: Int, surface: Surface): Int =
        try {
            control.createDisplay(name, width, height, dpi, surface)
        } catch (e: Exception) {
            -1
        }

    override fun resize(displayId: Int, width: Int, height: Int, dpi: Int): Boolean = try {
        control.resizeDisplay(displayId, width, height, dpi)
    } catch (e: Exception) {
        false
    }

    override fun setSurface(displayId: Int, surface: Surface?) {
        try {
            control.setDisplaySurface(displayId, surface)
        } catch (e: Exception) {
            // The service went away; the engine rebuilds its displays on the next resume.
        }
    }

    override fun release(displayId: Int) {
        try {
            control.releaseDisplay(displayId)
        } catch (e: Exception) {
            // Already gone.
        }
    }
}

/** Displays created inside the launcher process (no trusted flag, no shell permission). */
class LocalDisplayHost(private val displayManager: DisplayManager) : DisplayHost {

    private val displays = HashMap<Int, VirtualDisplay>()

    override fun create(name: String, width: Int, height: Int, dpi: Int, surface: Surface): Int {
        for (flags in FLAG_SETS) {
            try {
                val display = displayManager.createVirtualDisplay(
                    name,
                    width,
                    height,
                    dpi,
                    surface,
                    flags
                )
                if (display != null) {
                    val id = display.display.displayId
                    displays[id] = display
                    return id
                }
            } catch (e: Exception) {
                // Refused flag set: try the weaker one.
            }
        }
        return -1
    }

    override fun resize(displayId: Int, width: Int, height: Int, dpi: Int): Boolean {
        val display = displays[displayId] ?: return false
        return try {
            display.resize(width, height, dpi)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun setSurface(displayId: Int, surface: Surface?) {
        val display = displays[displayId] ?: return
        try {
            display.surface = surface
        } catch (e: Exception) {
            // A dead display is rebuilt by the engine.
        }
    }

    override fun release(displayId: Int) {
        displays.remove(displayId)?.let { display ->
            try {
                display.release()
            } catch (e: Exception) {
                // Already gone.
            }
        }
    }

    companion object {
        // DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC / _SUPPORTS_TOUCH / _OWN_CONTENT_ONLY
        // (hidden constants). TRUSTED is tried by the shell side only.
        private const val FLAG_PUBLIC = 1
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6

        private val FLAG_SETS = intArrayOf(
            FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH,
            FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH or FLAG_OWN_CONTENT_ONLY,
            FLAG_SUPPORTS_TOUCH,
            0
        )
    }
}
