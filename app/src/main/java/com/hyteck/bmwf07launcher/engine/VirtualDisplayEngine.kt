package com.hyteck.bmwf07launcher.engine

import android.app.ActivityOptions
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.hyteck.bmwf07launcher.data.Settings

/**
 * Renders both apps inside the launcher window by giving each pane its own
 * virtual display. Each pane can use its own density so a 2:8 pane does not
 * show a cropped phone-sized layout.
 *
 * Some firmware does not allow third-party apps to create a public virtual
 * display or to launch other apps onto it. Failures are reported through
 * [onMessage] and the launcher can fall back to [SystemSplitEngine].
 */
class VirtualDisplayEngine(
    private val context: Context,
    private val leftSurface: SurfaceView,
    private val rightSurface: SurfaceView,
    private val settings: Settings,
    private val onMessage: (String) -> Unit
) : SplitEngine {

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val displays = HashMap<Side, VirtualDisplay>()
    private val displayMetrics = HashMap<Side, Triple<Int, Int, Int>>()
    private val launchedPackages = HashMap<Side, String>()
    private val densities = HashMap<Side, Int>()
    private var errorReported = false
    private var launchErrorReported = false

    override val inline: Boolean = true

    init {
        densities[Side.LEFT] = settings.leftDpi
        densities[Side.RIGHT] = settings.rightDpi
        register(Side.LEFT, leftSurface)
        register(Side.RIGHT, rightSurface)
    }

    private fun register(side: Side, view: SurfaceView) {
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                ensureDisplay(side)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                ensureDisplay(side)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                displays.remove(side)?.release()
                displayMetrics.remove(side)
            }
        })
    }

    private fun surfaceOf(side: Side): SurfaceView =
        if (side == Side.LEFT) leftSurface else rightSurface

    private fun densityOf(side: Side): Int =
        densities[side] ?: if (side == Side.LEFT) settings.leftDpi else settings.rightDpi

    /**
     * Creates (or recreates after a size/density change) the virtual display
     * that is drawn into the pane's SurfaceView and relaunches its app.
     */
    private fun ensureDisplay(side: Side) {
        val view = surfaceOf(side)
        val surface = view.holder.surface
        if (!surface.isValid) return
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return

        val density = densityOf(side)
        val existing = displays[side]
        val metrics = displayMetrics[side]
        if (existing != null && metrics != null &&
            metrics.first == width && metrics.second == height && metrics.third == density
        ) {
            return
        }
        existing?.release()
        displays.remove(side)
        displayMetrics.remove(side)

        var display = createDisplay(side, width, height, density, surface)
        if (display == null && density != 160) {
            // Some firmware ignores unusual densities; retry at the native density.
            display = createDisplay(side, width, height, 160, surface)
        }
        if (display == null) {
            if (!errorReported) {
                errorReported = true
                onMessage("가상 화면을 만들 수 없습니다. 설정에서 '시스템 분할' 방식을 선택해 주세요.")
            }
            return
        }
        errorReported = false
        displays[side] = display
        displayMetrics[side] = Triple(width, height, density)
        launchedPackages[side]?.let { pkg ->
            launchOnDisplay(pkg, display.display.displayId)
        }
    }

    @SuppressLint("WrongConstant")
    private fun createDisplay(
        side: Side,
        width: Int,
        height: Int,
        density: Int,
        surface: android.view.Surface
    ): VirtualDisplay? {
        val name = "bmw-f07-" + (if (side == Side.LEFT) "left" else "right")
        try {
            return displayManager.createVirtualDisplay(
                name,
                width,
                height,
                density,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH
            )
        } catch (e: SecurityException) {
            // Public virtual displays normally need a system permission.
            return try {
                displayManager.createVirtualDisplay(name, width, height, density, surface, 0)
            } catch (inner: Exception) {
                null
            }
        } catch (e: Exception) {
            return null
        }
    }

    override fun launch(side: Side, packageName: String) {
        launchedPackages[side] = packageName
        val display = displays[side]
        if (display == null) {
            // Not laid out yet — will be launched from ensureDisplay().
            ensureDisplay(side)
            return
        }
        launchOnDisplay(packageName, display.display.displayId)
    }

    private fun launchOnDisplay(packageName: String, displayId: Int) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            onMessage("앱을 찾을 수 없습니다: $packageName")
            return
        }
        intent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        val options = ActivityOptions.makeBasic()
        options.setLaunchDisplayId(displayId)
        try {
            context.startActivity(intent, options.toBundle())
        } catch (e: Exception) {
            if (!launchErrorReported) {
                launchErrorReported = true
                onMessage(
                    "이 기기에서는 앱을 가상 화면에 띄울 수 없습니다. '시스템 분할' 방식을 사용해 주세요."
                )
            }
        }
    }

    override fun close(side: Side) {
        launchedPackages.remove(side)
        displays.remove(side)?.release()
        displayMetrics.remove(side)
    }

    override fun setDensity(side: Side, dpi: Int) {
        densities[side] = dpi.coerceIn(120, 480)
        displays.remove(side)?.release()
        displayMetrics.remove(side)
        ensureDisplay(side)
    }

    override fun resize(side: Side) {
        displays.remove(side)?.release()
        displayMetrics.remove(side)
        ensureDisplay(side)
    }

    override fun release() {
        for (display in displays.values) {
            display.release()
        }
        displays.clear()
        displayMetrics.clear()
        launchedPackages.clear()
    }

    companion object {
        // DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH (hidden constant, AOSP value 1 << 6).
        private const val FLAG_SUPPORTS_TOUCH = 64
    }
}
