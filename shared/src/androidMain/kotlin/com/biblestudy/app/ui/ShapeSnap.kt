package com.biblestudy.app.ui

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The shapes a held pen stroke can snap to (INK-12). */
enum class Shape { LINE, ARROW, BOX, OVAL }

/**
 * Hold-to-snap shape recognition (INK-12): a stroke drawn and then held still becomes a clean
 * straight line, an arrow (a line with a flick back at its tip), a box or an oval (a stroke that
 * closes on itself). Points are (x, y, pressure) triples; the result uses the same form.
 */
object ShapeSnap {
    fun recognize(pts: FloatArray): Pair<Shape, FloatArray>? {
        val n = pts.size / 3
        if (n < 4) return null
        fun x(i: Int) = pts[3 * i]
        fun y(i: Int) = pts[3 * i + 1]
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var length = 0f
        for (i in 0 until n) {
            minX = min(minX, x(i)); maxX = max(maxX, x(i)); minY = min(minY, y(i)); maxY = max(maxY, y(i))
            if (i > 0) length += hypot(x(i) - x(i - 1), y(i) - y(i - 1))
        }
        val w = maxX - minX; val h = maxY - minY
        val diag = hypot(w, h)
        if (diag < 12f) return null
        val closeGap = hypot(x(n - 1) - x(0), y(n - 1) - y(0))

        // A closed loop: a box if points crowd the corners, otherwise an oval.
        if (closeGap < 0.25f * diag && length > 1.6f * diag && w > 8f && h > 8f) {
            val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
            var cornerish = 0
            for (i in 0 until n) {
                val u = (x(i) - cx) / (w / 2f); val v = (y(i) - cy) / (h / 2f)
                if (u * u + v * v > 1.45f) cornerish++
            }
            return if (cornerish > n * 0.12f) Shape.BOX to box(minX, minY, maxX, maxY)
            else Shape.OVAL to oval(cx, cy, w / 2f, h / 2f)
        }

        // An open stroke: straight from start to tip, perhaps with a flick back (an arrowhead).
        var tip = 0
        var far = 0f
        for (i in 0 until n) {
            val d = hypot(x(i) - x(0), y(i) - y(0))
            if (d > far) { far = d; tip = i }
        }
        if (far < 12f || !straight(pts, 0, tip, far)) return null
        val after = n - 1 - tip
        val backLen = hypot(x(n - 1) - x(tip), y(n - 1) - y(tip))
        if (after >= 2 && backLen > 0.08f * far) {
            return Shape.ARROW to arrow(x(0), y(0), x(tip), y(tip), far)
        }
        if (tip < n - 1 && backLen > 0.04f * far) return null
        return Shape.LINE to line(x(0), y(0), x(tip), y(tip))
    }

    /** Whether points [from]..[to] stay close to the straight line between them. */
    private fun straight(pts: FloatArray, from: Int, to: Int, len: Float): Boolean {
        val ax = pts[3 * from]; val ay = pts[3 * from + 1]
        val bx = pts[3 * to]; val by = pts[3 * to + 1]
        for (i in from..to) {
            val px = pts[3 * i]; val py = pts[3 * i + 1]
            val dev = kotlin.math.abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / len
            if (dev > max(6f, 0.1f * len)) return false
        }
        return true
    }

    private const val P = 0.5f // steady pressure for clean shapes

    private fun line(ax: Float, ay: Float, bx: Float, by: Float) = floatArrayOf(ax, ay, P, bx, by, P)

    private fun arrow(ax: Float, ay: Float, bx: Float, by: Float, len: Float): FloatArray {
        val a = atan2(by - ay, bx - ax)
        val head = max(12f, 0.18f * len)
        val spread = Math.toRadians(28.0).toFloat()
        val h1x = bx - head * cos(a - spread); val h1y = by - head * sin(a - spread)
        val h2x = bx - head * cos(a + spread); val h2y = by - head * sin(a + spread)
        return floatArrayOf(ax, ay, P, bx, by, P, h1x, h1y, P, bx, by, P, h2x, h2y, P)
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) =
        floatArrayOf(l, t, P, r, t, P, r, b, P, l, b, P, l, t, P)

    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float): FloatArray {
        val steps = 64
        return FloatArray((steps + 1) * 3) { i ->
            val k = i / 3
            val t = (2 * Math.PI * k / steps).toFloat()
            when (i % 3) { 0 -> cx + rx * cos(t); 1 -> cy + ry * sin(t); else -> P }
        }
    }
}
