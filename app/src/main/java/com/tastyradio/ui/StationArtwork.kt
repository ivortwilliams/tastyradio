package com.tastyradio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

/**
 * A station's circular artwork.
 *
 * The image URL comes from the **directory** (radio-browser's `favicon`), not from the stream — ICY
 * stream metadata is text only, which is why the second line of the playback bar shows a track title
 * and never a picture.
 *
 * Two things about radio artwork that this has to survive:
 *
 * 1. **Plenty of stations have no usable image** — no favicon in the directory, or a dead URL. So a
 *    monogram stands in, and the row still looks deliberate rather than broken.
 * 2. **Many favicons are transparent PNGs** drawn for a light page. Rendered straight onto a dark
 *    list they either vanish or show whatever is behind them, so real artwork gets an opaque light
 *    backdrop. The monogram is a *fallback*, never a backdrop — otherwise it bleeds through the
 *    transparent parts and every logo looks dirty.
 */
@Composable
fun StationArtwork(
    name: String,
    imageUrl: String?,
    size: Dp = 52.dp,
    live: Boolean = false,
) {
    var loaded by remember(imageUrl) { mutableStateOf(false) }

    val outline = if (live) {
        Modifier.border(width = 2.dp, color = MaterialTheme.colorScheme.primary, shape = CircleShape)
    } else {
        Modifier
    }

    Box(modifier = Modifier.size(size).clip(CircleShape).then(outline)) {
        if (!loaded) {
            StationMonogram(name = name, size = size)
        }
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onState = { state -> loaded = state is AsyncImagePainter.State.Success },
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .then(if (loaded) Modifier.background(ArtworkBackdrop) else Modifier),
            )
        }
    }
}

/** Station logos are almost always drawn for a light background. Give them one. */
private val ArtworkBackdrop = Color(0xFFF4F4F6)
