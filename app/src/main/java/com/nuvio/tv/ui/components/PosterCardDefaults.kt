package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioComponents
import com.nuvio.tv.ui.v2.appearance.LocalUiScaleDecision
import com.nuvio.tv.ui.v2.scale.UiScaleDecision
import com.nuvio.tv.ui.v2.scale.posterSizeDp
import com.nuvio.tv.ui.v2.scale.posterSizeFactor

val LocalLandscapePosterMode = staticCompositionLocalOf { false }
val LocalAlwaysBackdropWithLogo = staticCompositionLocalOf { false }
val LocalLogoOverCardTrailer = staticCompositionLocalOf { true }

/** How much the poster under the remote is enlarged (1.08 = 8% bigger), set in Layout settings. */
val LocalPosterFocusScale = staticCompositionLocalOf { 1.08f }

@Immutable
data class PosterCardStyle(
    val width: Dp = NuvioComponents.tokens.posterCard.width,
    val height: Dp = NuvioComponents.tokens.posterCard.height,
    val cornerRadius: Dp = NuvioComponents.tokens.posterCard.cornerRadius,
    val focusedBorderWidth: Dp = NuvioComponents.tokens.posterCard.focusedBorderWidth,
    val focusedScale: Float = NuvioComponents.tokens.posterCard.focusedScale
) {
    val aspectRatio: Float
        get() = width.value / height.value
}

object PosterCardDefaults {
    val Style = PosterCardStyle()
}

/** Style for the chosen poster width; under the automatic interface scale the posters keep their Original look size. */
@Composable
fun rememberPosterCardStyle(widthDp: Int, cornerRadiusDp: Int): PosterCardStyle {
    val uiScale = LocalUiScaleDecision.current
    return remember(widthDp, cornerRadiusDp, uiScale) {
        val width = uiScale.posterSizeDp(widthDp).dp
        PosterCardStyle(width = width, height = width * 1.5f, cornerRadius = cornerRadiusDp.dp)
    }
}

/** A fixed poster size under the same rule as the chosen width; corners and focus stay as they are. */
fun PosterCardStyle.scaledFor(uiScale: UiScaleDecision): PosterCardStyle {
    val factor = uiScale.posterSizeFactor()
    return if (factor == 1f) this else copy(width = width * factor, height = height * factor)
}

@Composable
fun rememberScaledPosterCardStyle(style: PosterCardStyle): PosterCardStyle {
    val uiScale = LocalUiScaleDecision.current
    return remember(style, uiScale) { style.scaledFor(uiScale) }
}
