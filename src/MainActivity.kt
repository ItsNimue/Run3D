package lk.maleemahi.run3d

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ceil

class MainActivity : Activity() {

    private lateinit var sound: Sound
    private lateinit var view: GameView
    private lateinit var scoreText: TextView
    private lateinit var msgText: TextView
    private lateinit var menuBar: LinearLayout
    private lateinit var playBtn: TextView
    private lateinit var shopPanel: LinearLayout
    private lateinit var walletText: TextView
    private lateinit var cardsRow: LinearLayout
    private lateinit var soundBtn: TextView

    private var shopOpen = false
    private var lastHud = Hud(0, 0, 0, Game.READY, 0, 0, 1, 0f, 0f, 0f)

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun btn(label: String, bg: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(14), dp(26), dp(14))
            background = roundBg(bg, 28)
            setOnClickListener {
                sound.click()
                onClick()
            }
        }

    private fun fl(w: Int, h: Int, gravity: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        FrameLayout.LayoutParams(w, h, gravity).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sound = Sound(this)
        val root = FrameLayout(this)
        view = GameView(this, sound) { h -> runOnUiThread { updateHud(h) } }
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))

        scoreText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        root.addView(scoreText, fl(WRAP, WRAP, Gravity.TOP or Gravity.START, 18, 40))

        soundBtn = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundBg(0x66000000, 24)
            setOnClickListener {
                sound.muted = !sound.muted
                refreshSoundBtn()
                sound.click()
            }
        }
        refreshSoundBtn()
        root.addView(soundBtn, fl(WRAP, WRAP, Gravity.TOP or Gravity.END, 0, 36, 14, 0))

        msgText = TextView(this).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 0f, 3f, Color.BLACK)
        }
        root.addView(msgText, fl(MATCH, WRAP, Gravity.TOP, 24, 130, 24, 0))

        // ---- menu බොත්තම්
        menuBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        playBtn = btn("▶  PLAY", 0xFF22C55E.toInt()) { view.start() }
        val shopBtn = btn("🛍  SHOP", 0xFF3B82F6.toInt()) {
            view.menu()
            shopOpen = true
            updateHud(lastHud)
        }
        menuBar.addView(playBtn)
        menuBar.addView(shopBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })
        root.addView(menuBar, fl(MATCH, WRAP, Gravity.BOTTOM, 0, 0, 0, 70))

        // ---- shop
        shopPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = roundBg(0xE60B1220.toInt(), 24)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        walletText = TextView(this).apply {
            textSize = 20f
            setTextColor(0xFFFFD34D.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        head.addView(walletText, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(btn("◀ Back", 0xFF475569.toInt()) {
            shopOpen = false
            updateHud(lastHud)
        })
        shopPanel.addView(head)
        cardsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(cardsRow)
        }
        shopPanel.addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        root.addView(shopPanel, fl(MATCH, WRAP, Gravity.BOTTOM, 12, 0, 12, 24))

        setContentView(root)
        hideSystemUi()
        updateHud(lastHud)
    }

    private fun refreshSoundBtn() {
        soundBtn.text = if (sound.muted) "🔇" else "🔊"
    }

    private fun rgbInt(f: FloatArray): Int =
        Color.rgb((f[0] * 255).toInt(), (f[1] * 255).toInt(), (f[2] * 255).toInt())

    private fun card(i: Int, owned: Boolean, selected: Boolean, wallet: Int): View {
        val s = SKINS[i]
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = roundBg(if (selected) 0xFF134E4A.toInt() else 0xFF1E2A44.toInt(), 16).apply {
                if (selected) setStroke(dp(2), 0xFF22C55E.toInt())
            }
            setOnClickListener {
                if (!owned && wallet < s.price) {
                    Toast.makeText(this@MainActivity, "Not enough coins 🪙", Toast.LENGTH_SHORT).show()
                } else {
                    view.shop(i)
                }
            }
        }
        val swatch = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(rgbInt(s.shirt))
            }
        }
        box.addView(swatch, LinearLayout.LayoutParams(dp(40), dp(40)))
        box.addView(TextView(this).apply {
            text = s.name
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = when {
                selected -> "✔ Selected"
                owned -> "Owned"
                else -> "🪙 ${s.price}"
            }
            setTextColor(0xFFCBD5E1.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
        })
        return box
    }

    private fun secs(f: Float): Int = ceil(f.toDouble()).toInt()

    private fun updateHud(h: Hud) {
        lastHud = h

        val sb = StringBuilder("Score  ${h.score}\nCoins  ${h.coins}\nBest  ${h.best}")
        val power = ArrayList<String>()
        if (h.magnet > 0f) power.add("🧲 ${secs(h.magnet)}s")
        if (h.shield > 0f) power.add("🛡 ${secs(h.shield)}s")
        if (h.x2 > 0f) power.add("✖2 ${secs(h.x2)}s")
        if (power.isNotEmpty()) sb.append("\n").append(power.joinToString("   "))
        scoreText.text = sb.toString()
        scoreText.visibility = if (h.state == Game.RUNNING) View.VISIBLE else View.GONE

        when {
            h.state == Game.RUNNING -> {
                shopOpen = false
                msgText.visibility = View.GONE
                menuBar.visibility = View.GONE
                shopPanel.visibility = View.GONE
            }
            shopOpen -> {
                msgText.visibility = View.VISIBLE
                msgText.text = "SHOP"
                menuBar.visibility = View.GONE
                shopPanel.visibility = View.VISIBLE
                walletText.text = "🪙 ${h.wallet}"
                cardsRow.removeAllViews()
                for (i in SKINS.indices) {
                    val owned = ((h.owned shr i) and 1) == 1
                    cardsRow.addView(
                        card(i, owned, i == h.skin, h.wallet),
                        LinearLayout.LayoutParams(WRAP, WRAP).apply {
                            leftMargin = dp(4)
                            rightMargin = dp(4)
                        }
                    )
                }
            }
            else -> {
                msgText.visibility = View.VISIBLE
                shopPanel.visibility = View.GONE
                menuBar.visibility = View.VISIBLE
                if (h.state == Game.DEAD) {
                    msgText.text = "Game Over\n\nScore ${h.score} · Best ${h.best}\n+${h.coins} coins 🪙"
                    playBtn.text = "▶  PLAY AGAIN"
                } else {
                    msgText.text = "RUN 3D\n\nBest ${h.best}   ·   🪙 ${h.wallet}\n\n← → swipe   ↑ jump   ↓ slide"
                    playBtn.text = "▶  PLAY"
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        view.onResume()
        sound.resumeMusic()
    }

    override fun onPause() {
        super.onPause()
        view.onPause()
        sound.pauseMusic()
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
    }
}
