package com.redurbabat.feedback.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.redurbabat.feedback.pairing.QrEncoder

/**
 * Draws a pairing payload as a QR code.
 *
 * Colours are fixed black on white instead of following the theme: a scanner needs both the
 * contrast and the correct polarity, and an inverted code in dark mode simply does not scan. The
 * white plate is part of the code, not decoration.
 *
 * Drawing goes straight to the canvas, so no `Bitmap` is allocated and the payload never lands in
 * an image cache.
 */
@Composable
fun QrCodeImage(
    payload: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(payload) { QrEncoder.encodeOrNull(payload) }

    if (matrix == null) {
        Text(
            text = "Der QR-Code konnte nicht erzeugt werden. Verwende den sechsstelligen Code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
        return
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(maxWidth = 280.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(4.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val moduleSize = size.minDimension / matrix.size
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix.isDark(x, y)) {
                    continue
                }
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(x * moduleSize, y * moduleSize),
                    // A hair of overdraw removes the seams that rounding leaves between modules.
                    size = Size(moduleSize + 0.5f, moduleSize + 0.5f),
                )
            }
        }
    }
}
