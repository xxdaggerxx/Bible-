package com.biblestudy.app.ui

import android.content.Context
import com.biblestudy.app.model.InkStroke
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import kotlinx.coroutines.tasks.await

/**
 * Reads handwriting (INK-14, SRCH-8). The app only needs "turn these strokes into words"; the
 * reader behind it is Google's on-device recogniser, or a stand-in in tests.
 */
interface InkReader {
    /** Whether it can read now (its model is on the tablet). */
    suspend fun ready(): Boolean

    /** Fetches what it needs, once (the English model, about 20 MB, over Wi-Fi). */
    suspend fun prepare()

    /** The words in one line of writing; each stroke is (x, y, pressure) triples. Null if unreadable. */
    suspend fun read(line: List<FloatArray>): String?
}

/** Google ML Kit digital ink recognition, English. Everything runs on the tablet once the model is down. */
class MlKitInkReader : InkReader {
    private val model = DigitalInkRecognitionModel.builder(
        DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")!!
    ).build()
    private val recognizer by lazy { DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build()) }

    override suspend fun ready(): Boolean =
        runCatching { RemoteModelManager.getInstance().isModelDownloaded(model).await() }.getOrDefault(false)

    override suspend fun prepare() {
        RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().requireWifi().build()).await()
    }

    override suspend fun read(line: List<FloatArray>): String? {
        val ink = Ink.builder()
        var t = 0L
        for (s in line) {
            val b = Ink.Stroke.builder()
            for (i in s.indices step 3) { b.addPoint(Ink.Point.create(s[i], s[i + 1], t)); t += 10 }
            ink.addStroke(b.build())
        }
        val r = recognizer.recognize(ink.build()).await()
        return r.candidates.firstOrNull()?.text?.takeIf { it.isNotBlank() }
    }
}

/**
 * Groups handwriting into lines for reading (INK-14): strokes that share a band of height, left to
 * right; lines are read top to bottom.
 */
object InkLines {
    class Line(val strokes: List<InkStroke>, val top: Float, val bottom: Float, val left: Float, val right: Float)

    private fun bounds(s: InkStroke): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in s.points.indices step 3) {
            minX = minOf(minX, s.points[i]); maxX = maxOf(maxX, s.points[i])
            minY = minOf(minY, s.points[i + 1]); maxY = maxOf(maxY, s.points[i + 1])
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    fun group(strokes: List<InkStroke>): List<Line> {
        val items = strokes.filter { !it.highlighter && it.points.size >= 6 }.map { it to bounds(it) }.sortedBy { it.second[1] }
        val lines = ArrayList<MutableList<Pair<InkStroke, FloatArray>>>()
        for (it in items) {
            val b = it.second
            val mid = (b[1] + b[3]) / 2
            // Joins the line whose band holds this stroke's middle (dots and crosses included).
            val line = lines.lastOrNull { l ->
                val top = l.minOf { x -> x.second[1] }; val bottom = l.maxOf { x -> x.second[3] }
                val h = (bottom - top).coerceAtLeast(20f)
                mid >= top - h * 0.25f && mid <= bottom + h * 0.25f
            }
            if (line != null) line += it else lines += mutableListOf(it)
        }
        return lines.map { l ->
            val sorted = l.sortedBy { it.second[0] }
            Line(sorted.map { it.first }, l.minOf { it.second[1] }, l.maxOf { it.second[3] }, l.minOf { it.second[0] }, l.maxOf { it.second[2] })
        }.sortedBy { it.top }
    }

    /** Reads every line with [reader] and joins them, or null if nothing could be read. */
    suspend fun read(reader: InkReader, strokes: List<InkStroke>): String? {
        val parts = group(strokes).mapNotNull { l -> runCatching { reader.read(l.strokes.map { it.points }) }.getOrNull() }
        return parts.joinToString("\n").ifBlank { null }
    }
}

/** The real reader, made when first needed (it isn't available under the unit tests). */
fun defaultInkReader(@Suppress("UNUSED_PARAMETER") context: Context): InkReader = MlKitInkReader()
