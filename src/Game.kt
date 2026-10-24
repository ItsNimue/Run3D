package lk.maleemahi.run3d

import android.content.Context
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

fun rgb(r: Int, g: Int, b: Int) = floatArrayOf(r / 255f, g / 255f, b / 255f)

class Skin(
    val name: String,
    val price: Int,
    val skin: FloatArray,
    val shirt: FloatArray,
    val pants: FloatArray,
    val shoe: FloatArray,
    val hair: FloatArray,
    val band: FloatArray,
    val pack: FloatArray
)

val SKINS = listOf(
    Skin("Runner", 0, rgb(244, 196, 152), rgb(50, 130, 240), rgb(52, 56, 96), rgb(245, 245, 245), rgb(40, 28, 22), rgb(230, 50, 60), rgb(245, 120, 40)),
    Skin("Ninja", 100, rgb(244, 196, 152), rgb(30, 30, 36), rgb(24, 24, 30), rgb(60, 60, 70), rgb(20, 20, 24), rgb(220, 40, 40), rgb(70, 70, 80)),
    Skin("Robot", 200, rgb(170, 190, 205), rgb(120, 140, 160), rgb(70, 85, 105), rgb(40, 200, 220), rgb(60, 70, 85), rgb(40, 220, 240), rgb(40, 200, 220)),
    Skin("Fire", 300, rgb(244, 196, 152), rgb(235, 80, 30), rgb(120, 30, 20), rgb(255, 200, 60), rgb(255, 150, 30), rgb(255, 220, 60), rgb(150, 40, 20)),
    Skin("Zombie", 400, rgb(130, 190, 110), rgb(110, 90, 150), rgb(70, 70, 60), rgb(90, 70, 50), rgb(60, 80, 50), rgb(180, 60, 60), rgb(90, 90, 80)),
    Skin("Gold", 600, rgb(255, 215, 120), rgb(250, 200, 40), rgb(200, 150, 30), rgb(255, 240, 170), rgb(160, 110, 20), rgb(255, 255, 255), rgb(220, 170, 40))
)

class Obstacle(val type: Int, val lane: Int, var z: Float)
class Coin(var x: Float, var z: Float)
class Pickup(val kind: Int, val lane: Int, var z: Float)

/** UI එකට යවන snapshot එක */
class Hud(
    val score: Int, val coins: Int, val best: Int, val state: Int,
    val wallet: Int, val skin: Int, val owned: Int,
    val magnet: Float, val shield: Float, val x2: Float
)

class Game(ctx: Context, private val sound: Sound, private val onHud: (Hud) -> Unit) {

    companion object {
        const val READY = 0
        const val RUNNING = 1
        const val DEAD = 2

        const val LOW = 0
        const val HIGH = 1
        const val WALL = 2

        const val MAGNET = 0
        const val SHIELD = 1
        const val X2 = 2

        const val LANE_W = 2.2f
        const val SPAWN_Z = -95f
    }

    private val prefs = ctx.getSharedPreferences("run3d", Context.MODE_PRIVATE)
    private val rnd = Random(System.nanoTime())

    var state = READY
    var best = prefs.getInt("best", 0)
    var wallet = prefs.getInt("wallet", 0)
    var owned = prefs.getInt("owned", 1)
    var skinIndex = prefs.getInt("skin", 0)
    val skin: Skin get() = SKINS[skinIndex.coerceIn(0, SKINS.size - 1)]

    var lane = 0
    var px = 0f
    var py = 0f
    private var vy = 0f
    var slideT = 0f
    var slideBlend = 0f
    var deadBlend = 0f
    private var sinceDead = 0f
    var menuT = 1f
    var preview = 0f

    var speed = 13f
    var dist = 0f
    private var scoreF = 0f
    var coinCount = 0
    var runT = 0f
    var time = 0f

    var magnetT = 0f
    var shieldT = 0f
    var x2T = 0f
    var invT = 0f

    val obstacles = ArrayList<Obstacle>()
    val coins = ArrayList<Coin>()
    val pickups = ArrayList<Pickup>()

    private var sinceSpawn = 0f
    private var nextGap = 28f
    private var hudTimer = 0f

    val score: Int get() = (scoreF + coinCount * 10).toInt()

    // ------------------------------------------------------------ control
    private fun reset() {
        lane = 0; px = 0f; py = 0f; vy = 0f
        slideT = 0f; slideBlend = 0f; deadBlend = 0f; sinceDead = 0f
        speed = 13f; dist = 0f; scoreF = 0f; coinCount = 0
        magnetT = 0f; shieldT = 0f; x2T = 0f; invT = 0f
        preview = 0f
        obstacles.clear(); coins.clear(); pickups.clear()
        sinceSpawn = 0f; nextGap = 28f
        state = RUNNING
        pushHud()
    }

    fun start() {
        if (state == READY || (state == DEAD && sinceDead > 0.3f)) {
            sound.click()
            reset()
        }
    }

    /** menu එකට (shop එකට) ආපහු යනවා */
    fun toMenu() {
        if (state == RUNNING) return
        state = READY
        lane = 0; px = 0f; py = 0f; vy = 0f
        slideT = 0f; slideBlend = 0f; deadBlend = 0f
        dist = 0f
        obstacles.clear(); coins.clear(); pickups.clear()
        pushHud()
    }

    fun shopAction(i: Int) {
        if (i !in SKINS.indices || state == RUNNING) return
        val s = SKINS[i]
        if ((owned and (1 shl i)) == 0) {
            if (wallet < s.price) return
            wallet -= s.price
            owned = owned or (1 shl i)
            sound.power()
        } else {
            sound.click()
        }
        skinIndex = i
        prefs.edit().putInt("wallet", wallet).putInt("owned", owned).putInt("skin", skinIndex).apply()
        pushHud()
    }

    /** dir: 0 වම, 1 දකුණ, 2 උඩ (පනින්න), 3 පහළ (ලිස්සන්න) */
    fun swipe(dir: Int) {
        if (state != RUNNING) return
        when (dir) {
            0 -> lane = max(-1, lane - 1)
            1 -> lane = min(1, lane + 1)
            2 -> if (py <= 0.01f) {
                vy = 11.5f
                slideT = 0f
                sound.jump()
            }
            3 -> {
                slideT = 0.75f
                if (py > 0.01f) vy = -22f else sound.slide()
            }
        }
    }

    // ----------------------------------------------------------- update
    fun update(dt: Float) {
        time += dt
        hudTimer += dt
        slideBlend += ((if (slideT > 0f) 1f else 0f) - slideBlend) * min(1f, 14f * dt)
        menuT += ((if (state == READY) 1f else 0f) - menuT) * min(1f, 4f * dt)

        when (state) {
            READY -> {
                runT += dt * 3f
                dist += dt * 3f
                preview = (preview + dt * 60f) % 360f
            }
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
        val mult = if (x2T > 0f) 2 else 1
        scoreF += dz * mult
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
        magnetT = max(0f, magnetT - dt)
        shieldT = max(0f, shieldT - dt)
        x2T = max(0f, x2T - dt)
        if (invT > 0f) invT -= dt

        for (o in obstacles) o.z += dz
        for (c in coins) {
            c.z += dz
            if (magnetT > 0f && c.z > -14f && c.z < 3f) c.x += (px - c.x) * min(1f, 9f * dt)
        }
        for (p in pickups) p.z += dz
        obstacles.removeAll { it.z > 10f }
        coins.removeAll { it.z > 10f }
        pickups.removeAll { it.z > 10f }

        sinceSpawn += dz
        if (sinceSpawn >= nextGap) {
            sinceSpawn = 0f
            spawnRow()
            nextGap = max(20f, speed * 1.05f) + rnd.nextFloat() * 10f
        }

        val h = if (slideT > 0f) 0.55f else 2.0f

        // බාධක
        if (invT <= 0f) {
            var hit: Obstacle? = null
            for (o in obstacles) {
                if (abs(px - o.lane * LANE_W) > 1.25f) continue
                val zHalf = when (o.type) { LOW -> 0.5f; HIGH -> 0.25f; else -> 0.45f }
                if (abs(o.z) > zHalf + 0.35f) continue
                val y0 = if (o.type == HIGH) 1.35f else 0f
                val y1 = when (o.type) { LOW -> 0.9f; HIGH -> 2.4f; else -> 2.6f }
                if (py + h > y0 && py < y1) {
                    hit = o
                    break
                }
            }
            if (hit != null) {
                if (shieldT > 0f) {
                    shieldT = 0f
                    invT = 1.2f
                    obstacles.remove(hit)
                    sound.shield()
                } else {
                    die()
                    return
                }
            }
        }

        // coins
        var got = false
        val cit = coins.iterator()
        while (cit.hasNext()) {
            val c = cit.next()
            if (abs(px - c.x) < 0.9f && abs(c.z) < 0.9f && py < 1.3f && py + h > 0.7f) {
                coinCount += mult
                wallet += mult
                cit.remove()
                sound.coin()
                got = true
            }
        }
        if (got) prefs.edit().putInt("wallet", wallet).apply()

        // power-ups
        val pit = pickups.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            if (abs(px - p.lane * LANE_W) < 0.9f && abs(p.z) < 0.9f && py < 1.8f && py + h > 0.6f) {
                when (p.kind) {
                    MAGNET -> magnetT = 8f
                    SHIELD -> shieldT = 12f
                    else -> x2T = 10f
                }
                pit.remove()
                sound.power()
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
            for (i in 0 until 5) coins.add(Coin(l * LANE_W, z + 5f + i * 2.2f))
        }
        if (rnd.nextFloat() < 0.14f) {
            pickups.add(Pickup(rnd.nextInt(3), rnd.nextInt(3) - 1, z + 17f))
        }
    }

    private fun die() {
        state = DEAD
        sinceDead = 0f
        sound.hit()
        if (score > best) best = score
        prefs.edit().putInt("best", best).putInt("wallet", wallet).apply()
        pushHud()
    }

    private fun pushHud() =
        onHud(Hud(score, coinCount, best, state, wallet, skinIndex, owned, magnetT, shieldT, x2T))
}
