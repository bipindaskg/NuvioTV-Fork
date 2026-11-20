package com.nuvio.tv.ui.screens.player

import android.graphics.Rect
import android.graphics.RectF
import android.view.SurfaceView
import android.view.TextureView
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.StringRes
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

internal fun applyExoAspectMode(playerView: PlayerView, mode: AspectMode) {
    val contentFrame = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
    val surfaceView = resolveVideoSurfaceView(playerView)
    val targetView = contentFrame ?: surfaceView ?: playerView
    val viewAspect = readViewAspectRatio(playerView.width, playerView.height)
    val videoAspect = readExoVideoAspectRatio(playerView)

    resetAspectTransform(playerView)
    contentFrame?.let(::resetAspectTransform)
    surfaceView?.let(::resetAspectTransform)

    // Uniform zoom/crop modes: size the video surface itself (the same way PlayerView's own
    // RESIZE_MODE_ZOOM does) instead of scaling the SurfaceView with a View transform.
    // A transformed SurfaceView is rescaled by the display compositor/HW video plane, which on
    // many TVs gives soft, distorted output; a real surface of the final size is scaled once,
    // with the correct aspect ratio, exactly like normal playback.
    val scale = resolveAspectScale(mode = mode, viewAspect = viewAspect, videoAspect = videoAspect)
    val uniformZoom = abs(scale.scaleX - scale.scaleY) < 0.001f
    if (contentFrame != null && uniformZoom) {
        setContentFrameZoom(playerView, contentFrame, scale.scaleX)
        return
    }

    // Non-uniform (Stretch) keeps the transform path; make sure the frame is back to normal size.
    contentFrame?.let { setContentFrameZoom(playerView, it, 1.0f) }
    applyAspectScale(targetView, mode, viewAspect, videoAspect)
    centerTargetInPlayer(playerView, targetView)
}

/**
 * Resizes [contentFrame] to [zoom] x the player size (centered, clipped by the PlayerView).
 * Its AspectRatioFrameLayout then fits the video inside that larger box, which is a true
 * uniform zoom/crop with no aspect distortion. zoom == 1 restores match_parent.
 */
private fun setContentFrameZoom(playerView: PlayerView, contentFrame: View, zoom: Float) {
    val lp = contentFrame.layoutParams as? FrameLayout.LayoutParams ?: return
    val safeZoom = if (zoom.isFinite() && zoom > 1.0005f) zoom else 1.0f
    val targetWidth: Int
    val targetHeight: Int
    if (safeZoom == 1.0f || playerView.width <= 0 || playerView.height <= 0) {
        targetWidth = ViewGroup.LayoutParams.MATCH_PARENT
        targetHeight = ViewGroup.LayoutParams.MATCH_PARENT
    } else {
        targetWidth = (playerView.width * safeZoom).roundToInt()
        targetHeight = (playerView.height * safeZoom).roundToInt()
    }
    if (lp.width == targetWidth && lp.height == targetHeight && lp.gravity == Gravity.CENTER) return
    lp.width = targetWidth
    lp.height = targetHeight
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

private fun centerTargetInPlayer(playerView: PlayerView, targetView: View) {
    if (
        targetView === playerView ||
        playerView.width <= 0 ||
        playerView.height <= 0 ||
        targetView.width <= 0 ||
        targetView.height <= 0
    ) {
        return
    }

    val targetRect = Rect(0, 0, targetView.width, targetView.height)
    playerView.offsetDescendantRectToMyCoords(targetView, targetRect)
    val playerCenterX = playerView.width / 2.0f
    val playerCenterY = playerView.height / 2.0f
    val targetCenterX = targetRect.left + targetRect.width() / 2.0f
    val targetCenterY = targetRect.top + targetRect.height() / 2.0f
    targetView.translationX = playerCenterX - targetCenterX
    targetView.translationY = playerCenterY - targetCenterY
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
