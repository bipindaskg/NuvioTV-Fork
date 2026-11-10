package com.nuvio.tv.ui.brand

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.PathParser
import com.nuvio.tv.R
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Convergence / Ignition entrance, drawn with plain Canvas calls on a 1920 x 1080 stage.
 * Geometry and recoloured plates are prepared once; [draw] allocates nothing.
 */
class ArcIgnitionScene private constructor(
    /** The Netflix picture shown in the middle of the stage (it replaces the Arc mark and lettering). */
    private val brandMark: Bitmap,
    private val palette: ArcPalette,
    /** Glass art carries its own transparency, so it is laid over the backdrop rather than screened. */
    private val glassPlate: Boolean,
    /** Device pixels per stage unit the art was prepared for; at that scale plates and lettering are drawn 1:1. */
    private val pixelScale: Float
) {
    /** 2 = full detail, 1 = fewer strands and sparks, 0 = also no wide haze. */
    var detail = 2

    private val stageToLogo = Matrix().apply { setScale(LogoSize / 1254f, LogoSize / 1254f); postTranslate(LogoX, LogoY) }
    private val cut = path(CutPath).apply { transform(stageToLogo) }
    private val outside = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        addRect(-200f, -200f, StageW + 200f, StageH + 200f, Path.Direction.CW)
        addPath(path(ShapePath).apply { transform(stageToLogo) })
        addPath(cut)
    }

    private val samples = Array(Streams.size) { si ->
        Array(7) { strand -> FloatArray(SampleCount * 3).also { out ->
            for (i in 0 until SampleCount) streamPoint(i / (SampleCount - 1f), Streams[si], strand - 3, out, i * 3)
        } }
    }

    // The light layer only covers where light is drawn: the currents' reach while they run, then the mark.
    private val igniteBounds = RectF(LogoX - LightMargin, LogoY - LightMargin, LogoX + LogoSize + LightMargin, LogoY + LogoSize + LightMargin)
    private val currentsBounds = RectF(igniteBounds).also { r ->
        for (stream in samples) for (pts in stream) for (i in pts.indices step 3) r.union(pts[i] - LightMargin, pts[i + 1] - LightMargin, pts[i] + LightMargin, pts[i + 1] + LightMargin)
        r.intersect(-1f, -1f, StageW + 1f, StageH + 1f)
    }

    private val tones = HashMap<Int, Int>()
    private fun tone(rgb: Int): Int = tones.getOrPut(rgb) { palette.map(0xFF000000.toInt() or rgb) }

    private val gradients = HashMap<Int, LinearGradient>()
    private val tints = HashMap<Long, PorterDuffColorFilter>()

    private val scratch = FloatArray(512 * 3)
    private val point = FloatArray(3)
    private val point2 = FloatArray(3)
    private val strokePath = Path()
    private val gradientMatrix = Matrix()
    private val src = Rect()
    private val dst = RectF()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val platePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val screenPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val layerPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val screenGlowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val plainPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glowSprite = buildGlowSprite()
    // The backdrop never changes: its two wide glows are painted once, dithered, at the buffer's own size, so the fine
    // dither is never enlarged into grain (it also shows through the screened plates).
    private val backdrop = Bitmap.createBitmap(
        max(1, (StageW * pixelScale).roundToInt()), max(1, (StageH * pixelScale).roundToInt()), Bitmap.Config.ARGB_8888
    ).also {
        val c = Canvas(it)
        c.scale(it.width / StageW, it.height / StageH)
        c.drawColor(Backdrop)
        c.drawRect(0f, 0f, StageW, StageH, backdropGlow(960f, 470f, 700f, 0.18f, 0x192F65))
        c.drawRect(0f, 0f, StageW, StageH, backdropGlow(760f, 670f, 360f, 0.045f, 0x782CDF))
        it.prepareToDraw()
    }
    private val stageRect = RectF(0f, 0f, StageW, StageH)
    private var originX = 0f
    private var originY = 0f
    private var drawScale = 1f

    /**
     * Draws the frame at [clock] seconds onto a canvas of [width] x [height] pixels. Past
     * [SettleSeconds] the mark stays in its settled live-arc state. [alpha] fades the whole frame, [content] only the
     * mark, lettering and light, leaving the stage. On a [handoff] the logo plate is there from the start.
     */
    fun draw(canvas: Canvas, width: Int, height: Int, clock: Float, alpha: Float = 1f, content: Float = 1f, handoff: Boolean = false) {
        val scale = min(width / StageW, height / StageH)
        val backdropFits = backdrop.width == width && backdrop.height == height
        // A fitting backdrop is opaque and covers every pixel, so an opaque frame needs no clear underneath it.
        if (!backdropFits || alpha < 0.997f || content <= 0.003f) canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (alpha <= 0.003f) return
        if (content <= 0.003f) {
            // Only the stage is left: fade it without an offscreen layer.
            drawStage(canvas, width, height, scale, (alpha * 255).toInt())
            return
        }
        val outer = if (alpha < 0.997f) canvas.saveLayerAlpha(null, (alpha * 255).toInt()) else canvas.save()
        if (!backdropFits) canvas.drawColor(Backdrop)
        originX = (width - StageW * scale) / 2f
        originY = (height - StageH * scale) / 2f
        drawScale = scale
        plainPaint.alpha = 255
        if (backdropFits) canvas.drawBitmap(backdrop, 0f, 0f, plainPaint)
        canvas.translate(originX, originY)
        canvas.scale(scale, scale)
        if (!backdropFits) canvas.drawBitmap(backdrop, null, stageRect, plainPaint)
        // Just the Netflix picture, fading in over the backdrop (on a hand-off it is already there).
        plainPaint.alpha = (ArcStartHandoff.plateFade(clock, handoff) * content * 255).toInt()
        placeArt(brandMark, MarkLeft, MarkTop, MarkWidth, MarkHeight)
        canvas.drawBitmap(brandMark, null, dst, plainPaint)
        canvas.restoreToCount(outer)
    }

    private fun drawStage(canvas: Canvas, width: Int, height: Int, scale: Float, stageAlpha: Int) {
        if (backdrop.width == width && backdrop.height == height) {
            // The backdrop is opaque and covers the whole buffer, so one translucent draw is the whole stage.
            plainPaint.alpha = stageAlpha
            canvas.drawBitmap(backdrop, 0f, 0f, plainPaint)
            return
        }
        val layer = canvas.saveLayerAlpha(null, stageAlpha)
        canvas.drawColor(Backdrop)
        canvas.translate((width - StageW * scale) / 2f, (height - StageH * scale) / 2f)
        canvas.scale(scale, scale)
        plainPaint.alpha = 255
        canvas.drawBitmap(backdrop, null, stageRect, plainPaint)
        canvas.restoreToCount(layer)
    }

    private fun drawPlate(canvas: Canvas, plate: Bitmap, paint: Paint) {
        placeArt(plate, LogoX, LogoY, LogoSize, LogoSize)
        canvas.drawBitmap(plate, null, dst, paint)
    }

    /**
     * Sets [dst] for art meant for the stage box at [x], [y] of [w] x [h]. Art prepared for this scale lands on whole
     * device pixels at its own size, so it is not resampled again; anything else fills the box.
     */
    private fun placeArt(art: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        if (abs(art.width - w * drawScale) <= 1.5f && abs(art.height - h * drawScale) <= 1.5f) {
            val left = ((originX + x * drawScale).roundToInt() - originX) / drawScale
            val top = ((originY + y * drawScale).roundToInt() - originY) / drawScale
            dst.set(left, top, left + art.width / drawScale, top + art.height / drawScale)
        } else {
            dst.set(x, y, x + w, y + h)
        }
    }

    private fun currents(canvas: Canvas, clock: Float) {
        if (clock > 3.68f) return
        canvas.save()
        canvas.clipPath(outside)
        val strandFrom = if (detail >= 2) -3 else -1
        val strandTo = if (detail >= 2) 3 else 1
        for (si in Streams.indices) {
            val s = Streams[si]
            val u = smooth((clock - s.start) / (s.end - s.start))
            val tail = smooth((clock - s.start - 0.82f) / (s.end - s.start - 0.47f))
            val envelope = smooth((clock - s.start) / 0.4f) * (1f - smooth((clock - s.end + 0.02f) / 0.30f))
            if (envelope < 0.002f || u <= tail) continue
            val from = floor(tail * (SampleCount - 1)).toInt()
            val to = min(SampleCount, ceil(u * (SampleCount - 1)).toInt() + 1)
            if (to - from < 2) continue
            for (strand in strandFrom..strandTo) {
                val pts = samples[si][strand + 3]
                val mid = from + ((to - from) * 0.6f).toInt()
                val depth = (0.62f + 0.38f * (pts[mid * 3 + 2] + 1f) / 2f) * s.depth
                val width = when (strand) { 0 -> 1.35f; -1 -> 0.72f; else -> 0.42f }
                val weight = when (strand) { 0 -> 1f; -1 -> 0.75f; else -> 0.45f }
                lightStroke(canvas, pts, from, to, width, envelope * depth * weight, s.hue, hot = strand == 0, bloom = strand == 0)
            }
            streamPoint(u, s, 0, point, 0)
            pinpoint(canvas, point[0], point[1], envelope * 0.7f, 1.5f, s.hue)
            val sparks = if (detail >= 2) 18 else 6
            for (j in 0 until sparks) {
                val pu = mix(tail, u, hash((s.seed + j * 9).toDouble()))
                streamPoint(pu, s, 0, point, 0)
                streamPoint(min(1f, pu + 0.005f), s, 0, point2, 0)
                val intensity = sin(PI.toFloat() * fract(clock * 0.27f + j * 0.179f + s.seed * 0.01f)).pow(7) * envelope * 0.7f
                val dx = point2[0] - point[0]; val dy = point2[1] - point[1]; val n = hypot(dx, dy).takeIf { it > 0f } ?: 1f
                val off = 4f + (j % 5) * 2f
                pinpoint(canvas, point[0] - dy / n * off, point[1] + dx / n * off, intensity, 0.55f + (j % 3) * 0.18f, s.hue)
            }
            val forks = if (detail >= 2) 4 else 2
            for (k in 0 until forks) {
                val pu = mix(tail, u, 0.22f + k * 0.21f)
                streamPoint(pu, s, 0, point, 0)
                streamPoint(pu + 0.005f, s, 0, point2, 0)
                val dx = point2[0] - point[0]; val dy = point2[1] - point[1]; val n = hypot(dx, dy).takeIf { it > 0f } ?: 1f
                val side = if (k % 2 == 1) -1f else 1f
                val len = 33f + 15f * noise(clock * 0.3f, s.seed + k)
                scratch[0] = point[0]; scratch[1] = point[1]
                for (j in 1..12) {
                    val v = j / 12f
                    val bend = (sin(v * PI.toFloat() * 0.7f) * len * 0.38f + fbm(v * 8f, s.seed + k) * 1.7f) * side
                    scratch[j * 3] = point[0] + dx / n * v * len - dy / n * bend
                    scratch[j * 3 + 1] = point[1] + dy / n * v * len + dx / n * bend
                }
                lightStroke(canvas, scratch, 0, 13, 0.64f, envelope * 0.62f, s.hue, hot = false, bloom = false)
            }
        }
        canvas.restore()
    }

    private fun charge(canvas: Canvas, clock: Float) {
        val rise = smooth((clock - 2.18f) / 1.47f)
        val settle = 1f - smooth((clock - Ignite) / 0.55f)
        var power = rise * (0.25f + 0.75f * settle)
        for (s in Streams) power += 0.14f * exp(-((clock - s.end) / 0.10f).pow(2))
        val burst = exp(-((clock - Ignite - 0.025f) / 0.095f).pow(2))
        glow(canvas, SourceX, SourceY, 27f + rise * 22f, power * 0.6f, 0x589EFF, fillPaint)
        glow(canvas, SourceX, SourceY, 13f + rise * 14f, power * 0.7f, 0x90E2FF, fillPaint)
        pinpoint(canvas, SourceX, SourceY, power + burst, 1.5f + rise * 1.8f, 0xECFCFF)
        if (clock > 2.25f && clock < 4.12f) {
            for (j in 0 until 7) {
                val a = j * 2.399f + clock * 0.32f
                val r = (7f + (j % 3) * 4f) * (1f - 0.38f * rise)
                val strength = power * (0.18f + 0.17f * sin(clock * 2f + j))
                for (i in 0..18) {
                    val u = i / 18f; val angle = a + u * 0.74f
                    scratch[i * 3] = SourceX + cos(angle) * r * (1f - u * 0.32f)
                    scratch[i * 3 + 1] = SourceY + sin(angle) * r * 0.67f * (1f - u * 0.32f)
                }
                lightStroke(canvas, scratch, 0, 19, 0.52f, strength, 0x9ADAFF, hot = false, bloom = false)
            }
        }
        if (burst > 0.01f) {
            glow(canvas, SourceX, SourceY, 72f, burst * 0.48f, 0x85CEFF, fillPaint)
            scratch[0] = SourceX - 23f; scratch[1] = SourceY
            scratch[3] = SourceX; scratch[4] = SourceY - 0.15f
            scratch[6] = SourceX + 27f; scratch[7] = SourceY
            lightStroke(canvas, scratch, 0, 3, 0.7f, burst * 0.5f, 0xB2DDFF, hot = true, bloom = true)
        }
    }

    private fun ignition(canvas: Canvas, clock: Float) {
        if (clock < Ignite) return
        val elapsed = clock - Ignite
        val lead = clamp(elapsed / 0.31f)
        val breath = 0.85f + 0.065f * sin(clock * 2.2f) + 0.035f * sin(clock * 7.1f)
        val settle = mix(0.8f, 0.20f, smooth((elapsed - 0.3f) / 0.7f))
        canvas.save()
        canvas.clipPath(cut)
        var n = boltPoints(Trunk, 0, Trunk.size / 2, lead, clock, 9)
        lightStroke(canvas, scratch, 0, n, 1.7f, breath * settle, 0x5AABFF, hot = true, bloom = true)
        for (j in Branches.indices) {
            val b = Branches[j]
            val p = clamp((elapsed - b.at * 0.26f - 0.07f) / 0.23f)
            n = boltPoints(b.points, 0, b.points.size / 2, p, clock, 20 + j)
            lightStroke(canvas, scratch, 0, n, b.width * LogoSize / 1254f, settle * (0.7f + 0.12f * sin(clock * 2f + j)), 0x7ABAFF, hot = j % 2 == 0, bloom = false)
        }
        if (elapsed > 0.4f) {
            val travelling = fract((clock - Ignite) * 0.37f)
            val from = floor(travelling * (Trunk.size / 2 - 2)).toInt()
            n = boltPoints(Trunk, from, min(from + 4, Trunk.size / 2), 1f, clock, 9)
            lightStroke(canvas, scratch, 0, n, 1.9f, 0.30f, 0xADEBFF, hot = true, bloom = detail >= 1)
        }
        for (j in 0 until 13) {
            val k = 2 + floor(j * 1.5f).toInt()
            val life = fract(clock * 0.23f + j * 0.179f)
            val a = sin(life * PI.toFloat()).pow(6) * 0.6f
            val x = LogoX + (Trunk[k * 2] + noise(j.toFloat(), 18) * 15f) / 1254f * LogoSize
            val y = LogoY + (Trunk[k * 2 + 1] - life * 14f) / 1254f * LogoSize
            pinpoint(canvas, x, y, a, 0.55f, 0xC8EDFF)
        }
        canvas.restore()
        glow(canvas, SourceX, SourceY, 17f, 0.48f * settle, 0x7BCAFF, fillPaint)
        pinpoint(canvas, SourceX, SourceY, 0.9f, 1.6f, 0xECFCFF)
    }

    /** Fills [scratch] with the jittered bolt between source points [from] and [to]; returns the point count. */
    private fun boltPoints(src: FloatArray, from: Int, to: Int, progress: Float, clock: Float, seed: Int): Int {
        val segments = to - from - 1
        val count = segments * 5
        val limit = clamp(progress) * count
        var n = 0
        for (i in 0..floor(limit).toInt()) {
            val v = i / 5f
            val k = min(segments - 1, floor(v).toInt())
            val f = v - k
            val edge = i == 0 || i == count
            val ax = src[(from + k) * 2]; val ay = src[(from + k) * 2 + 1]
            val bx = src[(from + k + 1) * 2]; val by = src[(from + k + 1) * 2 + 1]
            val x = mix(ax, bx, f) + if (edge) 0f else fbm(i * 0.71f + clock * 0.9f, seed) * 1.3f
            val y = mix(ay, by, f) + if (edge) 0f else noise(i * 0.87f - clock * 0.6f, seed + 5) * 0.9f
            scratch[n * 3] = LogoX + x / 1254f * LogoSize
            scratch[n * 3 + 1] = LogoY + y / 1254f * LogoSize
            n++
        }
        return n
    }

    private fun lightStroke(
        canvas: Canvas, pts: FloatArray, from: Int, to: Int, width: Float, power: Float, rgb: Int,
        hot: Boolean, bloom: Boolean
    ) {
        if (to - from < 2 || power < 0.005f) return
        val color = tone(rgb)
        strokePath.rewind()
        strokePath.moveTo(pts[from * 3], pts[from * 3 + 1])
        for (i in from + 1 until to) strokePath.lineTo(pts[i * 3], pts[i * 3 + 1])
        val ax = pts[from * 3]; val ay = pts[from * 3 + 1]
        val bx = pts[(to - 1) * 3] + 0.001f; val by = pts[(to - 1) * 3 + 1] + 0.001f
        val shader = gradients.getOrPut(color) {
            LinearGradient(0f, 0f, 1f, 0f, intArrayOf(
                color and 0xFFFFFF, withAlpha(color, 0x65), withAlpha(color, 0xE0), withAlpha(color, 0xFF), withAlpha(color, 0xA0)
            ), floatArrayOf(0f, 0.13f, 0.5f, 0.88f, 1f), Shader.TileMode.CLAMP)
        }
        val len = hypot(bx - ax, by - ay)
        gradientMatrix.setScale(len, len)
        gradientMatrix.postRotate(Math.toDegrees(kotlin.math.atan2((by - ay).toDouble(), (bx - ax).toDouble())).toFloat())
        gradientMatrix.postTranslate(ax, ay)
        shader.setLocalMatrix(gradientMatrix)
        strokePaint.shader = shader
        strokePaint.color = Color.WHITE
        if (bloom && detail >= 1) {
            strokePaint.alpha = (clamp(power * 0.68f * if (hot) 0.3f else 0.2f) * 255).toInt()
            strokePaint.strokeWidth = width * 16f
            canvas.drawPath(strokePath, strokePaint)
            if (detail >= 2) {
                strokePaint.alpha = (clamp(power * 0.68f * 0.055f) * 255).toInt()
                strokePaint.strokeWidth = width * 34f
                canvas.drawPath(strokePath, strokePaint)
            }
        }
        strokePaint.alpha = (clamp(power * 0.68f) * 255).toInt()
        strokePaint.strokeWidth = width * 5.2f
        canvas.drawPath(strokePath, strokePaint)
        strokePaint.shader = null
        strokePaint.color = if (hot) tone(0xE9FBFF) else color
        strokePaint.alpha = (clamp(power) * 255).toInt()
        strokePaint.strokeWidth = width
        canvas.drawPath(strokePath, strokePaint)
    }

    private fun backdropGlow(x: Float, y: Float, r: Float, alpha: Float, rgb: Int) = Paint().apply {
        val color = tone(rgb)
        shader = RadialGradient(x, y, r, intArrayOf(
            withAlpha(color, (0xD0 * alpha).toInt()), withAlpha(color, (0x50 * alpha).toInt()),
            withAlpha(color, (0x18 * alpha).toInt()), color and 0xFFFFFF
        ), floatArrayOf(0f, 0.16f, 0.48f, 1f), Shader.TileMode.CLAMP)
        isDither = true
    }

    /** Light-layer glows also get a wide faint halo, standing in for a blurred light pass. */
    private fun glow(canvas: Canvas, x: Float, y: Float, r: Float, alpha: Float, rgb: Int, paint: Paint) {
        if (alpha < 0.001f) return
        if (paint === fillPaint && detail >= 1) sprite(canvas, x, y, r * 2f, alpha * 0.3f, rgb, paint)
        sprite(canvas, x, y, r, alpha, rgb, paint)
    }

    private fun sprite(canvas: Canvas, x: Float, y: Float, r: Float, alpha: Float, rgb: Int, paint: Paint) {
        val color = tone(rgb)
        val previous = paint.colorFilter
        paint.colorFilter = tints.getOrPut(color.toLong() and 0xFFFFFFFFL) { PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }
        paint.alpha = (clamp(alpha) * 255).toInt()
        paint.isFilterBitmap = true
        src.set(0, 0, glowSprite.width, glowSprite.height)
        dst.set(x - r, y - r, x + r, y + r)
        canvas.drawBitmap(glowSprite, src, dst, paint)
        paint.colorFilter = previous
    }

    private fun pinpoint(canvas: Canvas, x: Float, y: Float, strength: Float, size: Float, rgb: Int) {
        if (strength < 0.002f) return
        fillPaint.color = tone(rgb)
        fillPaint.alpha = (clamp(strength) * 255).toInt()
        canvas.save()
        canvas.rotate(Math.toDegrees(0.1).toFloat(), x, y)
        dst.set(x - size * 1.9f, y - size, x + size * 1.9f, y + size)
        canvas.drawOval(dst, fillPaint)
        canvas.restore()
    }

    private class Stream(val phase: Float, val start: Float, val end: Float, val seed: Int, val hue: Int, val depth: Float)

    private class Branch(val at: Float, val width: Float, val points: FloatArray)

    companion object {
        const val StageW = 1920f
        const val StageH = 1080f
        const val Ignite = 3.7f
        /** The discharge has landed and the mark breathes quietly from here on. */
        const val SettleSeconds = 4.6f
        /** The picture is 1040 x 585 (16:9): half the stage in each direction, centred. */
        private const val MarkLeft = 480f
        private const val MarkTop = 270f
        private const val MarkWidth = 960f
        private const val MarkHeight = 540f
        private const val LogoX = 700f
        private const val LogoY = 180f
        private const val LogoSize = 520f
        private const val SourceX = LogoX + 787f / 1254f * LogoSize
        private const val SourceY = LogoY + 186f / 1254f * LogoSize
        private const val LetterLeft = 616f
        private const val LightMargin = 160f
        private const val LetterTop = 720f
        private const val LetterWidth = 686f
        private const val SampleCount = 481
        private val Backdrop = Color.rgb(3, 5, 10)

        private const val CutPath = "M 650 50 C 725 135 742 360 800 465 C 854 590 948 690 1040 690 Q 1085 695 1104 672 C 1044 708 1001 629 969 553 C 910 443 868 320 815 220 Q 745 123 650 50 Z"
        private const val ShapePath = "M 496 73 Q 587 -8 657 59 L 1085 442 Q 1192 538 1148 629 Q 1140 655 1105 674 L 706 1052 Q 604 1151 516 1059 L 120 685 Q 11 586 73 488 Z"

        private val Streams = arrayOf(
            Stream(0.16f, 0.04f, 2.68f, 11, 0x78DFFF, 1f),
            Stream(2.05f, 0.21f, 2.92f, 24, 0xA694FF, 0.62f),
            Stream(3.96f, 0.49f, 3.13f, 38, 0x66CAFF, 0.86f),
            Stream(5.28f, 0.76f, 3.30f, 51, 0xB0BFFF, 0.5f)
        )

        private val Trunk = floatArrayOf(
            787f, 186f, 778f, 205f, 781f, 224f, 771f, 242f, 787f, 264f, 781f, 286f, 803f, 309f, 800f, 337f,
            830f, 358f, 840f, 382f, 824f, 404f, 839f, 425f, 835f, 449f, 872f, 474f, 876f, 497f, 900f, 514f,
            901f, 541f, 925f, 555f, 930f, 581f, 956f, 596f, 963f, 620f, 991f, 641f, 1000f, 668f
        )

        private val Branches = arrayOf(
            Branch(0.06f, 1.5f, floatArrayOf(779f, 213f, 764f, 219f, 762f, 231f, 744f, 237f, 735f, 257f)),
            Branch(0.19f, 2.3f, floatArrayOf(788f, 291f, 778f, 312f, 763f, 322f, 762f, 345f, 750f, 362f)),
            Branch(0.30f, 1.1f, floatArrayOf(800f, 337f, 819f, 340f, 825f, 354f, 843f, 360f, 845f, 378f)),
            Branch(0.42f, 2.0f, floatArrayOf(831f, 401f, 817f, 427f, 822f, 450f, 808f, 470f, 811f, 489f)),
            Branch(0.51f, 1.15f, floatArrayOf(842f, 454f, 865f, 450f, 879f, 469f, 891f, 471f, 905f, 493f)),
            Branch(0.62f, 1.8f, floatArrayOf(894f, 515f, 876f, 536f, 882f, 558f, 867f, 579f, 874f, 606f)),
            Branch(0.77f, 1.55f, floatArrayOf(932f, 585f, 920f, 608f, 926f, 623f, 919f, 650f)),
            Branch(0.81f, 0.95f, floatArrayOf(947f, 598f, 974f, 596f, 985f, 613f, 1003f, 622f, 1010f, 641f))
        )

        /**
         * Decodes, recolours and sizes the art for [pixelScale] device pixels per stage unit (1 for a 1080p buffer), so
         * the mark and the lettering are drawn pixel for pixel. Blocking; call off the main thread. Stops with a
         * [CancellationException] between images once [keepGoing] turns false.
         */
        fun prepare(
            resources: Resources,
            palette: ArcPalette,
            pixelScale: Float = 1f,
            keepGoing: () -> Boolean = { true }
        ): ArcIgnitionScene {
            val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            fun art(id: Int, recolor: Boolean, stageWidth: Float, sharpen: Boolean): Bitmap {
                if (!keepGoing()) throw CancellationException("no longer wanted")
                val decoded = BitmapFactory.decodeResource(resources, id, options)
                val sw = decoded.width
                val sh = decoded.height
                var pixels = IntArray(sw * sh)
                decoded.getPixels(pixels, 0, sw, 0, 0, sw, sh)
                decoded.recycle()
                val tw = (stageWidth * pixelScale).roundToInt()
                var w = sw
                var h = sh
                if (tw in 1 until sw) {
                    w = tw
                    h = max(1, (sh * tw.toFloat() / sw).roundToInt())
                    pixels = ArcPixels.areaResample(pixels, sw, sh, w, h)
                }
                if (recolor && !palette.isOriginal) palette.mapPixels(pixels)
                if (sharpen) ArcPixels.sharpen(pixels, w, h)
                return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).also { it.prepareToDraw() }
            }
            val glass = palette == ArcPalette.Glass
            return ArcIgnitionScene(
                brandMark = art(R.drawable.netflix_icon, recolor = false, MarkWidth, sharpen = false),
                palette = palette,
                glassPlate = glass,
                pixelScale = pixelScale
            )
        }

        private fun path(data: String): Path = PathParser.createPathFromPathData(data)

        private fun buildGlowSprite(): Bitmap {
            val size = 128
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(size / 2f, size / 2f, size / 2f,
                    intArrayOf(withAlpha(-1, 0xD0), withAlpha(-1, 0x50), withAlpha(-1, 0x18), 0x00FFFFFF),
                    floatArrayOf(0f, 0.16f, 0.48f, 1f), Shader.TileMode.CLAMP)
            }
            Canvas(bitmap).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            bitmap.prepareToDraw()
            return bitmap
        }

        private fun withAlpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or (alpha shl 24)

        private fun clamp(v: Float, a: Float = 0f, b: Float = 1f) = max(a, min(b, v))
        private fun mix(a: Float, b: Float, u: Float) = a + (b - a) * u
        internal fun smooth(value: Float): Float { val v = clamp(value); return v * v * (3f - 2f * v) }
        private fun fract(n: Float) = n - floor(n)
        private fun fract(n: Double) = n - floor(n)
        private fun hash(n: Double): Float = fract(sin(n * 127.1 + 311.7) * 43758.5453).toFloat()
        private fun noise(x: Float, seed: Int): Float {
            val a = floor(x.toDouble())
            val u = smooth(fract(x))
            return mix(hash(a + seed * 97), hash(a + 1 + seed * 97), u) * 2f - 1f
        }
        private fun fbm(x: Float, s: Int) = noise(x, s) * 0.62f + noise(x * 2.13f, s + 7) * 0.26f + noise(x * 4.37f, s + 13) * 0.12f

        /** The fixed path of one current; strand 0 is the main filament. */
        private fun streamPoint(uIn: Float, s: Stream, strand: Int, out: FloatArray, at: Int) {
            val clock = 1f
            val u = clamp(uIn)
            val v = 1f - u
            val envelope = sin(PI.toFloat() * u)
            val radius = 660f * v.pow(1.24f)
            val angle = s.phase + u * 5.25f + 0.1f * clock * v + fbm(u * 3f + clock * 0.08f, s.seed + 50) * 0.095f * sin(u * PI.toFloat())
            val cx = mix(958f, SourceX, u.pow(1.55f))
            val cy = mix(494f, SourceY, u.pow(1.8f))
            val slope = 0.47f + 0.05f * sin(s.phase)
            val co = cos(angle); val si = sin(angle)
            val braid = (strand * 4.3f + fbm(u * 11f + clock * 0.32f, s.seed + strand) * 7.5f) * v.pow(0.4f)
            val turbulence = fbm(u * 32f - clock * 0.2f, s.seed) * 12.5f * envelope + fbm(u * 114f + clock * 0.15f, s.seed + 2) * 3.7f * envelope
            val off = (braid + turbulence) * v.pow(0.22f)
            out[at] = cx + co * radius - si * off
            out[at + 1] = cy + si * radius * slope + co * off
            out[at + 2] = sin(angle) * v
        }
    }
}
