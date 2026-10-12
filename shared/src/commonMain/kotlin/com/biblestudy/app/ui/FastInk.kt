package com.biblestudy.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** One piece of a pen stroke, in the page's pixels. */
class InkSegment(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val width: Float, val color: Int)

/**
 * Draws the pen stroke being written straight to the screen (INK-4), where the platform can, so
 * ink appears under the pen tip without waiting for the next frame. Only the stroke in progress is
 * drawn here; once the pen lifts, the page draws the saved stroke and this clears.
 */
interface FastInk {
    val ready: Boolean
    fun startStroke()
    fun draw(segment: InkSegment)
    fun clearNow()
    /** Clears the stroke once the page has had time to draw the saved one, so it never blinks out. */
    fun clearSoon()
}

/** Whether this platform has a fast ink layer (Android's front-buffered rendering). */
expect val fastInkSupported: Boolean

/** The fast ink layer over the page; [onChange] gets it when ready, and null when it goes. */
@Composable
expect fun FastInkLayer(modifier: Modifier, onChange: (FastInk?) -> Unit)
