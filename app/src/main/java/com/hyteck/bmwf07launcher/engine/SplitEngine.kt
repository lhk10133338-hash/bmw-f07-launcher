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

    /**
     * Updates the desired packages for both panes without launching on the old displays yet,
     * used when a preset also changes the split ratio so the apps start directly on the newly
     * sized displays.
     */
    fun preparePackages(left: String?, right: String?) {
        if (left != null) launch(Side.LEFT, left) else clear(Side.LEFT)
        if (right != null) launch(Side.RIGHT, right) else clear(Side.RIGHT)
    }

    fun clear(side: Side)

    fun setDensity(side: Side, dpi: Int)

    /**
     * Fullscreen hides the other pane. An inline engine may freeze that display; the system
     * engine has nothing to do because the free-form window is simply covered.
     */
    fun setPaneVisible(side: Side, visible: Boolean) {}

    fun applyBounds(left: Rect, right: Rect, commit: Boolean)

    /**
     * Keep-out edges for the launcher's dock, in screen pixels.
     *
     * A floating window must never be placed over the dock: the dock's own buttons would lose
     * their first tap to the window above them. [minLeft] is the smallest `left` a left-hand pane
     * may use (the dock's right edge plus a small gap); [maxRight] is the largest `right` a
     * right-hand pane may use (the dock's left edge minus the gap). 0 means "no dock on that
     * side", so the value is not applied. Engines that draw the apps inside the launcher window
     * already sit next to the dock and ignore this.
     */
    fun setDockKeepOut(minLeft: Int, maxRight: Int) {}

    /**
     * Puts the apps named by [sides] back in front after the launcher was covered by another
     * screen (settings, app drawer, another app). Engines that render inside the launcher
     * re-attach their surface and re-launch the app on its display; the system engine re-places
     * the floating windows into their rectangles.
     */
    fun resume(sides: Set<Side>)

    /**
     * Brings one pane's picture back on demand (the pane menu's "화면 다시 그리기").
     *
     * Only an engine that draws the app inside the launcher can do this; the system engine leaves
     * it alone and the pane menu does not offer it there.
     */
    fun redraw(side: Side) {}

    fun release()
}
