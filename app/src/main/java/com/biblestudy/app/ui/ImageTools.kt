package com.biblestudy.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.biblestudy.app.model.MarginImage

/** The bar for a selected margin picture (UI-4): turn, crop, delete (MRG-8). */
@Composable
fun ImageBar(vm: StudyViewModel, ctl: ReaderController, img: MarginImage, modifier: Modifier = Modifier) {
    var cropping by remember { mutableStateOf(false) }
    Surface(modifier, shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = { vm.rotateImage(img) }) { Text("Turn") }
            TextButton(onClick = { cropping = true }, enabled = vm.bitmap(img.file) != null) { Text("Crop") }
            TextButton(onClick = { vm.deleteImage(img); ctl.selectedImageId = null }) { Text("Delete image") }
        }
    }
    if (cropping) CropDialog(vm, img) { cropping = false }
}

/** Drag the corners of the frame to keep part of the picture (MRG-8). */
@Composable
fun CropDialog(vm: StudyViewModel, img: MarginImage, onDismiss: () -> Unit) {
    val bmp = vm.bitmap(img.file) ?: return onDismiss()
    // The frame as fractions of the picture.
    var frame by remember { mutableStateOf(Rect(img.cropL, img.cropT, img.cropR, img.cropB)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crop") },
        text = {
            Column {
                Text("Drag a corner to choose the part to keep.")
                Box(
                    Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 420.dp)
                        .aspectRatio(bmp.width.toFloat() / bmp.height, matchHeightConstraintsFirst = bmp.height > bmp.width)
                        .testTag("cropArea")
                ) {
                    Image(bmp, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            var corner = 0
                            detectDragGestures(
                                onDragStart = { p ->
                                    val f = frame
                                    val corners = listOf(f.topLeft, f.topRight, f.bottomRight, f.bottomLeft)
                                        .map { Offset(it.x * size.width, it.y * size.height) }
                                    corner = corners.indices.minBy { (corners[it] - p).getDistance() }
                                },
                            ) { change, drag ->
                                change.consume()
                                val dx = drag.x / size.width
                                val dy = drag.y / size.height
                                val f = frame
                                val min = 0.08f
                                frame = when (corner) {
                                    0 -> f.copy(left = (f.left + dx).coerceIn(0f, f.right - min), top = (f.top + dy).coerceIn(0f, f.bottom - min))
                                    1 -> f.copy(right = (f.right + dx).coerceIn(f.left + min, 1f), top = (f.top + dy).coerceIn(0f, f.bottom - min))
                                    2 -> f.copy(right = (f.right + dx).coerceIn(f.left + min, 1f), bottom = (f.bottom + dy).coerceIn(f.top + min, 1f))
                                    else -> f.copy(left = (f.left + dx).coerceIn(0f, f.right - min), bottom = (f.bottom + dy).coerceIn(f.top + min, 1f))
                                }
                            }
                        }
                    ) {
                        val r = Rect(frame.left * size.width, frame.top * size.height, frame.right * size.width, frame.bottom * size.height)
                        val shade = Color(0x88000000)
                        // Darken what will be cut away.
                        drawRect(shade, Offset.Zero, Size(size.width, r.top))
                        drawRect(shade, Offset(0f, r.bottom), Size(size.width, size.height - r.bottom))
                        drawRect(shade, Offset(0f, r.top), Size(r.left, r.height))
                        drawRect(shade, Offset(r.right, r.top), Size(size.width - r.right, r.height))
                        drawRect(Color.White, r.topLeft, r.size, style = Stroke(width = 3f))
                        for (c in listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft)) drawCircle(Color.White, 14f, c)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.cropImage(img, frame.left, frame.top, frame.right, frame.bottom)
                onDismiss()
            }) { Text("Done") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { frame = Rect(0f, 0f, 1f, 1f) }) { Text("Whole picture") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
