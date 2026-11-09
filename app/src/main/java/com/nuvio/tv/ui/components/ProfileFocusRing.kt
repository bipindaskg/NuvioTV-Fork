package com.nuvio.tv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A bright yellow ring with a soft glow, drawn around a profile avatar with a small gap, so it is
 * obvious from across the room which profile the remote is on. [avatarDiameter] is the size of the
 * avatar circle; the ring is centred on this element and drawn outside it (about 15dp of room is
 * needed around the avatar). Drawn with a few plain strokes, which keeps it cheap on old TVs.
 */
fun Modifier.profileFocusRing(focused: Boolean, avatarDiameter: Dp): Modifier =
    if (!focused) {
        this
    } else {
        drawBehind {
            val avatarRadius = avatarDiameter.toPx() / 2f
            val gap = 5.dp.toPx()
            val stroke = 4.dp.toPx()
            val ringRadius = avatarRadius + gap + stroke / 2f
            val yellow = Color(0xFFFFD60A)
            // Soft glow: two wider, fainter rings underneath the solid one.
            drawCircle(
                color = yellow.copy(alpha = 0.14f),
                radius = ringRadius + 3.dp.toPx(),
                style = Stroke(width = stroke + 6.dp.toPx())
            )
            drawCircle(
                color = yellow.copy(alpha = 0.30f),
                radius = ringRadius + 1.5f.dp.toPx(),
                style = Stroke(width = stroke + 3.dp.toPx())
            )
            drawCircle(color = yellow, radius = ringRadius, style = Stroke(width = stroke))
        }
    }
