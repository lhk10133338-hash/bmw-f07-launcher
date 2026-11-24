package com.hyteck.bmwf07launcher.engine

import android.graphics.Rect

enum class Side { LEFT, RIGHT }

/**
 * Abstraction over the two split-screen execution engines.
 *
 * [inline] is true when both apps are rendered inside the launcher window
 * (virtual display); false when the launcher hands the screen over to the
 * system multi-window implementation.
 *
 * The launcher calls [applyBounds] with the measured pane rectangles after every layout
 * change. Engines that resize live honour the call immediately; the system engine only acts
 * on [commit] (divider released, preset applied) because moving another app's window is
 * expensive.
 */
interface SplitEngine {
    val inline: Boolean

    /** True when the engine can change the per-pane density (only the virtual display can). */
    val supportsDpi: Boolean

    fun launch(side: Side, packageName: String)

    fun clear(side: Side)

    fun setDensity(side: Side, dpi: Int)

    fun applyBounds(left: Rect, right: Rect, commit: Boolean)

    fun release()
}
