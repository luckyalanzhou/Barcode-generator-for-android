package com.luckyalanzhou.barcodegenerator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** 当前应用页面上的轻量烟花层：火箭从底部升空，随后随机绽放。 */
internal class InlineFireworksView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rockets = mutableListOf<InlineRocket>()
    private val bursts = mutableListOf<InlineBurst>()
    private val colors = intArrayOf(0xffff718d.toInt(), 0xffffc75a.toInt(), 0xff78d7ff.toInt(), 0xffc09cff.toInt(), 0xff80e7a5.toInt())
    private var lastAutoLaunch = 0L
    private var lastDrawAt = 0L
    private var hintUntil = System.currentTimeMillis() + 3_500L

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val delta = ((now - lastDrawAt).coerceIn(0L, 33L)) / 1_000f
        lastDrawAt = now
        if (now - lastAutoLaunch >= 1_350L && width > 0 && height > 0) {
            val number = (now / 1_350L).toInt()
            launch(width * (.14f + (number % 6) * .145f), height * (.18f + (number % 4) * .13f), colors[number.mod(colors.size)])
            lastAutoLaunch = now
        }
        rockets.forEach { rocket ->
            rocket.update(delta)
            rocket.draw(canvas, paint)
            if (rocket.finished) {
                bursts += InlineBurst(rocket.x, rocket.y, rocket.color, density, rocket.style)
            }
        }
        rockets.removeAll { it.finished }
        bursts.forEach { it.update(delta); it.draw(canvas, paint) }
        bursts.removeAll { it.finished }
        if (now < hintUntil) {
            paint.color = 0xddebf6ff.toInt(); paint.textSize = 15f * density; paint.textAlign = Paint.Align.CENTER
            canvas.drawText("轻触夜空，从地面发射一朵烟花", width / 2f, height - 44f * density, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        postInvalidateOnAnimation()
    }

    private fun launch(targetX: Float, targetY: Float, color: Int) {
        val launchX = (targetX + ((targetX / width - .5f) * -46f * density)).coerceIn(18f * density, width - 18f * density)
        val style = ((targetX.toInt() * 31) xor (targetY.toInt() * 17) xor System.nanoTime().toInt()).and(7)
        rockets += InlineRocket(launchX, height - 24f * density, targetX, targetY, color, density, style)
        while (rockets.size > 5) rockets.removeAt(0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val index = ((event.x.toInt() + event.y.toInt()) / 41).mod(colors.size)
            launch(event.x, event.y.coerceIn(height * .14f, height * .76f), colors[index])
            hintUntil = 0L
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

private class InlineRocket(
    private val startX: Float, private val startY: Float, private val targetX: Float, private val targetY: Float,
    val color: Int, private val density: Float, val style: Int
) {
    var x = startX; var y = startY
    private var progress = 0f
    var finished = false
        private set

    fun update(delta: Float) { progress += delta / .7f; if (progress >= 1f) { progress = 1f; finished = true }; x = startX + (targetX - startX) * progress; y = startY + (targetY - startY) * progress }
    fun draw(canvas: Canvas, paint: Paint) {
        paint.strokeWidth = 1.5f * density; paint.color = 0x88ffffff.toInt()
        canvas.drawLine(x, y + 18f * density, x - (targetX - startX) * .04f, y + 42f * density, paint)
        paint.color = color; canvas.drawCircle(x, y, 2.8f * density, paint)
    }
}

/** 八种随机花型：圆环、菊花、柳枝、星芒、牡丹、爱心、瀑布和闪烁金雨。 */
private class InlineBurst(private val originX: Float, private val originY: Float, private val color: Int, density: Float, style: Int) {
    private val style = style
    private val dots = Array(if (style == 4 || style == 6) 72 else 56) { index ->
        val dotCount = if (style == 4 || style == 6) 72 else 56
        val angle = Math.PI * 2.0 * index / dotCount
        val speed = when (style) {
            0 -> 74f + (index % 4) * 3f
            1 -> 42f + (index % 8) * 12f
            2 -> 58f + (index % 6) * 11f
            3 -> if (index % 7 == 0) 142f else 48f
            4 -> if (index % 3 == 0) 118f else 62f + (index % 5) * 9f
            5 -> 7f
            6 -> 64f + (index % 4) * 15f
            else -> 44f + (index % 7) * 10f
        } * density
        val heartX = (16.0 * sin(angle) * sin(angle) * sin(angle)).toFloat()
        val heartY = (-(13.0 * cos(angle) - 5.0 * cos(2.0 * angle) - 2.0 * cos(3.0 * angle) - cos(4.0 * angle))).toFloat()
        when (style) {
            5 -> InlineDot(heartX * 7.2f * density, heartY * 7.2f * density, false)
            6 -> InlineDot(cos(angle).toFloat() * speed * .62f, sin(angle).toFloat() * speed * .92f, true)
            7 -> InlineDot(cos(angle).toFloat() * speed * .7f, sin(angle).toFloat() * speed, true)
            else -> InlineDot(cos(angle).toFloat() * speed, sin(angle).toFloat() * speed, style == 2)
        }
    }
    private var age = 0f
    var finished = false
        private set

    fun update(delta: Float) {
        age += delta
        dots.forEach { dot ->
            dot.x += dot.vx * delta; dot.y += dot.vy * delta
            dot.vy += if (dot.willow) 60f * delta else 38f * delta
            dot.vx *= if (dot.willow) .982f else .991f; dot.vy *= if (dot.willow) .982f else .991f
        }
        finished = age > if (dots.first().willow || style == 7) 2.05f else 1.45f
    }

    fun draw(canvas: Canvas, paint: Paint) {
        val life = if (dots.first().willow || style == 7) 2.05f else 1.45f
        val alpha = ((1f - age / life).coerceIn(0f, 1f) * 255).toInt()
        dots.forEachIndexed { index, dot ->
            val dotColor = if (style == 7 && index % 3 != 0) 0xffffd66b.toInt() else color
            paint.color = (dotColor and 0x00ffffff) or (alpha shl 24)
            val radius = if (style == 4 && index % 3 == 0) 2.35f else 1.55f + index % 3 * .38f
            canvas.drawCircle(originX + dot.x, originY + dot.y, radius, paint)
        }
    }

    private data class InlineDot(var vx: Float, var vy: Float, val willow: Boolean, var x: Float = 0f, var y: Float = 0f)
}
