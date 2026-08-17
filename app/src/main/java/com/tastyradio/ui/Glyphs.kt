package com.tastyradio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Hand-drawn glyphs and monogram artwork.
 *
 * Deliberately no icon-font dependency and no image loader yet: `stop`, `mute` and `record` aren't
 * in Compose's core icon set, and real station artwork is a later phase. A monogram in a coloured
 * circle keeps the reference layout (circle on the left of every row) honest in the meantime.
 */

/** A filled square: stop, not pause. Live streams can't be resumed from where they stopped. */
@Composable
fun StopGlyph(size: Dp = 16.dp, tint: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Canvas(modifier = Modifier.size(size)) {
        drawRoundRect(color = tint, cornerRadius = CornerRadius(this.size.minDimension * 0.15f))
    }
}

/** A filled triangle: play. */
@Composable
fun PlayGlyph(size: Dp = 18.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.15f, 0f)
            lineTo(w, h * 0.5f)
            lineTo(w * 0.15f, h)
            close()
        }
        drawPath(path, color = tint)
    }
}

/** A filled circle: record. */
@Composable
fun RecordGlyph(size: Dp = 16.dp, tint: Color = Color(0xFFFF5252)) {
    Canvas(modifier = Modifier.size(size)) {
        drawCircle(color = tint)
    }
}

/** A broken ring, drawn while a channel is connecting. */
@Composable
fun ConnectingGlyph(size: Dp = 16.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier = Modifier.size(size)) {
        val side = this.size.minDimension
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 280f,
            useCenter = false,
            style = Stroke(width = side * 0.14f),
            topLeft = Offset.Zero,
            size = Size(side, side),
        )
    }
}

/**
 * Stand-in station artwork: the station's initials on a colour derived from its name, so the same
 * station is always the same colour and the list stays scannable.
 */
@Composable
fun StationMonogram(name: String, size: Dp = 52.dp, live: Boolean = false) {
    val background = monogramColor(name)
    val foreground = if (background.luminance() > 0.5f) Color.Black else Color.White
    val base = Modifier
        .size(size)
        .clip(CircleShape)
        .background(background)
    Box(
        modifier = if (live) {
            base.border(width = 2.dp, color = MaterialTheme.colorScheme.primary, shape = CircleShape)
        } else {
            base
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name),
            color = foreground,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.34f).sp,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun initialsOf(name: String): String {
    val letters = name.split(' ', '-', '–', '_')
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
    return when {
        letters.isEmpty() -> "?"
        letters.size == 1 -> letters.first().uppercase()
        else -> "${letters[0].uppercaseChar()}${letters[1].uppercaseChar()}"
    }
}

/** Deterministic, mid-saturation, mid-lightness so white or black text always reads on it. */
internal fun monogramColor(name: String): Color {
    val hue = (abs(name.hashCode()) % 360).toFloat()
    return Color.hsl(hue = hue, saturation = 0.45f, lightness = 0.42f)
}
