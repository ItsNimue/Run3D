package lk.maleemahi.run3d

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var view: GameView
    private lateinit var scoreText: TextView
    private lateinit var msgText: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        view = GameView(this) { score, coins, best, state ->
            runOnUiThread { updateHud(score, coins, best, state) }
        }
        root.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        scoreText = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setPadding(dp(18), dp(40), dp(18), 0)
        }
        root.addView(
            scoreText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )

        msgText = TextView(this).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 0f, 3f, Color.BLACK)
            setPadding(dp(24), 0, dp(24), 0)
        }
        root.addView(
            msgText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        setContentView(root)
        hideSystemUi()
        updateHud(0, 0, 0, Game.READY)
    }

    private fun updateHud(score: Int, coins: Int, best: Int, state: Int) {
        scoreText.text = "Score  $score\nCoins  $coins\nBest  $best"
        when (state) {
            Game.READY -> {
                msgText.visibility = View.VISIBLE
                msgText.text = "RUN 3D\n\nTap to start\n\n← → swipe   ↑ jump   ↓ slide"
            }
            Game.DEAD -> {
                msgText.visibility = View.VISIBLE
                msgText.text = "Game Over\n\nScore $score · Best $best\n\nTap to restart"
            }
            else -> msgText.visibility = View.GONE
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
    }

    override fun onPause() {
        super.onPause()
        view.onPause()
    }
}
