package com.biblestudy.app.ui

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Choreographer
import android.view.SurfaceView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer

/** One piece of a pen stroke, in this view's pixels. */
class InkSegment(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val width: Float, val color: Int)

/**
 * Draws the pen stroke being written straight to the screen's front buffer (INK-4), as Samsung
 * Notes does, so ink appears under the pen tip without waiting for the next frame. Only the stroke
 * in progress is drawn here; once the pen lifts, the page draws the saved stroke and this clears.
 *
 * The surface sits above the page (it is transparent everywhere else) but takes no touches: the
 * page beneath it still receives the pen.
 */
class FastInkView(context: Context) : SurfaceView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var renderer: CanvasFrontBufferedRenderer<InkSegment>? = null
    /** Bumped when a stroke starts, so a pending clear doesn't wipe a stroke being written. */
    private var strokes = 0

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isClickable = false
        isFocusable = false
    }

    private val callbacks = object : CanvasFrontBufferedRenderer.Callback<InkSegment> {
        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: InkSegment) {
            paint.color = param.color
            paint.strokeWidth = param.width
            canvas.drawLine(param.x0, param.y0, param.x1, param.y1, paint)
        }

        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<InkSegment>) {
            // Nothing persists here: saved strokes are drawn by the page underneath.
            canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer = runCatching { CanvasFrontBufferedRenderer(this, callbacks) }.getOrNull()
    }

    override fun onDetachedFromWindow() {
        renderer?.release(true)
        renderer = null
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false

    val ready: Boolean get() = renderer?.isValid() == true

    fun startStroke() { strokes++ }

    fun draw(segment: InkSegment) {
        renderer?.renderFrontBufferedLayer(segment)
    }

    fun clearNow() {
        strokes++
        renderer?.clear()
    }

    /**
     * Clears the stroke once the page has had time to draw the saved one, so it never blinks out.
     */
    fun clearSoon() {
        val r = renderer ?: return
        val stroke = strokes
        val chor = Choreographer.getInstance()
        chor.postFrameCallback { chor.postFrameCallback { chor.postFrameCallback { if (strokes == stroke) r.clear() } } }
    }

    companion object {
        /** Front-buffered rendering needs a real display; it is off under Robolectric. */
        val supported: Boolean get() = Build.FINGERPRINT != "robolectric"
    }
}
