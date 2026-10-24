package lk.maleemahi.run3d

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import kotlin.math.abs

class GameView(ctx: Context, onHud: (Int, Int, Int, Int) -> Unit) : GLSurfaceView(ctx) {

    private val game = Game(ctx, onHud)
    private var downX = 0f
    private var downY = 0f
    private var fired = false

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(GameRenderer(game))
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                fired = false
            }
            MotionEvent.ACTION_MOVE -> if (!fired) {
                val dx = e.x - downX
                val dy = e.y - downY
                val th = 40f * resources.displayMetrics.density
                if (abs(dx) > th || abs(dy) > th) {
                    fired = true
                    val dir = if (abs(dx) > abs(dy)) {
                        if (dx > 0) 1 else 0
                    } else {
                        if (dy < 0) 2 else 3
                    }
                    queueEvent { game.swipe(dir) }
                }
            }
            MotionEvent.ACTION_UP -> if (!fired) queueEvent { game.tap() }
        }
        return true
    }
}
