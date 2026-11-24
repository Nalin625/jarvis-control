package com.jarvis.control

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * ARCADE: six small games in the Jarvis neon look. Nothing here needs the laptop or the internet.
 * Best scores are kept on the phone. Back goes game -> menu -> home.
 */
class GamesActivity : Activity() {

    private lateinit var frame: FrameLayout
    private var current: GameView? = null
    private lateinit var menu: MenuView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#05080B")
        window.navigationBarColor = Color.parseColor("#05080B")
        frame = FrameLayout(this)
        frame.setBackgroundColor(Color.parseColor("#05080B"))
        menu = MenuView(this) { open(it) }
        frame.addView(menu, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        intent.getStringExtra("game")?.let { open(it) }
    }

    private fun open(id: String) {
        val g: GameView = when (id) {
            "snake" -> SnakeGame(this)
            "2048" -> Game2048(this)
            "tictac" -> TicTacGame(this)
            "memory" -> MemoryGame(this)
            "reaction" -> ReactionGame(this)
            "rush" -> TapRushGame(this)
            else -> return
        }
        g.onExit = { close() }
        current = g
        frame.addView(g, FrameLayout.LayoutParams(-1, -1))
        menu.visibility = View.GONE
    }

    private fun close() {
        current?.let { it.stop(); frame.removeView(it) }
        current = null
        menu.visibility = View.VISIBLE
        menu.refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current != null) close() else super.onBackPressed()
    }

    override fun onPause() { super.onPause(); current?.pauseGame() }
    override fun onDestroy() { current?.stop(); super.onDestroy() }

    // ------------------------------------------------------------------ shared look
    companion object {
        val BG = Color.parseColor("#05080B")
        val CARD = Color.parseColor("#0C171D")
        val EDGE = Color.parseColor("#1E4452")
        val CYAN = Color.parseColor("#4FE3FF")
        val VIOLET = Color.parseColor("#8A5CFF")
        val GREEN = Color.parseColor("#3DDC84")
        val RED = Color.parseColor("#FF4D6A")
        val ORANGE = Color.parseColor("#FF7A5C")
        val TEXT = Color.parseColor("#DCEEF2")
        val DIM = Color.parseColor("#6F8B93")
        val NEON = intArrayOf(CYAN, VIOLET, GREEN, ORANGE, RED, Color.parseColor("#2E7BFF"), Color.parseColor("#FFD84F"), Color.parseColor("#FF5CD6"))

        fun withAlpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

        val GAMES = listOf(
            Triple("snake", "SNAKE", "SWIPE TO STEER"),
            Triple("2048", "2048", "SWIPE TO MERGE"),
            Triple("tictac", "TIC-TAC-TOE", "BEAT JARVIS"),
            Triple("memory", "MEMORY", "MATCH THE PAIRS"),
            Triple("reaction", "REACTION", "HOW FAST ARE YOU"),
            Triple("rush", "TAP RUSH", "30 SECONDS")
        )
    }

    // ------------------------------------------------------------------ base classes
    abstract class GameView(ctx: Context, val id: String, private val lowerBetter: Boolean = false) : View(ctx) {
        var onExit: () -> Unit = {}
        protected val d = ctx.resources.displayMetrics.density
        private val prefs = ctx.getSharedPreferences("jarvis_arcade", Context.MODE_PRIVATE)
        protected val ui = Handler(Looper.getMainLooper())
        protected val p = Paint(Paint.ANTI_ALIAS_FLAG)
        protected val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        protected var running = true

        var best: Int
            get() = prefs.getInt("best_$id", if (lowerBetter) 0 else 0)
            private set(v) { prefs.edit().putInt("best_$id", v).apply() }

        /** Returns true if this is a new best. */
        protected fun submit(score: Int): Boolean {
            val b = best
            val better = if (lowerBetter) (b == 0 || score < b) else score > b
            if (better && score > 0) { best = score; return true }
            return false
        }

        open fun pauseGame() {}
        open fun stop() { running = false; ui.removeCallbacksAndMessages(null) }

        protected fun buzz() { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

        protected fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = TEXT) {
            tp.textSize = size * d; tp.color = color; c.drawText(s, x, y, tp)
        }

        /** Title bar with the back button; returns the y where the playfield may start. */
        protected fun header(c: Canvas, title: String, line: String): Float {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = CYAN
            val bx = 28 * d; val by = 44 * d
            val a = Path().apply { moveTo(bx + 6 * d, by - 9 * d); lineTo(bx - 4 * d, by); lineTo(bx + 6 * d, by + 9 * d) }
            c.drawPath(a, p)
            text(c, title, width / 2f, by + 5 * d, 18f, CYAN)
            text(c, line, width / 2f, by + 30 * d, 12f, DIM)
            return by + 48 * d
        }

        protected fun backHit(x: Float, y: Float) = x < 70 * d && y < 90 * d

        protected fun glowRect(c: Canvas, r: RectF, color: Int, fillA: Int = 70, rad: Float = 8f) {
            p.style = Paint.Style.FILL; p.color = withAlpha(color, fillA); c.drawRoundRect(r, rad * d, rad * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f * d; p.color = withAlpha(color, 40); c.drawRoundRect(r, rad * d, rad * d, p)
            p.strokeWidth = 1.6f * d; p.color = color; c.drawRoundRect(r, rad * d, rad * d, p)
        }

        protected fun button(c: Canvas, r: RectF, label: String, color: Int = CYAN) {
            glowRect(c, r, color, 40, 10f); text(c, label, r.centerX(), r.centerY() + 5 * d, 14f, color)
        }
    }

    // ------------------------------------------------------------------ menu
    class MenuView(ctx: Context, val pick: (String) -> Unit) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        private val prefs = ctx.getSharedPreferences("jarvis_arcade", Context.MODE_PRIVATE)
        private val cards = ArrayList<RectF>()
        private var t = 0f
        private val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000; repeatCount = -1; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { t = it.animatedFraction; invalidate() }
        }
        fun refresh() { invalidate() }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            // slow scanning grid
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = withAlpha(CYAN, 14)
            var gy = (t * 40 * d) % (40 * d)
            while (gy < height) { c.drawLine(0f, gy, w, gy, p); gy += 40 * d }
            var gx = 0f
            while (gx < w) { c.drawLine(gx, 0f, gx, height.toFloat(), p); gx += 40 * d }
            tp.textSize = 26 * d; tp.color = CYAN; c.drawText("ARCADE", w / 2, 78 * d, tp)
            tp.textSize = 11 * d; tp.color = DIM; c.drawText("PLAY WITH JARVIS  -  WORKS OFFLINE", w / 2, 100 * d, tp)
            cards.clear()
            val cols = 2; val gap = 14 * d; val side = 18 * d
            val cw = (w - side * 2 - gap) / cols
            val ch = min(cw * 0.92f, (height - 150 * d - gap * 3 - side) / 3f)
            GAMES.forEachIndexed { i, g ->
                val col = i % cols; val row = i / cols
                val x = side + col * (cw + gap); val y = 126 * d + row * (ch + gap)
                val r = RectF(x, y, x + cw, y + ch); cards.add(r)
                val color = NEON[i % NEON.size]
                p.style = Paint.Style.FILL; p.color = CARD; c.drawRoundRect(r, 14 * d, 14 * d, p)
                val pulse = 0.5f + 0.5f * sin((t * 6.2832f + i).toDouble()).toFloat()
                p.style = Paint.Style.STROKE; p.strokeWidth = 6 * d; p.color = withAlpha(color, (18 + 26 * pulse).toInt()); c.drawRoundRect(r, 14 * d, 14 * d, p)
                p.strokeWidth = 1.6f * d; p.color = color; c.drawRoundRect(r, 14 * d, 14 * d, p)
                drawBadge(c, g.first, r.centerX(), r.top + ch * 0.36f, ch * 0.20f, color)
                tp.textSize = 14 * d; tp.color = TEXT; c.drawText(g.second, r.centerX(), r.top + ch * 0.74f, tp)
                tp.textSize = 9.5f * d; tp.color = DIM; c.drawText(g.third, r.centerX(), r.top + ch * 0.86f, tp)
                val b = prefs.getInt("best_" + g.first, 0)
                if (b > 0) { tp.textSize = 9.5f * d; tp.color = color; c.drawText("BEST " + (if (g.first == "reaction") "$b MS" else if (g.first == "memory") "$b MOVES" else "$b"), r.centerX(), r.top + ch * 0.96f, tp) }
            }
        }

        private fun drawBadge(c: Canvas, id: String, cx: Float, cy: Float, r: Float, color: Int) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f * d; p.color = color
            when (id) {
                "snake" -> { val path = Path(); path.moveTo(cx - r, cy + r * .6f); path.cubicTo(cx - r * .2f, cy - r * 1.4f, cx + r * .2f, cy + r * 1.4f, cx + r, cy - r * .6f); c.drawPath(path, p) }
                "2048" -> { val q = r * .8f; c.drawRoundRect(RectF(cx - q, cy - q, cx + q, cy + q), 6 * d, 6 * d, p); c.drawLine(cx, cy - q, cx, cy + q, p); c.drawLine(cx - q, cy, cx + q, cy, p) }
                "tictac" -> { c.drawLine(cx - r, cy - r, cx - r * .1f, cy + r * .1f, p); c.drawLine(cx - r * .1f, cy - r, cx - r, cy + r * .1f, p); c.drawCircle(cx + r * .55f, cy + r * .45f, r * .5f, p) }
                "memory" -> { c.drawRoundRect(RectF(cx - r, cy - r * .8f, cx - r * .1f, cy + r * .8f), 5 * d, 5 * d, p); c.drawRoundRect(RectF(cx + r * .1f, cy - r * .8f, cx + r, cy + r * .8f), 5 * d, 5 * d, p) }
                "reaction" -> { val path = Path(); path.moveTo(cx + r * .2f, cy - r); path.lineTo(cx - r * .5f, cy + r * .1f); path.lineTo(cx + r * .1f, cy + r * .1f); path.lineTo(cx - r * .2f, cy + r); path.lineTo(cx + r * .6f, cy - r * .2f); path.lineTo(cx, cy - r * .2f); path.close(); c.drawPath(path, p) }
                else -> { c.drawCircle(cx, cy, r, p); c.drawCircle(cx, cy, r * .6f, p); p.style = Paint.Style.FILL; c.drawCircle(cx, cy, r * .2f, p) }
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP) {
                cards.forEachIndexed { i, r -> if (r.contains(e.x, e.y)) { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); pick(GAMES[i].first) } }
            }
            return true
        }
    }

    // ------------------------------------------------------------------ SNAKE
    class SnakeGame(ctx: Context) : GameView(ctx, "snake") {
        private val cols = 16
        private var rows = 24
        private val body = ArrayList<IntArray>()
        private var dx = 1; private var dy = 0; private var nx = 1; private var ny = 0
        private var food = intArrayOf(5, 5)
        private var score = 0; private var over = false; private var started = false; private var newBest = false
        private var delay = 150L
        private var sx = 0f; private var sy = 0f
        private val tick = object : Runnable { override fun run() { if (!running) return; if (started && !over) step(); invalidate(); ui.postDelayed(this, delay) } }

        init { reset(); ui.postDelayed(tick, delay) }

        private fun reset() {
            body.clear(); body.add(intArrayOf(8, 12)); body.add(intArrayOf(7, 12)); body.add(intArrayOf(6, 12))
            dx = 1; dy = 0; nx = 1; ny = 0; score = 0; over = false; started = false; newBest = false; delay = 150L; place()
        }
        private fun place() { do { food = intArrayOf(Random.nextInt(cols), Random.nextInt(rows)) } while (body.any { it[0] == food[0] && it[1] == food[1] }) }
        private fun step() {
            dx = nx; dy = ny
            val h = body[0]; val n = intArrayOf(h[0] + dx, h[1] + dy)
            if (n[0] < 0 || n[1] < 0 || n[0] >= cols || n[1] >= rows || body.any { it[0] == n[0] && it[1] == n[1] }) { over = true; newBest = submit(score); buzz(); return }
            body.add(0, n)
            if (n[0] == food[0] && n[1] == food[1]) { score += 10; delay = max(70L, delay - 4); place(); buzz() } else body.removeAt(body.size - 1)
        }

        override fun onDraw(c: Canvas) {
            val top = header(c, "SNAKE", "SCORE $score   BEST ${max(best, score)}")
            val cell = min((width - 24 * d) / cols, (height - top - 24 * d) / 24f)
            rows = max(12, ((height - top - 24 * d) / cell).toInt())
            val ox = (width - cell * cols) / 2f; val oy = top + 6 * d
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = EDGE
            c.drawRect(ox, oy, ox + cell * cols, oy + cell * rows, p)
            val pulse = 0.6f + 0.4f * sin(System.currentTimeMillis() / 180.0).toFloat()
            p.style = Paint.Style.FILL; p.color = withAlpha(RED, (120 + 100 * pulse).toInt())
            c.drawCircle(ox + (food[0] + .5f) * cell, oy + (food[1] + .5f) * cell, cell * .36f, p)
            body.forEachIndexed { i, s ->
                p.color = if (i == 0) CYAN else withAlpha(CYAN, max(60, 200 - i * 6))
                c.drawRoundRect(RectF(ox + s[0] * cell + 1.5f * d, oy + s[1] * cell + 1.5f * d, ox + (s[0] + 1) * cell - 1.5f * d, oy + (s[1] + 1) * cell - 1.5f * d), 5 * d, 5 * d, p)
            }
            if (!started) text(c, "SWIPE TO START", width / 2f, oy + cell * rows / 2f, 16f, TEXT)
            if (over) {
                p.style = Paint.Style.FILL; p.color = withAlpha(BG, 200); c.drawRect(ox, oy, ox + cell * cols, oy + cell * rows, p)
                text(c, "GAME OVER", width / 2f, height / 2f - 20 * d, 24f, RED)
                text(c, if (newBest) "NEW BEST  $score" else "SCORE $score", width / 2f, height / 2f + 10 * d, 15f, TEXT)
                text(c, "TAP TO PLAY AGAIN", width / 2f, height / 2f + 40 * d, 12f, DIM)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val ddx = e.x - sx; val ddy = e.y - sy
                    if (e.action == MotionEvent.ACTION_UP && abs(ddx) < 12 * d && abs(ddy) < 12 * d) {
                        if (backHit(e.x, e.y)) { onExit(); return true }
                        if (over) reset()
                    } else if (abs(ddx) > 28 * d || abs(ddy) > 28 * d) {
                        if (over) return true
                        started = true
                        if (abs(ddx) > abs(ddy)) { if (dx == 0) { nx = if (ddx > 0) 1 else -1; ny = 0 } }
                        else { if (dy == 0) { ny = if (ddy > 0) 1 else -1; nx = 0 } }
                        sx = e.x; sy = e.y
                    }
                }
            }
            return true
        }
    }

    // ------------------------------------------------------------------ 2048
    class Game2048(ctx: Context) : GameView(ctx, "2048") {
        private val g = Array(4) { IntArray(4) }
        private var score = 0; private var over = false; private var newBest = false
        private var sx = 0f; private var sy = 0f

        init { reset() }
        private fun reset() { for (r in g) r.fill(0); score = 0; over = false; newBest = false; spawn(); spawn(); invalidate() }
        private fun spawn() {
            val free = ArrayList<IntArray>()
            for (r in 0..3) for (c in 0..3) if (g[r][c] == 0) free.add(intArrayOf(r, c))
            if (free.isEmpty()) return
            val f = free.random(); g[f[0]][f[1]] = if (Random.nextInt(10) == 0) 4 else 2
        }
        /** Slides one line to the left; returns the new line. */
        private fun slide(line: IntArray): IntArray {
            val nz = line.filter { it != 0 }.toMutableList(); val out = ArrayList<Int>()
            var i = 0
            while (i < nz.size) {
                if (i + 1 < nz.size && nz[i] == nz[i + 1]) { out.add(nz[i] * 2); score += nz[i] * 2; i += 2 } else { out.add(nz[i]); i++ }
            }
            while (out.size < 4) out.add(0)
            return out.toIntArray()
        }
        private fun move(dir: Int) { // 0 left, 1 right, 2 up, 3 down
            val before = g.map { it.clone() }
            for (k in 0..3) {
                val line = IntArray(4) { i -> when (dir) { 0 -> g[k][i]; 1 -> g[k][3 - i]; 2 -> g[i][k]; else -> g[3 - i][k] } }
                val res = slide(line)
                for (i in 0..3) when (dir) { 0 -> g[k][i] = res[i]; 1 -> g[k][3 - i] = res[i]; 2 -> g[i][k] = res[i]; else -> g[3 - i][k] = res[i] }
            }
            val changed = (0..3).any { r -> (0..3).any { c -> before[r][c] != g[r][c] } }
            if (changed) { spawn(); buzz() }
            if (!canMove()) { over = true; newBest = submit(score) }
            invalidate()
        }
        private fun canMove(): Boolean {
            for (r in 0..3) for (c in 0..3) {
                if (g[r][c] == 0) return true
                if (c < 3 && g[r][c] == g[r][c + 1]) return true
                if (r < 3 && g[r][c] == g[r + 1][c]) return true
            }
            return false
        }
        private fun tileColor(v: Int): Int = NEON[(Integer.numberOfTrailingZeros(max(v, 2)) - 1).coerceIn(0, NEON.size - 1)]

        override fun onDraw(c: Canvas) {
            val top = header(c, "2048", "SCORE $score   BEST ${max(best, score)}")
            val size = min(width - 32 * d, height - top - 40 * d)
            val ox = (width - size) / 2f; val oy = top + 20 * d; val cell = size / 4f
            for (r in 0..3) for (col in 0..3) {
                val rect = RectF(ox + col * cell + 4 * d, oy + r * cell + 4 * d, ox + (col + 1) * cell - 4 * d, oy + (r + 1) * cell - 4 * d)
                val v = g[r][col]
                if (v == 0) { p.style = Paint.Style.FILL; p.color = CARD; c.drawRoundRect(rect, 10 * d, 10 * d, p) }
                else { glowRect(c, rect, tileColor(v), 90, 10f); text(c, "$v", rect.centerX(), rect.centerY() + 7 * d, if (v < 100) 26f else if (v < 1000) 22f else 17f, TEXT) }
            }
            text(c, "SWIPE ANYWHERE", width / 2f, oy + size + 28 * d, 11f, DIM)
            if (over) {
                p.style = Paint.Style.FILL; p.color = withAlpha(BG, 210); c.drawRect(ox, oy, ox + size, oy + size, p)
                text(c, "NO MOVES LEFT", width / 2f, oy + size / 2f - 10 * d, 22f, RED)
                text(c, if (newBest) "NEW BEST  $score" else "SCORE $score", width / 2f, oy + size / 2f + 18 * d, 15f, TEXT)
                text(c, "TAP TO PLAY AGAIN", width / 2f, oy + size / 2f + 46 * d, 12f, DIM)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_DOWN) { sx = e.x; sy = e.y }
            if (e.action == MotionEvent.ACTION_UP) {
                val ddx = e.x - sx; val ddy = e.y - sy
                if (abs(ddx) < 14 * d && abs(ddy) < 14 * d) { if (backHit(e.x, e.y)) onExit() else if (over) reset(); return true }
                if (over) return true
                if (abs(ddx) > abs(ddy)) move(if (ddx > 0) 1 else 0) else move(if (ddy > 0) 3 else 2)
            }
            return true
        }
    }

    // ------------------------------------------------------------------ TIC-TAC-TOE (vs Jarvis)
    class TicTacGame(ctx: Context) : GameView(ctx, "tictac") {
        private val b = IntArray(9) // 0 empty, 1 you (X), 2 Jarvis (O)
        private var wins = 0; private var losses = 0; private var draws = 0
        private var result = ""; private var line: IntArray? = null; private var locked = false
        private var youStart = true

        init { reset() }
        private fun reset() {
            b.fill(0); result = ""; line = null; locked = false
            if (!youStart) { b[jarvisMove()] = 2 }
            invalidate()
        }
        private val lines = arrayOf(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8), intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8), intArrayOf(0, 4, 8), intArrayOf(2, 4, 6))
        private fun winner(bd: IntArray): Int { for (l in lines) if (bd[l[0]] != 0 && bd[l[0]] == bd[l[1]] && bd[l[1]] == bd[l[2]]) return bd[l[0]]; return 0 }
        private fun minimax(bd: IntArray, turn: Int, depth: Int): Int {
            val w = winner(bd)
            if (w == 2) return 10 - depth
            if (w == 1) return depth - 10
            if (bd.none { it == 0 }) return 0
            var best = if (turn == 2) -100 else 100
            for (i in 0..8) if (bd[i] == 0) {
                bd[i] = turn
                val s = minimax(bd, 3 - turn, depth + 1)
                bd[i] = 0
                best = if (turn == 2) max(best, s) else min(best, s)
            }
            return best
        }
        /** Jarvis plays well but not perfectly: about 1 move in 5 is a random slip, so a win is possible. */
        private fun jarvisMove(): Int {
            val free = (0..8).filter { b[it] == 0 }
            if (Random.nextInt(5) == 0) return free.random()
            var bestS = -100; var bestM = free[0]
            for (i in free) { b[i] = 2; val s = minimax(b, 1, 1); b[i] = 0; if (s > bestS) { bestS = s; bestM = i } }
            return bestM
        }
        private fun finish() {
            val w = winner(b)
            if (w != 0) { line = lines.first { b[it[0]] == w && b[it[1]] == w && b[it[2]] == w }; locked = true
                if (w == 1) { wins++; result = "YOU WIN"; submit(wins) } else { losses++; result = "JARVIS WINS" } }
            else if (b.none { it == 0 }) { draws++; locked = true; result = "DRAW" }
        }

        override fun onDraw(c: Canvas) {
            val top = header(c, "TIC-TAC-TOE", "YOU $wins   DRAW $draws   JARVIS $losses")
            val size = min(width - 48 * d, height - top - 140 * d); val ox = (width - size) / 2f; val oy = top + 24 * d; val cell = size / 3f
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = EDGE
            for (i in 1..2) { c.drawLine(ox + i * cell, oy, ox + i * cell, oy + size, p); c.drawLine(ox, oy + i * cell, ox + size, oy + i * cell, p) }
            for (i in 0..8) {
                val cx = ox + (i % 3 + .5f) * cell; val cy = oy + (i / 3 + .5f) * cell; val r = cell * .27f
                p.strokeWidth = 4f * d
                if (b[i] == 1) { p.color = CYAN; c.drawLine(cx - r, cy - r, cx + r, cy + r, p); c.drawLine(cx + r, cy - r, cx - r, cy + r, p) }
                if (b[i] == 2) { p.color = ORANGE; c.drawCircle(cx, cy, r, p) }
            }
            line?.let { l ->
                p.color = GREEN; p.strokeWidth = 5f * d
                c.drawLine(ox + (l[0] % 3 + .5f) * cell, oy + (l[0] / 3 + .5f) * cell, ox + (l[2] % 3 + .5f) * cell, oy + (l[2] / 3 + .5f) * cell, p)
            }
            text(c, if (result.isEmpty()) "YOUR MOVE  (X)" else result, width / 2f, oy + size + 40 * d, 18f, if (result == "YOU WIN") GREEN else if (result.isEmpty()) TEXT else ORANGE)
            if (locked) button(c, RectF(width / 2f - 80 * d, oy + size + 60 * d, width / 2f + 80 * d, oy + size + 104 * d), "PLAY AGAIN")
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_UP) return true
            if (backHit(e.x, e.y)) { onExit(); return true }
            val top = 44 * d + 48 * d
            val size = min(width - 48 * d, height - top - 140 * d); val ox = (width - size) / 2f; val oy = top + 24 * d; val cell = size / 3f
            if (locked) {
                if (e.y > oy + size + 50 * d) { youStart = !youStart; reset() }
                return true
            }
            val col = ((e.x - ox) / cell).toInt(); val row = ((e.y - oy) / cell).toInt()
            if (e.x < ox || e.y < oy || col !in 0..2 || row !in 0..2) return true
            val i = row * 3 + col
            if (b[i] != 0) return true
            b[i] = 1; buzz(); finish()
            if (!locked) { b[jarvisMove()] = 2; finish() }
            invalidate(); return true
        }
    }

    // ------------------------------------------------------------------ MEMORY
    class MemoryGame(ctx: Context) : GameView(ctx, "memory", lowerBetter = true) {
        private val n = 16
        private var cards = IntArray(n)
        private var up = BooleanArray(n); private var done = BooleanArray(n)
        private var first = -1; private var second = -1
        private var moves = 0; private var pairs = 0; private var newBest = false
        private val symbols = "ABCDEFGH"

        init { reset() }
        private fun reset() {
            val l = (0 until 8).flatMap { listOf(it, it) }.shuffled(); cards = l.toIntArray()
            up = BooleanArray(n); done = BooleanArray(n); first = -1; second = -1; moves = 0; pairs = 0; newBest = false; invalidate()
        }
        private fun geom(): FloatArray {
            val top = 44 * d + 48 * d
            val size = min(width - 32 * d, height - top - 120 * d); return floatArrayOf((width - size) / 2f, top + 20 * d, size / 4f)
        }
        override fun onDraw(c: Canvas) {
            header(c, "MEMORY", "MOVES $moves   BEST ${if (best > 0) best else "-"}")
            val (ox, oy, cell) = geom().let { Triple(it[0], it[1], it[2]) }
            for (i in 0 until n) {
                val r = RectF(ox + (i % 4) * cell + 4 * d, oy + (i / 4) * cell + 4 * d, ox + (i % 4 + 1) * cell - 4 * d, oy + (i / 4 + 1) * cell - 4 * d)
                if (done[i]) { glowRect(c, r, NEON[cards[i]], 30, 10f); text(c, symbols[cards[i]].toString(), r.centerX(), r.centerY() + 9 * d, 28f, withAlpha(NEON[cards[i]], 140)) }
                else if (up[i]) { glowRect(c, r, NEON[cards[i]], 90, 10f); text(c, symbols[cards[i]].toString(), r.centerX(), r.centerY() + 9 * d, 28f, TEXT) }
                else { p.style = Paint.Style.FILL; p.color = CARD; c.drawRoundRect(r, 10 * d, 10 * d, p); p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = EDGE; c.drawRoundRect(r, 10 * d, 10 * d, p); text(c, "?", r.centerX(), r.centerY() + 7 * d, 20f, DIM) }
            }
            if (pairs == 8) {
                text(c, if (newBest) "NEW BEST  $moves MOVES" else "DONE IN $moves MOVES", width / 2f, oy + cell * 4 + 40 * d, 16f, GREEN)
                button(c, RectF(width / 2f - 80 * d, oy + cell * 4 + 58 * d, width / 2f + 80 * d, oy + cell * 4 + 100 * d), "PLAY AGAIN")
            } else text(c, "FIND ALL 8 PAIRS", width / 2f, oy + cell * 4 + 36 * d, 12f, DIM)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_UP) return true
            if (backHit(e.x, e.y)) { onExit(); return true }
            val (ox, oy, cell) = geom().let { Triple(it[0], it[1], it[2]) }
            if (pairs == 8) { if (e.y > oy + cell * 4 + 40 * d) reset(); return true }
            if (second >= 0) return true
            val col = ((e.x - ox) / cell).toInt(); val row = ((e.y - oy) / cell).toInt()
            if (e.x < ox || e.y < oy || col !in 0..3 || row !in 0..3) return true
            val i = row * 4 + col
            if (up[i] || done[i]) return true
            up[i] = true; buzz()
            if (first < 0) first = i else {
                second = i; moves++
                if (cards[first] == cards[second]) { done[first] = true; done[second] = true; pairs++; first = -1; second = -1
                    if (pairs == 8) newBest = submit(moves) }
                else { val a = first; val b = second; ui.postDelayed({ up[a] = false; up[b] = false; first = -1; second = -1; invalidate() }, 700) }
            }
            invalidate(); return true
        }
    }

    // ------------------------------------------------------------------ REACTION
    class ReactionGame(ctx: Context) : GameView(ctx, "reaction", lowerBetter = true) {
        private var state = 0 // 0 idle, 1 waiting, 2 go, 3 result, 4 too soon
        private var t0 = 0L; private var ms = 0; private var newBest = false
        private val go = Runnable { state = 2; t0 = System.currentTimeMillis(); invalidate() }

        override fun onDraw(c: Canvas) {
            val color = when (state) { 1 -> RED; 2 -> GREEN; 4 -> ORANGE; else -> CYAN }
            p.style = Paint.Style.FILL; p.color = withAlpha(color, if (state == 0 || state == 3) 24 else 90); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            header(c, "REACTION", "BEST ${if (best > 0) "$best MS" else "-"}")
            val msg = when (state) { 0 -> "TAP TO START"; 1 -> "WAIT FOR GREEN..."; 2 -> "TAP NOW!"; 3 -> "$ms MS"; else -> "TOO SOON" }
            text(c, msg, width / 2f, height / 2f, if (state == 3) 44f else 26f, color)
            if (state == 3) text(c, (if (newBest) "NEW BEST  -  " else "") + rank(ms), width / 2f, height / 2f + 40 * d, 14f, TEXT)
            if (state == 3 || state == 4) text(c, "TAP TO TRY AGAIN", width / 2f, height / 2f + 76 * d, 12f, DIM)
        }
        private fun rank(v: Int) = when { v < 200 -> "LIGHTNING"; v < 260 -> "QUICK"; v < 350 -> "AVERAGE"; else -> "SLEEPY" }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_DOWN) return true
            if (backHit(e.x, e.y)) { onExit(); return true }
            when (state) {
                0, 3, 4 -> { state = 1; newBest = false; ui.postDelayed(go, 1500L + Random.nextLong(3500L)) }
                1 -> { ui.removeCallbacks(go); state = 4 }
                2 -> { ms = (System.currentTimeMillis() - t0).toInt(); state = 3; newBest = submit(ms); buzz() }
            }
            invalidate(); return true
        }
    }

    // ------------------------------------------------------------------ TAP RUSH
    class TapRushGame(ctx: Context) : GameView(ctx, "rush") {
        private class Dot(val x: Float, val y: Float, val born: Long, val life: Long, val color: Int)
        private val dots = ArrayList<Dot>()
        private var score = 0; private var misses = 0; private var started = false; private var over = false; private var newBest = false
        private var endAt = 0L; private var nextSpawn = 0L
        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val now = System.currentTimeMillis()
                if (started && !over) {
                    dots.removeAll { now - it.born > it.life }
                    if (now >= nextSpawn && width > 0) {
                        val r = 34 * d
                        dots.add(Dot(r + Random.nextFloat() * (width - 2 * r), 150 * d + Random.nextFloat() * (height - 220 * d), now, 1100L, NEON.random()))
                        nextSpawn = now + max(260L, 650L - score * 6L)
                    }
                    if (now >= endAt) { over = true; newBest = submit(score) }
                }
                invalidate(); ui.postDelayed(this, 33)
            }
        }
        init { ui.post(tick) }

        override fun onDraw(c: Canvas) {
            val now = System.currentTimeMillis()
            val left = if (started && !over) max(0L, (endAt - now) / 1000 + 1) else 30
            header(c, "TAP RUSH", "SCORE $score   TIME $left   BEST ${max(best, score)}")
            for (dt in dots) {
                val age = (now - dt.born).toFloat() / dt.life; val r = 34 * d * (1f - age * .35f)
                p.style = Paint.Style.FILL; p.color = withAlpha(dt.color, 60); c.drawCircle(dt.x, dt.y, r, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f * d; p.color = dt.color; c.drawCircle(dt.x, dt.y, r, p)
                p.strokeWidth = 1f * d; p.color = withAlpha(dt.color, 120); c.drawCircle(dt.x, dt.y, r * (1f + age * .6f), p)
            }
            if (!started) text(c, "TAP TO START", width / 2f, height / 2f, 22f, TEXT)
            if (over) {
                p.style = Paint.Style.FILL; p.color = withAlpha(BG, 210); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
                text(c, "TIME UP", width / 2f, height / 2f - 20 * d, 26f, ORANGE)
                text(c, if (newBest) "NEW BEST  $score" else "SCORE $score", width / 2f, height / 2f + 12 * d, 16f, TEXT)
                text(c, "TAP TO PLAY AGAIN", width / 2f, height / 2f + 44 * d, 12f, DIM)
            }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_DOWN) return true
            if (backHit(e.x, e.y)) { onExit(); return true }
            if (over || !started) {
                dots.clear(); score = 0; misses = 0; newBest = false; over = false; started = true
                endAt = System.currentTimeMillis() + 30_000L; nextSpawn = 0L; return true
            }
            val hit = dots.firstOrNull { val dx = e.x - it.x; val dy = e.y - it.y; dx * dx + dy * dy < (44 * d) * (44 * d) }
            if (hit != null) { dots.remove(hit); score++; buzz() } else misses++
            return true
        }
    }
}
