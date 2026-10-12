package com.biblestudy.app.ui

import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Exports one chapter page, with its ink, highlights, pictures and text boxes, as a PDF (cut into
 * A4-shaped pages) or a single PNG picture (DATA-5). [draw] paints the page at its own size.
 */
object ChapterExport {
    /** PDF page width in points; pages are A4-shaped. */
    const val PDF_W = 595f
    const val PDF_H = 842f
    /** Keeps a picture export's memory reasonable for very long chapters. */
    const val MAX_PIXELS = 16_000_000f

    /** Space kept at the foot of each PDF page, and under a picture, for the copyright line (BIB-10). */
    const val FOOTER_PT = 28f
    const val FOOTER_PX = 44f

    fun pdf(out: okio.Sink, w: Float, h: Float, footer: String, draw: DrawScope.() -> Unit) = ExportWriter.pdf(out, w, h, footer, draw)

    fun png(out: okio.Sink, w: Float, h: Float, footer: String, draw: DrawScope.() -> Unit) = ExportWriter.png(out, w, h, footer, draw)

    /** The line printed on every export: the version's copyright and where it came from (BIB-10). */
    fun footerFor(version: com.biblestudy.app.data.BibleVersion, reference: String): String =
        "$reference (${version.code}) · ${version.copyright.trimEnd('.')} · Exported from Ink & Word"
}

/** Writes the export files on this platform. */
expect object ExportWriter {
    fun pdf(out: okio.Sink, w: Float, h: Float, footer: String, draw: DrawScope.() -> Unit)
    fun png(out: okio.Sink, w: Float, h: Float, footer: String, draw: DrawScope.() -> Unit)
}
