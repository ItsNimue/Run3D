package lk.maleemahi.run3d

import android.content.Context
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class Obstacle(val type: Int, val lane: Int, var z: Float)
class Coin(val lane: Int, var z: Float)

class Game(ctx: Context, private val onHud: (Int, Int, Int, Int) -> Unit) {

    companion object {
        const val READY = 0
        const val RUNNING = 1
        const val DEAD = 2

        const val LOW = 0    // පනින්න ඕනේ
        const val HIGH = 1   // ලිස්සන්න ඕනේ
        const val WALL = 2   // පැත්තකට මාරු වෙන්න ඕනේ

        const val LANE_W = 2.2f
        const val SPAWN_Z = -95f
    }

    private val prefs = ctx.getSharedPreferences("run3d", Context.MODE_PRIVATE)
    private val rnd = Random(System.nanoTime())

    var state = READY
    var best = prefs.getInt("best", 0)

    var lane = 0
    var px = 0f
    var py = 0f
    private var vy = 0f
    var slideT = 0f
    var slideBlend = 0f
    var deadBlend = 0f
    private var sinceDead = 0f

    var speed = 13f
    var dist = 0f
    var coinCount = 0
    var runT = 0f
    var time = 0f

    val obstacles = ArrayList<Obstacle>()
    val coins = ArrayList<Coin>()

    private var sinceSpawn = 0f
    private var nextGap = 28f
    private var hudTimer = 0f

    val score: Int get() = (dist + coinCount * 10).toInt()

    // ------------------------------------------------------------ input
    private fun reset() {
        lane = 0; px = 0f; py = 0f; vy = 0f
        slideT = 0f; slideBlend = 0f; deadBlend = 0f; sinceDead = 0f
        speed = 13f; dist = 0f; coinCount = 0
        obstacles.clear(); coins.clear()
        sinceSpawn = 0f; nextGap = 28f
        state = RUNNING
        pushHud()
    }

    fun tap() {
        if (state == READY || (state == DEAD && sinceDead > 0.5f)) reset()
    }

    /** dir: 0 වම, 1 දකුණ, 2 උඩ (පනින්න), 3 පහළ (ලිස්සන්න) */
    fun swipe(dir: Int) {
        if (state != RUNNING) {
            tap()
            return
        }
        when (dir) {
            0 -> lane = max(-1, lane - 1)
            1 -> lane = min(1, lane + 1)
            2 -> if (py <= 0.01f) {
                vy = 11.5f
                slideT = 0f
            }
            3 -> {
                slideT = 0.75f
                if (py > 0.01f) vy = -22f   // ඉක්මනින් බහින්න
            }
        }
    }

    // ----------------------------------------------------------- update
    fun update(dt: Float) {
        time += dt
        hudTimer += dt
        slideBlend += ((if (slideT > 0f) 1f else 0f) - slideBlend) * min(1f, 14f * dt)

        when (state) {
            READY -> runT += dt * 3f
            DEAD -> {
                deadBlend = min(1f, deadBlend + dt * 3f)
                sinceDead += dt
            }
            else -> step(dt)
        }
        if (hudTimer > 0.1f) {
            hudTimer = 0f
            pushHud()
        }
    }

    private fun step(dt: Float) {
        speed = min(32f, 13f + dist * 0.004f)
        val dz = speed * dt
        dist += dz
        runT += dt * speed * 0.55f
        px += (lane * LANE_W - px) * min(1f, 14f * dt)

        if (py > 0f || vy > 0f) {
            vy -= 30f * dt
            py += vy * dt
            if (py <= 0f) {
                py = 0f
                vy = 0f
            }
        }
        if (slideT > 0f) slideT -= dt

        for (o in obstacles) o.z += dz
        for (c in coins) c.z += dz
        obstacles.removeAll { it.z > 10f }
        coins.removeAll { it.z > 10f }

        sinceSpawn += dz
        if (sinceSpawn >= nextGap) {
            sinceSpawn = 0f
            spawnRow()
            nextGap = max(20f, speed * 1.05f) + rnd.nextFloat() * 10f
        }

        // බාධක වලට හැපෙනවාද
        val h = if (slideT > 0f) 0.55f else 2.0f
        for (o in obstacles) {
            if (abs(px - o.lane * LANE_W) > 1.25f) continue
            val zHalf = when (o.type) { LOW -> 0.5f; HIGH -> 0.25f; else -> 0.45f }
            if (abs(o.z) > zHalf + 0.35f) continue
            val y0 = if (o.type == HIGH) 1.35f else 0f
            val y1 = when (o.type) { LOW -> 0.9f; HIGH -> 2.4f; else -> 2.6f }
            if (py + h > y0 && py < y1) {
                die()
                return
            }
        }

        // coins
        val it = coins.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if (abs(px - c.lane * LANE_W) < 0.9f && abs(c.z) < 0.9f && py < 1.3f && py + h > 0.7f) {
                coinCount++
                it.remove()
            }
        }
    }

    private fun spawnRow() {
        val z = SPAWN_Z
        val r = rnd.nextFloat()
        when {
            r < 0.35f -> obstacles.add(Obstacle(rnd.nextInt(3), rnd.nextInt(3) - 1, z))
            r < 0.60f -> {
                val free = rnd.nextInt(3) - 1
                for (l in -1..1) if (l != free) obstacles.add(Obstacle(WALL, l, z))
            }
            r < 0.80f -> {
                val t = if (rnd.nextBoolean()) LOW else HIGH
                for (l in -1..1) obstacles.add(Obstacle(t, l, z))
            }
        }
        if (rnd.nextFloat() < 0.8f) {
            val l = rnd.nextInt(3) - 1
            for (i in 0 until 5) coins.add(Coin(l, z + 5f + i * 2.2f))
        }
    }

    private fun die() {
        state = DEAD
        sinceDead = 0f
        if (score > best) {
            best = score
            prefs.edit().putInt("best", best).apply()
        }
        pushHud()
    }

    private fun pushHud() = onHud(score, coinCount, best, state)
}
