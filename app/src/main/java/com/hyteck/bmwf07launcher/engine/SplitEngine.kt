package com.hyteck.bmwf07launcher.engine

enum class Side { LEFT, RIGHT }

/**
 * Abstraction over the two split-screen execution engines.
 *
 * [inline] is true when both apps are rendered inside the launcher window
 * (virtual display); false when the launcher hands the screen over to the
 * system multi-window implementation.
 */
interface SplitEngine {
    val inline: Boolean

    fun launch(side: Side, packageName: String)

    fun close(side: Side)

    fun setDensity(side: Side, dpi: Int)

    /** Called after the pane size changed (ratio drag, fullscreen toggle). */
    fun resize(side: Side)

    fun release()
}
