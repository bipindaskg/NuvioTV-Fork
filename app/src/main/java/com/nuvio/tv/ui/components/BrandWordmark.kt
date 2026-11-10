package com.nuvio.tv.ui.components

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.nuvio.tv.ui.brand.ArcArtPainter
import com.nuvio.tv.ui.brand.ArcBrandArt
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun BrandWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1f,
    drawableOverride: Int? = null,
    alignment: Alignment = Alignment.Center
) {
    if (drawableOverride != null) {
        Image(
            painter = painterResource(id = drawableOverride),
            contentDescription = contentDescription,
            modifier = modifier,
            alignment = alignment,
            contentScale = contentScale,
            alpha = alpha
        )
        return
    }
    ArcArtImage(markOnly = false, modifier, contentDescription, contentScale, alpha, alignment)
}

/** The brand symbol on its own: the Netflix "N" monogram. */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1f,
    alignment: Alignment = Alignment.Center
) {
    Image(
        painter = painterResource(id = com.nuvio.tv.R.drawable.netflix_mark),
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha
    )
}

@Composable
private fun ArcArtImage(
    markOnly: Boolean,
    modifier: Modifier,
    contentDescription: String?,
    contentScale: ContentScale,
    alpha: Float,
    alignment: Alignment
) {
    val context = LocalContext.current.applicationContext
    val palette = NuvioTheme.arcPalette
    val painter = remember(markOnly) { ArcArtPainter(markOnly) }
    var bucket by remember { mutableIntStateOf(0) }
    LaunchedEffect(palette, bucket) {
        if (bucket == 0) return@LaunchedEffect
        painter.image = ArcBrandArt.cached(palette, bucket)
            ?: withContext(Dispatchers.Default) {
                runCatching { ArcBrandArt.load(context, palette, bucket) }
                    .onFailure { Log.w("BrandWordmark", "logo art failed", it) }
                    .getOrNull()
            }
    }
    val aspect = if (markOnly) ArcBrandArt.MarkAspect else ArcBrandArt.LockupAspect
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier.onSizeChanged {
            bucket = ArcBrandArt.bucketFor(min(it.height.toFloat(), it.width / aspect).roundToInt())
        },
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha
    )
}
