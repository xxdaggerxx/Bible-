package com.biblestudy.app.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A stroke pre-built into a few paths grouped by (pressure-based) width.
 * [points] are the drawn (display) points; [source] is the stored points they came from, so a
 * cache can tell when the stroke has changed (e.g. moved with the lasso).
 */
class StrokeRender(val pieces: List<Pair<Path, Float>>, val bounds: Rect, val points: FloatArray, val source: FloatArray)

fun penWidth(base: Float, pressure: Float) = base * (0.35f + 0.9f * pressure.coerceIn(0f, 1f))

fun buildRender(points: FloatArray, width: Float, highlighter: Boolean, source: FloatArray = points): StrokeRender {
    val n = points.size / 3
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (i in 0 until n) {
        val x = points[3 * i]; val y = points[3 * i + 1]
        minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
    }
    val pad = width * 1.4f + 2f
    val bounds = if (n == 0) Rect.Zero else Rect(minX - pad, minY - pad, maxX + pad, maxY + pad)
    if (n == 0) return StrokeRender(emptyList(), bounds, points, source)

    if (highlighter || n < 3) {
        val p = Path()
        p.moveTo(points[0], points[1])
        for (i in 1 until n) p.lineTo(points[3 * i], points[3 * i + 1])
        if (n == 1) p.lineTo(points[0] + 0.1f, points[1])
        val w = if (highlighter) width else penWidth(width, points[2])
        return StrokeRender(listOf(p to w), bounds, points, source)
    }

    val pieces = ArrayList<Pair<Path, Float>>()
    var cur = Path()
    var curW = -1f
    for (i in 1 until n) {
        val pressure = (points[3 * i - 1] + points[3 * i + 2]) / 2f
        val w = (penWidth(width, pressure) * 4f).roundToInt() / 4f
        if (w != curW) {
            cur = Path()
            cur.moveTo(points[3 * (i - 1)], points[3 * (i - 1) + 1])
            curW = w
            pieces.add(cur to w)
        }
        cur.lineTo(points[3 * i], points[3 * i + 1])
    }
    return StrokeRender(pieces, bounds, points, source)
}

fun DrawScope.drawStrokeRender(r: StrokeRender, color: Color, ox: Float, oy: Float) {
    translate(ox, oy) {
        for ((path, w) in r.pieces) {
            drawPath(path, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Squared distance from point (px,py) to segment (ax,ay)-(bx,by). */
fun distSqToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax; val dy = by - ay
    val len = dx * dx + dy * dy
    val t = if (len == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len).coerceIn(0f, 1f)
    val cx = ax + t * dx - px; val cy = ay + t * dy - py
    return cx * cx + cy * cy
}
