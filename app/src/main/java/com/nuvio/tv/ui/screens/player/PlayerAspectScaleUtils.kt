package com.nuvio.tv.ui.screens.player

import android.graphics.RectF
import android.view.SurfaceView
import android.view.TextureView
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.tv.R
import kotlin.math.abs
import kotlin.math.roundToInt

enum class AspectMode(@StringRes val labelResId: Int) {
    ORIGINAL(R.string.player_aspect_fit),
    FULL_SCREEN(R.string.player_aspect_crop),
    STRETCH(R.string.player_aspect_stretch),
    SLIGHT_ZOOM(R.string.player_aspect_mode_slight_zoom),
    CINEMA_ZOOM(R.string.player_aspect_mode_cinema_zoom),
    VERTICAL_STRETCH(R.string.player_aspect_fit_height),
    HORIZONTAL_STRETCH(R.string.player_aspect_fit_width)
}

internal fun aspectModeAppliedToExoSurface(
    tunnelingEnabled: Boolean,
    aspectMode: AspectMode
): AspectMode {
    return if (tunnelingEnabled) AspectMode.ORIGINAL else aspectMode
}

internal fun nextAspectMode(current: AspectMode): AspectMode {
    val modes = AspectMode.entries
    val nextIndex = (modes.indexOf(current) + 1) % modes.size
    return modes[nextIndex]
}

internal fun aspectModeLabel(mode: AspectMode, getString: (Int) -> String): String =
    getString(mode.labelResId)

internal data class AspectScale(val scaleX: Float, val scaleY: Float)

/**
 * The distances from the libass overlay frame to the visible video rectangle.
 * Values are signed: a positive value is a letterbox/pillarbox bar, while a negative value
 * means that a zoom or crop extends the video beyond the overlay frame.
 */
internal data class LibassOverlayMargins(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int
)

internal fun libassOverlayMargins(
    overlayWidth: Int,
    overlayHeight: Int,
    videoBounds: RectF
): LibassOverlayMargins? = libassOverlayMargins(
    overlayWidth, overlayHeight,
    videoBounds.left, videoBounds.top, videoBounds.right, videoBounds.bottom
)

internal fun libassOverlayMargins(
    overlayWidth: Int,
    overlayHeight: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float
): LibassOverlayMargins? {
    if (
        overlayWidth <= 0 || overlayHeight <= 0 ||
        !left.isFinite() || !top.isFinite() ||
        !right.isFinite() || !bottom.isFinite() ||
        right <= left || bottom <= top
    ) {
        return null
    }
    return LibassOverlayMargins(
        top = top.roundToInt(),
        bottom = (overlayHeight - bottom).roundToInt(),
        left = left.roundToInt(),
        right = (overlayWidth - right).roundToInt()
    )
}

/**
 * Maps a direct child's laid-out and transformed bounds into its parent's coordinate space.
 * [exo_content_frame] is a direct child of PlayerView in exo_player_view.xml.
 */
internal fun transformedChildBoundsInParent(child: View): RectF? {
    if (child.width <= 0 || child.height <= 0) return null
    return RectF(0f, 0f, child.width.toFloat(), child.height.toFloat()).also { bounds ->
        child.matrix.mapRect(bounds)
        bounds.offset(child.left.toFloat(), child.top.toFloat())
    }
}

internal fun aspectModeNeedsVideoAspect(mode: AspectMode): Boolean {
    return when (mode) {
        AspectMode.FULL_SCREEN,
        AspectMode.STRETCH,
        AspectMode.VERTICAL_STRETCH,
        AspectMode.HORIZONTAL_STRETCH -> true

        AspectMode.ORIGINAL,
        AspectMode.SLIGHT_ZOOM,
        AspectMode.CINEMA_ZOOM -> false
    }
}

internal fun readViewAspectRatio(width: Int, height: Int): Float {
    return if (width > 0 && height > 0) {
        width.toFloat() / height.toFloat()
    } else {
        0f
    }
}

internal fun readExoVideoAspectRatio(playerView: PlayerView): Float? {
    val videoSize = playerView.player?.videoSize
    return if ((videoSize?.height ?: 0) > 0) {
        ((videoSize?.width ?: 0).toFloat() * (videoSize?.pixelWidthHeightRatio ?: 1f)) /
            videoSize!!.height.toFloat()
    } else {
        null
    }
}

internal fun resolveAspectScale(mode: AspectMode, viewAspect: Float, videoAspect: Float?): AspectScale {
    if (viewAspect <= 0f) {
        return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
    }

    return when (mode) {
        AspectMode.ORIGINAL -> AspectScale(scaleX = 1.0f, scaleY = 1.0f)

        AspectMode.FULL_SCREEN -> {
            val safeVideoAspect = videoAspect?.takeIf { it > 0f }
                ?: return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
            val uniformScale = if (safeVideoAspect > viewAspect) {
                safeVideoAspect / viewAspect
            } else {
                viewAspect / safeVideoAspect
            }
            AspectScale(scaleX = uniformScale, scaleY = uniformScale)
        }

        AspectMode.STRETCH -> {
            val safeVideoAspect = videoAspect?.takeIf { it > 0f }
                ?: return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
            if (safeVideoAspect > viewAspect) {
                AspectScale(scaleX = 1.0f, scaleY = safeVideoAspect / viewAspect)
            } else {
                AspectScale(scaleX = viewAspect / safeVideoAspect, scaleY = 1.0f)
            }
        }

        AspectMode.SLIGHT_ZOOM -> AspectScale(scaleX = 1.15f, scaleY = 1.15f)

        AspectMode.CINEMA_ZOOM -> AspectScale(scaleX = 1.33f, scaleY = 1.33f)

        AspectMode.VERTICAL_STRETCH -> {
            val safeVideoAspect = videoAspect?.takeIf { it > 0f }
                ?: return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
            if (safeVideoAspect > viewAspect) {
                val uniformScale = safeVideoAspect / viewAspect
                AspectScale(scaleX = uniformScale, scaleY = uniformScale)
            } else {
                AspectScale(scaleX = 1.0f, scaleY = 1.0f)
            }
        }

        AspectMode.HORIZONTAL_STRETCH -> {
            val safeVideoAspect = videoAspect?.takeIf { it > 0f }
                ?: return AspectScale(scaleX = 1.0f, scaleY = 1.0f)
            if (safeVideoAspect < viewAspect) {
                val uniformScale = viewAspect / safeVideoAspect
                AspectScale(scaleX = uniformScale, scaleY = uniformScale)
            } else {
                AspectScale(scaleX = 1.0f, scaleY = 1.0f)
            }
        }
    }
}

/**
 * Applies [mode] to an Exo PlayerView WITHOUT ever letting the video surface extend past the
 * screen.
 *
 * Why: some TV SoCs (seen on the Vu Android TV) put the SurfaceView on a hardware video plane that
 * cannot clip. When a transformed / oversized surface is partly off-screen, the whole frame gets
 * squeezed into the visible part, so "zoom" shows the full picture distorted instead of cropped.
 *
 * Instead the surface is sized to the visible part of the zoomed picture (always inside the
 * screen) and the codec crops the video to that surface (SCALE_TO_FIT_WITH_CROPPING). The result
 * is a true uniform zoom/crop with no reshaping.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun applyExoAspectMode(
    playerView: PlayerView,
    mode: AspectMode,
    manageResizeMode: Boolean = true
) {
    val contentFrame = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
    val surfaceView = resolveVideoSurfaceView(playerView)
    val viewWidth = playerView.width
    val viewHeight = playerView.height
    val viewAspect = readViewAspectRatio(viewWidth, viewHeight)
    val videoAspect = readExoVideoAspectRatio(playerView)

    // No view transforms any more; clear anything left over from older code paths.
    resetAspectTransform(playerView)
    contentFrame?.let(::resetAspectTransform)
    surfaceView?.let(::resetAspectTransform)

    val scale = resolveAspectScale(mode = mode, viewAspect = viewAspect, videoAspect = videoAspect)
    val uniform = abs(scale.scaleX - scale.scaleY) < 0.001f
    val zoom = scale.scaleX
    val safeVideoAspect = videoAspect?.takeIf { it > 0f }

    var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    var scalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
    var targetWidth = ViewGroup.LayoutParams.MATCH_PARENT
    var targetHeight = ViewGroup.LayoutParams.MATCH_PARENT

    if (viewWidth > 0 && viewHeight > 0 && safeVideoAspect != null) {
        if (!uniform) {
            // Stretch: fill the whole screen and let the picture be reshaped on purpose.
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
        } else if (zoom > 1.0005f) {
            // Size of the video when fitted inside the screen.
            val fitWidth: Float
            val fitHeight: Float
            if (safeVideoAspect > viewAspect) {
                fitWidth = viewWidth.toFloat()
                fitHeight = viewWidth / safeVideoAspect
            } else {
                fitHeight = viewHeight.toFloat()
                fitWidth = viewHeight * safeVideoAspect
            }
            // Visible part of the zoomed picture, clamped to the screen.
            targetWidth = minOf(viewWidth.toFloat(), fitWidth * zoom).roundToInt()
            targetHeight = minOf(viewHeight.toFloat(), fitHeight * zoom).roundToInt()
            // The surface is exactly that box; the codec covers it and crops the overflow.
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            scalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        }
    }

    if (manageResizeMode && playerView.resizeMode != resizeMode) {
        playerView.resizeMode = resizeMode
    }
    contentFrame?.let { setContentFrameSize(it, targetWidth, targetHeight) }
    (playerView.player as? ExoPlayer)?.let { exoPlayer ->
        if (exoPlayer.videoScalingMode != scalingMode) {
            exoPlayer.videoScalingMode = scalingMode
        }
    }
}

private fun setContentFrameSize(contentFrame: View, width: Int, height: Int) {
    val lp = contentFrame.layoutParams as? FrameLayout.LayoutParams ?: return
    if (lp.width == width && lp.height == height && lp.gravity == Gravity.CENTER) return
    lp.width = width
    lp.height = height
    lp.gravity = Gravity.CENTER
    contentFrame.layoutParams = lp
}

internal fun applyAspectMode(playerView: PlayerView, mode: AspectMode) {
    val targetView = resolveVideoSurfaceView(playerView) ?: playerView
    val viewAspect = readViewAspectRatio(playerView.width, playerView.height)
    val videoAspect = readExoVideoAspectRatio(playerView)
    resetAspectTransform(playerView)

    applyAspectScale(targetView, mode, viewAspect, videoAspect)
}

internal fun addExoAspectLayoutChangeListener(
    playerView: PlayerView,
    listener: View.OnLayoutChangeListener
): () -> Unit {
    val targets = linkedSetOf<View>()
    targets.add(playerView)
    playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)?.let(targets::add)
    resolveVideoSurfaceView(playerView)?.let(targets::add)
    targets.forEach { it.addOnLayoutChangeListener(listener) }
    return {
        targets.forEach { it.removeOnLayoutChangeListener(listener) }
    }
}

private fun applyAspectScale(targetView: View, mode: AspectMode, viewAspect: Float, videoAspect: Float?) {
    val scale = resolveAspectScale(
        mode = mode,
        viewAspect = viewAspect,
        videoAspect = videoAspect
    )
    targetView.scaleX = scale.scaleX
    targetView.scaleY = scale.scaleY
}

private fun resetAspectTransform(view: View) {
    view.scaleX = 1.0f
    view.scaleY = 1.0f
    view.translationX = 0.0f
    view.translationY = 0.0f
    if (view.width > 0) {
        view.pivotX = view.width / 2.0f
    }
    if (view.height > 0) {
        view.pivotY = view.height / 2.0f
    }
}

private fun resolveVideoSurfaceView(playerView: PlayerView): View? {
    return findVideoSurfaceView(playerView)
}

private fun findVideoSurfaceView(view: View): View? {
    return when (view) {
        is SurfaceView, is TextureView -> view
        is ViewGroup -> {
            for (index in 0 until view.childCount) {
                val child = findVideoSurfaceView(view.getChildAt(index))
                if (child != null) {
                    return child
                }
            }
            null
        }

        else -> null
    }
}
