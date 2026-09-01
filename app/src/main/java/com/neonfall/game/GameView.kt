package com.neonfall.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * NeonFall core loop. Everything (rendering, physics, UI overlays) lives here
 * so the game has zero asset dependencies and runs on any device.
 *
 * Difficulty is a pure function of [stage], so stages are unlimited.
 */
class GameView(context: Context, private val onExit: () -> Unit) : View(context) {

    // ---------- tuning ----------
    private companion object {
        const val BASE_STAGE_SECONDS = 12f
        const val BASE_SPEED = 380f        // px/sec at 1800px tall screen
        const val SPEED_PER_STAGE = 42f
        const val MIN_SPAWN = 0.16f
        const val STAGE_CLEAR_BONUS = 100
        const val COIN_VALUE = 10
        const val NEAR_MISS_VALUE = 3
    }

    private enum class State { READY, PLAYING, PAUSED, GAME_OVER }

    private class Block(var x: Float, var y: Float, val w: Float, val h: Float, val speed: Float) {
        var scored = false
    }
    private class Coin(var x: Float, var y: Float, val r: Float, val speed: Float)
    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val color: Int)
    private class Star(val x: Float, val y: Float, val size: Float, val speed: Float)

    private val prefs = Prefs(context)
    private var state = State.READY
    private var running = true
    private var lastTime = 0L
    private var time = 0f

    // player
    private var px = 0f
    private var playerW = 0f
    private var playerH = 0f
    private var dir = 0
    private var tilt = 0f

    // world
    private val blocks = ArrayList<Block>()
    private val coins = ArrayList<Coin>()
    private val particles = ArrayList<Particle>()
    private val stars = ArrayList<Star>()
    private var spawnTimer = 0f
    private var coinTimer = 0f
    private var shake = 0f
    private var bannerTimer = 0f
    private var bannerText = ""

    // progress
    private var stage = 1
    private var stageTime = 0f
    private var score = 0
    private var newBest = false

    private val stageDuration get() = BASE_STAGE_SECONDS + min(stage, 30) * 0.4f
    private val blockSpeed get() = BASE_SPEED + stage * SPEED_PER_STAGE
    private val spawnInterval get() = max(MIN_SPAWN, 0.9f - stage * 0.04f)

    // paints
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
    }
    private val textDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(138, 151, 173)
        textAlign = Paint.Align.CENTER
    }
    private var bgShader: Shader? = null

    private val cCyan = Color.rgb(77, 217, 255)
    private val cCoral = Color.rgb(255, 77, 94)
    private val cGold = Color.rgb(255, 201, 60)

    private val pauseRect = RectF()

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            val dt = if (lastTime == 0L) 0f else min((now - lastTime) / 1e9f, 0.05f)
            lastTime = now
            update(dt)
            invalidate()
            postOnAnimation(this)
        }
    }

    fun pause() {
        running = false
        removeCallbacks(loop)
        if (state == State.PLAYING) state = State.PAUSED
    }

    fun resume() {
        running = true
        lastTime = 0L
        postOnAnimation(loop)
    }

    /** @return true if consumed (i.e. we paused instead of leaving). */
    fun handleBack(): Boolean {
        return when (state) {
            State.PLAYING -> { state = State.PAUSED; true }
            State.PAUSED -> { state = State.PLAYING; true }
            else -> false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        playerW = w * 0.12f
        playerH = playerW * 0.85f
        px = (w - playerW) / 2f
        text.textSize = w * 0.06f
        textDim.textSize = w * 0.042f
        bgShader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            Color.rgb(16, 26, 46), Color.rgb(6, 10, 20), Shader.TileMode.CLAMP)
        stars.clear()
        repeat(60) {
            stars += Star(Random.nextFloat() * w, Random.nextFloat() * h,
                1f + Random.nextFloat() * 2.5f, 20f + Random.nextFloat() * 60f)
        }
        val s = w * 0.11f
        pauseRect.set(w - s - w * 0.04f, h * 0.035f, w - w * 0.04f, h * 0.035f + s)
    }

    private fun startGame() {
        blocks.clear(); coins.clear(); particles.clear()
        stage = 1; stageTime = 0f; score = 0; newBest = false
        spawnTimer = 0f; coinTimer = 0f; shake = 0f
        px = (width - playerW) / 2f
        dir = 0
        showBanner("STAGE 1")
        state = State.PLAYING
    }

    private fun showBanner(s: String) { bannerText = s; bannerTimer = 1.4f }

    private fun vibrate(ms: Long) {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(ms)
    }

    private fun burst(x: Float, y: Float, color: Int, n: Int, power: Float) {
        repeat(n) {
            val a = Random.nextFloat() * 6.2832f
            val sp = power * (0.3f + Random.nextFloat())
            particles += Particle(x, y, kotlin.math.cos(a) * sp, sin(a) * sp, 0.5f + Random.nextFloat() * 0.5f, color)
        }
    }

    // ---------- update ----------
    private fun update(dt: Float) {
        time += dt
        if (bannerTimer > 0f) bannerTimer -= dt
        if (shake > 0f) shake = max(0f, shake - dt * 40f)
        updateParticles(dt)
        if (state != State.PLAYING || width == 0) return

        val scale = height / 1800f

        // player
        val moveSpeed = width * 1.15f
        px = (px + dir * moveSpeed * dt).coerceIn(0f, width - playerW)
        tilt += ((dir * 0.18f) - tilt) * min(1f, dt * 14f)

        // stage timer
        stageTime += dt
        if (stageTime >= stageDuration) {
            stage++
            stageTime = 0f
            score += STAGE_CLEAR_BONUS
            showBanner("STAGE $stage")
            burst(width / 2f, height * 0.3f, cCyan, 30, width * 0.5f)
            vibrate(20)
        }

        // spawn hazards
        spawnTimer += dt
        if (spawnTimer >= spawnInterval) {
            spawnTimer = 0f
            val bw = width * (0.08f + Random.nextFloat() * 0.15f)
            val bh = bw * (0.45f + Random.nextFloat() * 0.6f)
            val spd = blockSpeed * scale * (0.85f + Random.nextFloat() * 0.3f)
            blocks += Block(Random.nextFloat() * (width - bw), -bh, bw, bh, spd)
        }

        // spawn coins
        coinTimer += dt
        if (coinTimer >= 1.5f) {
            coinTimer = 0f
            val r = width * 0.028f
            coins += Coin(r + Random.nextFloat() * (width - 2 * r), -r, r, blockSpeed * scale * 0.8f)
        }

        val py = playerY()
        val hit = RectF(px + 6f, py + 6f, px + playerW - 6f, py + playerH - 6f)
        val near = RectF(px - playerW * 0.35f, py - playerH * 0.4f, px + playerW * 1.35f, py + playerH)

        // hazards
        val bi = blocks.iterator()
        while (bi.hasNext()) {
            val b = bi.next()
            b.y += b.speed * dt
            val r = RectF(b.x, b.y, b.x + b.w, b.y + b.h)
            if (RectF.intersects(r, hit)) { gameOver(); return }
            if (!b.scored && b.y > py && RectF.intersects(r, near)) {
                b.scored = true; score += NEAR_MISS_VALUE
            }
            if (b.y > height) { bi.remove(); if (!b.scored) score += 1 }
        }

        // coins
        val ci = coins.iterator()
        while (ci.hasNext()) {
            val c = ci.next()
            c.y += c.speed * dt
            val r = RectF(c.x - c.r, c.y - c.r, c.x + c.r, c.y + c.r)
            if (RectF.intersects(r, hit)) {
                ci.remove(); score += COIN_VALUE
                burst(c.x, c.y, cGold, 10, width * 0.35f)
                vibrate(8)
            } else if (c.y - c.r > height) ci.remove()
        }

        // engine trail
        if (Random.nextFloat() < 0.6f) {
            particles += Particle(px + playerW / 2f + (Random.nextFloat() - 0.5f) * playerW * 0.4f,
                py + playerH, (Random.nextFloat() - 0.5f) * 40f, 200f + Random.nextFloat() * 100f,
                0.35f, cCyan)
        }
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) { it.remove(); continue }
            p.x += p.vx * dt; p.y += p.vy * dt
            p.vy += 300f * dt
        }
    }

    private fun gameOver() {
        state = State.GAME_OVER
        shake = 24f
        burst(px + playerW / 2f, playerY() + playerH / 2f, cCoral, 40, width * 0.7f)
        burst(px + playerW / 2f, playerY() + playerH / 2f, cCyan, 25, width * 0.5f)
        vibrate(120)
        prefs.gamesPlayed += 1
        if (score > prefs.bestScore) { prefs.bestScore = score; newBest = true }
        if (stage > prefs.bestStage) prefs.bestStage = stage
    }

    private fun playerY() = height - playerH * 2.2f

    // ---------- draw ----------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()

        paint.shader = bgShader
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        canvas.save()
        if (shake > 0f) canvas.translate((Random.nextFloat() - 0.5f) * shake, (Random.nextFloat() - 0.5f) * shake)

        // stars (parallax scroll)
        paint.color = Color.argb(120, 255, 255, 255)
        val scroll = if (state == State.PLAYING) time else 0f
        for (s in stars) {
            val y = (s.y + scroll * s.speed * (1f + stage * 0.05f)) % h
            canvas.drawCircle(s.x, y, s.size, paint)
        }

        // particles
        for (p in particles) {
            paint.color = p.color
            paint.alpha = (255 * min(1f, p.life * 2f)).toInt()
            canvas.drawCircle(p.x, p.y, w * 0.008f * (0.5f + p.life), paint)
        }
        paint.alpha = 255

        // coins
        for (c in coins) {
            glow.color = cGold; glow.alpha = 60
            canvas.drawCircle(c.x, c.y, c.r * 1.8f, glow)
            paint.color = cGold
            canvas.drawCircle(c.x, c.y, c.r, paint)
            paint.color = Color.rgb(255, 235, 160)
            canvas.drawCircle(c.x - c.r * 0.3f, c.y - c.r * 0.3f, c.r * 0.3f, paint)
        }

        // hazards
        for (b in blocks) {
            glow.color = cCoral; glow.alpha = 50
            canvas.drawRoundRect(b.x - 6f, b.y - 6f, b.x + b.w + 6f, b.y + b.h + 6f, 18f, 18f, glow)
            paint.color = cCoral
            canvas.drawRoundRect(b.x, b.y, b.x + b.w, b.y + b.h, 12f, 12f, paint)
            paint.color = Color.rgb(255, 140, 150)
            canvas.drawRoundRect(b.x + 6f, b.y + 5f, b.x + b.w - 6f, b.y + 10f, 4f, 4f, paint)
        }

        // player
        if (state != State.GAME_OVER) {
            val py = playerY()
            canvas.save()
            canvas.rotate(tilt * 57f, px + playerW / 2f, py + playerH / 2f)
            glow.color = cCyan; glow.alpha = 70
            canvas.drawRoundRect(px - 10f, py - 10f, px + playerW + 10f, py + playerH + 10f, 24f, 24f, glow)
            paint.color = cCyan
            canvas.drawRoundRect(px, py, px + playerW, py + playerH, 16f, 16f, paint)
            paint.color = Color.rgb(185, 239, 255)
            canvas.drawRoundRect(px + 8f, py + 7f, px + playerW * 0.45f, py + 13f, 4f, 4f, paint)
            canvas.restore()
        }
        canvas.restore()

        drawHud(canvas, w, h)

        when (state) {
            State.READY -> overlay(canvas, w, h, "NEONFALL", "Hold left or right to slide", "Tap to start")
            State.PAUSED -> overlay(canvas, w, h, "PAUSED", "Stage $stage  ·  Score $score", "Tap to resume  ·  Back to quit")
            State.GAME_OVER -> overlay(canvas, w, h, "GAME OVER",
                (if (newBest) "New best!  " else "") + "Stage $stage  ·  Score $score",
                "Tap to retry  ·  Back for menu")
            else -> {}
        }

        if (bannerTimer > 0f && state == State.PLAYING) {
            val a = min(1f, bannerTimer * 2f)
            text.textSize = w * 0.11f
            text.alpha = (255 * a).toInt()
            canvas.drawText(bannerText, w / 2f, h * 0.3f, text)
            text.alpha = 255
            text.textSize = w * 0.06f
        }
    }

    private fun drawHud(canvas: Canvas, w: Float, h: Float) {
        val top = h * 0.035f
        // progress bar
        paint.color = Color.rgb(31, 43, 68)
        canvas.drawRoundRect(w * 0.04f, top, w * 0.96f, top + 8f, 4f, 4f, paint)
        paint.color = Color.rgb(80, 220, 130)
        canvas.drawRoundRect(w * 0.04f, top, w * 0.04f + w * 0.92f * (stageTime / stageDuration), top + 8f, 4f, 4f, paint)

        text.textAlign = Paint.Align.LEFT
        text.textSize = w * 0.05f
        canvas.drawText("Stage $stage", w * 0.04f, top + text.textSize * 1.9f, text)
        text.textAlign = Paint.Align.CENTER
        text.textSize = w * 0.07f
        canvas.drawText(score.toString(), w / 2f, top + text.textSize * 1.55f, text)
        text.textSize = w * 0.06f

        // pause button
        if (state == State.PLAYING) {
            paint.color = Color.argb(80, 255, 255, 255)
            val bw = pauseRect.width() * 0.16f
            val cx = pauseRect.centerX(); val cy = pauseRect.centerY()
            canvas.drawRoundRect(cx - bw * 1.6f, cy - bw * 2f, cx - bw * 0.4f, cy + bw * 2f, 4f, 4f, paint)
            canvas.drawRoundRect(cx + bw * 0.4f, cy - bw * 2f, cx + bw * 1.6f, cy + bw * 2f, 4f, 4f, paint)
        }
    }

    private fun overlay(canvas: Canvas, w: Float, h: Float, title: String, sub: String, hint: String) {
        paint.color = Color.argb(175, 4, 8, 16)
        canvas.drawRect(0f, 0f, w, h, paint)
        text.textSize = w * 0.14f
        canvas.drawText(title, w / 2f, h * 0.44f, text)
        text.textSize = w * 0.06f
        textDim.color = Color.rgb(242, 245, 249)
        canvas.drawText(sub, w / 2f, h * 0.52f, textDim)
        textDim.color = Color.rgb(138, 151, 173)
        canvas.drawText(hint, w / 2f, h * 0.60f, textDim)
    }

    // ---------- input ----------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                when (state) {
                    State.READY, State.GAME_OVER -> { startGame(); return true }
                    State.PAUSED -> { state = State.PLAYING; return true }
                    State.PLAYING -> {
                        if (pauseRect.contains(e.x, e.y)) { state = State.PAUSED; dir = 0; return true }
                        dir = if (e.x < width / 2f) -1 else 1
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (state == State.PLAYING) dir = if (e.x < width / 2f) -1 else 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dir = 0
        }
        return true
    }
}
