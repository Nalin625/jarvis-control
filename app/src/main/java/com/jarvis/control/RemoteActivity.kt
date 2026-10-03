package com.jarvis.control

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.graphics.Typeface
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import kotlin.math.abs

/**
 * Laptop remote: everything the phone can do TO the laptop. Built in code (no XML) so it
 * can't break the main layout. Every button is one request to the laptop's remote-control routes.
 */
class RemoteActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var resultLabel: TextView
    private lateinit var askReply: TextView
    private lateinit var askField: EditText
    private lateinit var glanceLabel: TextView
    private val ACCENT = Color.parseColor("#4FE3FF")
    private val BG = Color.parseColor("#0B1220")
    private val CARD = Color.parseColor("#141E33")
    private val DIM = Color.parseColor("#7F8EA3")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            addView(root)
        }
        // animated HUD backdrop behind everything, same look as the main screen
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BG)
            addView(HudBackgroundView(this@RemoteActivity), FrameLayout.LayoutParams(-1, -1))
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
        })

        root.addView(title("// LAPTOP REMOTE", 20).apply { letterSpacing = 0.12f })
        resultLabel = TextView(this).apply {
            setTextColor(DIM); textSize = 13f
            text = if (LaptopApi.paired(this@RemoteActivity)) "Ready." else "Not connected. Go back and tap Find my laptop."
            setPadding(0, dp(2), 0, dp(10))
        }
        root.addView(resultLabel)

        // ---- Ask Jarvis
        root.addView(card("ASK JARVIS") { c ->
            askField = field("Ask or command, e.g. \"free ram\"")
            c.addView(askField)
            askReply = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(0, dp(8), 0, dp(4)); setTextIsSelectable(true) }
            c.addView(row(btn("Ask") { ask(askField.text.toString()) }, btn("\uD83C\uDFA4 Voice") { listen() }))
            c.addView(askReply)
        })

        // ---- Touchpad
        root.addView(card("TOUCHPAD") { c ->
            c.addView(TouchpadView(this, ::sendMouse).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220))
            })
            c.addView(TextView(this).apply {
                text = "Drag = move   \u00B7   tap = click   \u00B7   two fingers: drag = scroll, tap = right click"
                setTextColor(DIM); textSize = 11f; setPadding(0, dp(4), 0, dp(6))
            })
            c.addView(row(btn("Left click") { post("/remote/mouse", JSONObject().put("click", "left")) },
                btn("Double") { post("/remote/mouse", JSONObject().put("click", "double")) },
                btn("Right click") { post("/remote/mouse", JSONObject().put("click", "right")) }))
        })

        // ---- Keyboard
        root.addView(card("KEYBOARD") { c ->
            val typeField = field("Type here, then Send")
            c.addView(typeField)
            c.addView(row(btn("Send text") { post("/remote/type", JSONObject().put("text", typeField.text.toString())); typeField.setText("") }))
            c.addView(row(key("Enter", "Return"), key("Bksp", "BackSpace"), key("Tab", "Tab"), key("Esc", "Escape")))
            c.addView(row(key("\u2190", "Left"), key("\u2191", "Up"), key("\u2193", "Down"), key("\u2192", "Right")))
        })

        // ---- Power / volume / brightness
        root.addView(card("CONTROLS") { c ->
            c.addView(row(btn("\uD83D\uDD12 Lock") { post("/remote/power", JSONObject().put("action", "lock")) },
                btn("\uD83D\uDCA4 Sleep") { post("/remote/power", JSONObject().put("action", "sleep")) },
                btn("Screen off") { post("/remote/power", JSONObject().put("action", "screen_off")) }))
            c.addView(row(btn("Vol \u2212") { post("/remote/volume", JSONObject().put("delta", -5)) },
                btn("Mute") { post("/remote/volume", JSONObject().put("mute", true)) },
                btn("Vol +") { post("/remote/volume", JSONObject().put("delta", 5)) }))
            c.addView(row(btn("Bright \u2212") { post("/remote/brightness", JSONObject().put("delta", -10)) },
                btn("Bright +") { post("/remote/brightness", JSONObject().put("delta", 10)) }))
            c.addView(row(key("\u23EE", "XF86AudioPrev"), key("\u23EF", "XF86AudioPlay"), key("\u23ED", "XF86AudioNext")))
        })

        // ---- Presentation remote
        root.addView(card("PRESENTATION") { c ->
            c.addView(row(key("\u25C0 Prev", "Left"), key("Next \u25B6", "Right")))
            c.addView(row(key("Start (F5)", "F5"), key("Black", "b"), key("End", "Escape")))
        })

        // ---- Send things to the laptop
        root.addView(card("SEND TO LAPTOP") { c ->
            val link = field("https:// link to open on the laptop")
            link.inputType = InputType.TYPE_TEXT_VARIATION_URI
            c.addView(link)
            c.addView(row(btn("Open link") { post("/remote/open", JSONObject().put("url", link.text.toString().trim())) }))
            val note = field("Quick note")
            c.addView(note)
            c.addView(row(btn("Save note") { post("/remote/note", JSONObject().put("text", note.text.toString())); note.setText("") }))
            val remText = field("Remind me to...")
            val remMin = field("in minutes (e.g. 10)").apply { inputType = InputType.TYPE_CLASS_NUMBER }
            c.addView(remText); c.addView(remMin)
            c.addView(row(btn("Set reminder") {
                val mins = remMin.text.toString().toIntOrNull() ?: 10
                ask("remind me to ${remText.text.toString().trim()} in $mins minutes", showInAsk = false)
                remText.setText("")
            }))
            c.addView(row(btn("Pull laptop clipboard") { pullClipboard() }))
        })

        // ---- Laptop glance
        root.addView(card("LAPTOP STATUS") { c ->
            glanceLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; text = "Tap refresh" }
            c.addView(glanceLabel)
            c.addView(row(btn("Refresh") { glance() }))
        })
    }

    override fun onResume() {
        super.onResume()
        if (LaptopApi.paired(this)) glance()
    }

    // ---------------------------------------------------------------- network helpers
    private fun post(path: String, body: JSONObject) {
        Thread {
            val r = LaptopApi.call(this, "POST", path, body)
            ui.post { say(if (r.has("error")) "\u26A0 " + r.optString("error") else "\u2713 Done") }
        }.start()
    }

    private fun say(msg: String) { resultLabel.text = msg }

    private fun ask(text: String, showInAsk: Boolean = true) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (showInAsk) askReply.text = "Thinking..."
        Thread {
            val r = LaptopApi.call(this, "POST", "/command", JSONObject().put("command", t), timeoutMs = 70000)
            ui.post {
                val reply = if (r.has("error")) "\u26A0 " + r.optString("error") else r.optString("response", "(no reply)")
                if (showInAsk) askReply.text = reply else say(reply.take(120))
            }
        }.start()
    }

    private fun listen() {
        try {
            startActivityForResult(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                77
            )
        } catch (e: Exception) {
            say("\u26A0 No speech recognizer on this phone")
        }
    }

    @Deprecated("simple one-off voice request")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            askField.setText(said)
            ask(said)
        }
    }

    private fun glance() {
        Thread {
            val r = LaptopApi.call(this, "GET", "/remote/glance")
            ui.post {
                glanceLabel.text = if (r.has("error")) "\u26A0 " + r.optString("error") else {
                    val bat = r.optJSONObject("battery")
                    "CPU ${r.optInt("cpu_pct")}%   RAM ${r.optInt("ram_pct")}% (${r.optDouble("ram_used_gb")} / ${r.optDouble("ram_total_gb")} GB)\n" +
                        (if (bat != null) "Battery ${bat.optInt("pct")}%${if (bat.optBoolean("charging")) " \u26A1" else ""}   " else "") +
                        "Up ${r.optString("uptime")}"
                }
            }
        }.start()
    }

    private fun pullClipboard() {
        Thread {
            val r = LaptopApi.call(this, "GET", "/clipboard/last")
            ui.post {
                val t = r.optString("text", "")
                if (r.has("error")) say("\u26A0 " + r.optString("error"))
                else if (t.isEmpty()) say("Laptop clipboard is empty")
                else {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Jarvis", t))
                    say("\u2713 Copied from laptop (${t.length} chars)")
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- touchpad -> batched mouse moves
    private var accDx = 0f
    private var accDy = 0f
    private var accScroll = 0f
    private var sending = false

    private fun sendMouse(dx: Float, dy: Float, scroll: Float, click: String?) {
        accDx += dx; accDy += dy; accScroll += scroll
        if (click != null) {
            post("/remote/mouse", JSONObject().put("click", click))
            return
        }
        if (sending) return
        sending = true
        ui.postDelayed({
            val body = JSONObject().put("dx", accDx.toInt()).put("dy", accDy.toInt()).put("scroll", accScroll.toInt())
            val used = accDx.toInt() != 0 || accDy.toInt() != 0 || accScroll.toInt() != 0
            accDx -= accDx.toInt(); accDy -= accDy.toInt(); accScroll -= accScroll.toInt()
            if (used) Thread {
                LaptopApi.call(this, "POST", "/remote/mouse", body, timeoutMs = 2000)
                ui.post { sending = false }
            }.start() else sending = false
        }, 40)
    }

    // ---------------------------------------------------------------- small UI builders
    private fun title(t: String, size: Int) = TextView(this).apply {
        text = t; setTextColor(ACCENT); textSize = size.toFloat()
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private fun card(name: String, fill: (LinearLayout) -> Unit): LinearLayout {
        val c = HudCard(this).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        }
        c.addView(title(name, 12).apply { letterSpacing = 0.14f; setPadding(0, 0, 0, dp(10)) })
        fill(c)
        return c
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint; setHintTextColor(DIM); setTextColor(Color.WHITE); textSize = 14f
        typeface = Typeface.MONOSPACE
        setBackgroundResource(R.drawable.bg_input)
        setSingleLine(true)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE)
        minHeight = 0; minimumHeight = dp(44)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#0A1822"))
            setStroke(dp(1), Color.parseColor("#1E4452"))
            cornerRadius = dp(10).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun key(label: String, name: String) = btn(label) { post("/remote/key", JSONObject().put("key", name)) }

    private fun row(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        for (b in buttons) {
            addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
    }
}

/** One-finger drag = move, tap = click, two-finger drag = scroll, two-finger tap = right click. */
class TouchpadView(
    context: Context,
    private val onMove: (dx: Float, dy: Float, scroll: Float, click: String?) -> Unit
) : View(context) {

    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = 0f
    private var maxPointers = 1
    private val sensitivity = 1.8f

    init {
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#0E1830"))
            setStroke(2, Color.parseColor("#4FE3FF"))
            cornerRadius = 24f
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = e.x; lastY = e.y; downTime = e.eventTime; moved = 0f; maxPointers = 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                lastX = e.getX(0); lastY = e.getY(0)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.getX(0) - lastX
                val dy = e.getY(0) - lastY
                lastX = e.getX(0); lastY = e.getY(0)
                moved += abs(dx) + abs(dy)
                if (e.pointerCount >= 2) onMove(0f, 0f, dy / 18f, null)   // two fingers: scroll
                else onMove(dx * sensitivity, dy * sensitivity, 0f, null)
            }
            MotionEvent.ACTION_UP -> {
                val quick = e.eventTime - downTime < 250 && moved < 24f
                if (quick) onMove(0f, 0f, 0f, if (maxPointers >= 2) "right" else "left")
            }
        }
        return true
    }
}
