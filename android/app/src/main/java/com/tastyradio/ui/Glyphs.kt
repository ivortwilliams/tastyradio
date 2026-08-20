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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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

/**
 * Three nodes joined by two lines: share. The one glyph everybody already recognises, and the
 * reason there is still no icon dependency in this app.
 */
@Composable
fun ShareGlyph(size: Dp = 18.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val radius = w * 0.14f
        val line = w * 0.09f
        val hub = Offset(radius, h * 0.5f)
        val top = Offset(w - radius, radius)
        val bottom = Offset(w - radius, h - radius)
        drawLine(color = tint, start = hub, end = top, strokeWidth = line)
        drawLine(color = tint, start = hub, end = bottom, strokeWidth = line)
        drawCircle(color = tint, radius = radius, center = hub)
        drawCircle(color = tint, radius = radius, center = top)
        drawCircle(color = tint, radius = radius, center = bottom)
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

/** A little set: speaker, tuning dial, antenna. Drawn rather than pulled from an icon library. */
@Composable
fun RadioGlyph(size: Dp = 24.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.075f)

        // Antenna, angled up from the top-right of the case.
        drawLine(
            color = tint,
            start = Offset(s * 0.66f, s * 0.34f),
            end = Offset(s * 0.90f, s * 0.10f),
            strokeWidth = s * 0.075f,
        )

        // The case.
        drawRoundRect(
            color = tint,
            topLeft = Offset(s * 0.06f, s * 0.32f),
            size = Size(s * 0.88f, s * 0.58f),
            cornerRadius = CornerRadius(s * 0.10f),
            style = stroke,
        )

        // Speaker grille on the left.
        drawCircle(
            color = tint,
            radius = s * 0.14f,
            center = Offset(s * 0.34f, s * 0.61f),
            style = stroke,
        )

        // Tuning dials on the right.
        drawCircle(color = tint, radius = s * 0.035f, center = Offset(s * 0.70f, s * 0.52f))
        drawCircle(color = tint, radius = s * 0.035f, center = Offset(s * 0.70f, s * 0.70f))
    }
}

/**
 * Three faders at different heights: a saved mix *is* a set of fader positions, and it echoes the
 * app's own launcher icon.
 */
@Composable
fun FadersGlyph(size: Dp = 24.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val trackWidth = s * 0.055f
        val positions = listOf(0.22f to 0.62f, 0.5f to 0.34f, 0.78f to 0.52f)
        positions.forEach { (x, knobY) ->
            drawLine(
                color = tint.copy(alpha = 0.55f),
                start = Offset(s * x, s * 0.14f),
                end = Offset(s * x, s * 0.86f),
                strokeWidth = trackWidth,
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(s * x - s * 0.15f, s * knobY - s * 0.055f),
                size = Size(s * 0.30f, s * 0.11f),
                cornerRadius = CornerRadius(s * 0.045f),
            )
        }
    }
}

/** A magnifier: search. */
@Composable
fun SearchGlyph(size: Dp = 24.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = s * 0.085f
        drawCircle(
            color = tint,
            radius = s * 0.27f,
            center = Offset(s * 0.43f, s * 0.42f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = tint,
            start = Offset(s * 0.63f, s * 0.62f),
            end = Offset(s * 0.86f, s * 0.85f),
            strokeWidth = stroke,
        )
    }
}

/** A cog: settings. Teeth drawn as short radial spokes, which stays legible at tab size. */
@Composable
fun SettingsGlyph(size: Dp = 24.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val centre = Offset(s * 0.5f, s * 0.5f)
        // Teeth start *inside* the ring so they read as part of the cog. Drawn clear of it, the
        // same shape reads as a sun.
        repeat(8) { index ->
            val angle = (index * 45f) * PI.toFloat() / 180f
            drawLine(
                color = tint,
                start = Offset(centre.x + cos(angle) * s * 0.20f, centre.y + sin(angle) * s * 0.20f),
                end = Offset(centre.x + cos(angle) * s * 0.42f, centre.y + sin(angle) * s * 0.42f),
                strokeWidth = s * 0.13f,
            )
        }
        // The body, over the tooth roots, leaving a hole in the middle.
        drawCircle(color = tint, radius = s * 0.235f, center = centre, style = Stroke(width = s * 0.16f))
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
