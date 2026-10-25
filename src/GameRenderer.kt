package lk.maleemahi.run3d

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

class GameRenderer(private val ctx: Context, private val game: Game) : GLSurfaceView.Renderer {

    // ---- ඔයාගේ models වලට අනුව වෙනස් කරන්න
    private val PLAYER_YAW = 180f    // character මුහුණ ඉදිරිය (-Z) දිහාවට. මුහුණ පිටිපස්සට පෙනෙනවා නම් 0 කරන්න
    private val PLAYER_SIZE = 2.2f   // character උස

    private class Slot(val file: String, val size: Float, val byHeight: Boolean, val yaw: Float) {
        var model: GlbModel? = null
        var tried = false
    }

    private val slots = hashMapOf(
        "player" to Slot("models/player.glb", PLAYER_SIZE, true, PLAYER_YAW),
        "crate" to Slot("models/crate.glb", 1.9f, false, 0f),
        "barrier" to Slot("models/barrier.glb", 2.4f, true, 0f),
        "wall" to Slot("models/wall.glb", 2.6f, true, 0f),
        "coin" to Slot("models/coin.glb", 0.7f, false, 0f),
        "magnet" to Slot("models/magnet.glb", 0.9f, false, 0f),
        "shield" to Slot("models/shield.glb", 0.9f, false, 0f),
        "x2" to Slot("models/x2.glb", 0.9f, false, 0f),
        "tree" to Slot("models/tree.glb", 3.8f, true, 0f)
    )

    private val SKY = rgb(140, 205, 242)
    private val C_ROAD_A = rgb(92, 96, 108)
    private val C_ROAD_B = rgb(104, 108, 120)
    private val C_LINE = rgb(235, 235, 235)
    private val C_GRASS = rgb(96, 168, 80)
    private val C_TRUNK = rgb(110, 76, 48)
    private val C_LEAF_A = rgb(52, 140, 70)
    private val C_LEAF_B = rgb(78, 170, 86)
    private val C_CRATE = rgb(176, 112, 56)
    private val C_CRATE_TOP = rgb(214, 150, 80)
    private val C_POST = rgb(70, 70, 80)
    private val C_BAR = rgb(220, 60, 60)
    private val C_WHITE = rgb(245, 245, 245)
    private val C_WALL = rgb(90, 110, 150)
    private val C_WALL_TOP = rgb(130, 150, 190)
    private val C_GOLD = rgb(255, 200, 40)
    private val C_SHADOW = rgb(60, 66, 76)
    private val C_MAG = rgb(230, 50, 60)
    private val C_SHIELD = rgb(40, 140, 255)
    private val C_SHIELD_L = rgb(130, 210, 255)
    private val C_X2 = rgb(150, 70, 230)

    private val vertexSrc = """
        uniform mat4 uMVP;
        uniform mat4 uModel;
        attribute vec3 aPos;
        attribute vec3 aNormal;
        attribute vec2 aUV;
        varying float vLight;
        varying float vFog;
        varying vec2 vUV;
        void main() {
            gl_Position = uMVP * vec4(aPos, 1.0);
            vec3 n = normalize((uModel * vec4(aNormal, 0.0)).xyz);
            float l = max(dot(n, normalize(vec3(0.4, 1.0, 0.6))), 0.0);
            vLight = 0.45 + 0.6 * l;
            vFog = clamp((gl_Position.w - 35.0) / 55.0, 0.0, 1.0);
            vUV = aUV;
        }
    """.trimIndent()

    private val fragmentSrc = """
        precision mediump float;
        uniform vec3 uColor;
        uniform vec3 uFog;
        uniform float uAlpha;
        uniform float uUseTex;
        uniform sampler2D uTex;
        varying float vLight;
        varying float vFog;
        varying vec2 vUV;
        void main() {
            vec3 base = uColor;
            if (uUseTex > 0.5) {
                base = texture2D(uTex, vUV).rgb * uColor;
            }
            gl_FragColor = vec4(mix(base * vLight, uFog, vFog), uAlpha);
        }
    """.trimIndent()

    private var prog = 0
    private var aPos = 0
    private var aNormal = 0
    private var aUV = 0
    private var uMVP = 0
    private var uModel = 0
    private var uColor = 0
    private var uFog = 0
    private var uAlpha = 0
    private var uUseTex = 0
    private var uTex = 0

    private var cubeVbo = 0
    private var boundVbo = -1

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val I = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val playerBase = FloatArray(16)
    private val pickBase = FloatArray(16)

    private var last = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(SKY[0], SKY[1], SKY[2], 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        GLES20.glUseProgram(prog)

        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNormal = GLES20.glGetAttribLocation(prog, "aNormal")
        aUV = GLES20.glGetAttribLocation(prog, "aUV")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uColor = GLES20.glGetUniformLocation(prog, "uColor")
        uFog = GLES20.glGetUniformLocation(prog, "uFog")
        uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha")
        uUseTex = GLES20.glGetUniformLocation(prog, "uUseTex")
        uTex = GLES20.glGetUniformLocation(prog, "uTex")

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glUniform1i(uTex, 0)

        cubeVbo = uploadVbo(buildCube())
        boundVbo = -1

        // ඇති models ඔක්කොම මුලින්ම load කරනවා (නැති ඒවා cube විදිහට)
        for (k in slots.keys.toList()) model(k)
        last = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(proj, 0, 75f, width.toFloat() / height, 0.5f, 130f)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private fun buildCube(): FloatArray {
        val a = 0.5f
        val b = -0.5f
        val faces = arrayOf(
            floatArrayOf(0f, 0f, 1f, b, b, a, a, b, a, a, a, a, b, a, a),
            floatArrayOf(0f, 0f, -1f, a, b, b, b, b, b, b, a, b, a, a, b),
            floatArrayOf(1f, 0f, 0f, a, b, a, a, b, b, a, a, b, a, a, a),
            floatArrayOf(-1f, 0f, 0f, b, b, b, b, b, a, b, a, a, b, a, b),
            floatArrayOf(0f, 1f, 0f, b, a, a, a, a, a, a, a, b, b, a, b),
            floatArrayOf(0f, -1f, 0f, b, b, b, a, b, b, a, b, a, b, b, a)
        )
        val idx = intArrayOf(0, 1, 2, 0, 2, 3)
        val arr = FloatArray(6 * 6 * 8)
        var k = 0
        for (f in faces) {
            for (i in idx) {
                arr[k++] = f[3 + i * 3]
                arr[k++] = f[4 + i * 3]
                arr[k++] = f[5 + i * 3]
                arr[k++] = f[0]
                arr[k++] = f[1]
                arr[k++] = f[2]
                arr[k++] = 0f
                arr[k++] = 0f
            }
        }
        return arr
    }

    // ------------------------------------------------------------ models
    private fun model(key: String): GlbModel? {
        val s = slots[key] ?: return null
        if (!s.tried) {
            s.tried = true
            s.model = GlbModel.load(ctx, s.file, s.size, s.byHeight)
            boundVbo = -1
        }
        return s.model
    }

    /** skin එකට වෙනම model එකක් (player_N.glb) තියෙනවා නම් ඒක, නැත්නම් player.glb */
    private fun playerModel(skin: Int): GlbModel? {
        val k = "player_$skin"
        if (!slots.containsKey(k)) slots[k] = Slot("models/player_$skin.glb", PLAYER_SIZE, true, PLAYER_YAW)
        return model(k) ?: model("player")
    }

    private fun bindVbo(vbo: Int) {
        if (boundVbo == vbo) return
        boundVbo = vbo
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 32, 0)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 32, 12)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 32, 24)
    }

    private fun drawModel(
        m: GlbModel, base: FloatArray, px: Float, py: Float, pz: Float, yaw: Float,
        rx: Float = 0f, rz: Float = 0f, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f,
        a: Float = 1f
    ) {
        System.arraycopy(base, 0, model, 0, 16)
        Matrix.translateM(model, 0, px, py, pz)
        if (yaw != 0f) Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        if (rz != 0f) Matrix.rotateM(model, 0, rz, 0f, 0f, 1f)
        if (rx != 0f) Matrix.rotateM(model, 0, rx, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, sx, sy, sz)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform1f(uAlpha, a)
        for (p in m.prims) {
            bindVbo(p.vbo)
            if (p.tex != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, p.tex)
                GLES20.glUniform1f(uUseTex, 1f)
            } else {
                GLES20.glUniform1f(uUseTex, 0f)
            }
            GLES20.glUniform3f(uColor, p.color[0], p.color[1], p.color[2])
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, p.count)
        }
    }

    /** slot එකේ model එක තියෙනවා නම් අඳිනවා (true), නැත්නම් false (cube fallback එකට) */
    private fun drawSlot(key: String, px: Float, py: Float, pz: Float, spin: Float = 0f, scale: Float = 1f): Boolean {
        val m = model(key) ?: return false
        drawModel(m, I, px, py, pz, slots[key]!!.yaw + spin, sx = scale, sy = scale, sz = scale)
        return true
    }

    /** model = base * T(p) * Ry * Rx * T(o) * S : cube එකක් */
    private fun part(
        base: FloatArray, c: FloatArray, sx: Float, sy: Float, sz: Float,
        px: Float = 0f, py: Float = 0f, pz: Float = 0f,
        rx: Float = 0f, ry: Float = 0f,
        ox: Float = 0f, oy: Float = 0f, oz: Float = 0f,
        a: Float = 1f
    ) {
        System.arraycopy(base, 0, model, 0, 16)
        Matrix.translateM(model, 0, px, py, pz)
        if (ry != 0f) Matrix.rotateM(model, 0, ry, 0f, 1f, 0f)
        if (rx != 0f) Matrix.rotateM(model, 0, rx, 1f, 0f, 0f)
        if (ox != 0f || oy != 0f || oz != 0f) Matrix.translateM(model, 0, ox, oy, oz)
        Matrix.scaleM(model, 0, sx, sy, sz)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform3f(uColor, c[0], c[1], c[2])
        GLES20.glUniform1f(uAlpha, a)
        GLES20.glUniform1f(uUseTex, 0f)
        bindVbo(cubeVbo)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
    }

    // ------------------------------------------------------------- frame
    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        var dt = (now - last) / 1e9f
        last = now
        if (dt > 0.05f) dt = 0.05f
        game.update(dt)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(prog)
        GLES20.glUniform3f(uFog, SKY[0], SKY[1], SKY[2])

        val m = game.menuT
        val cx = game.px * 0.45f * (1f - m)
        val ey = 4.8f + (2.5f - 4.8f) * m
        val ez = 10f + (4.8f - 10f) * m
        val tz = -6f + 6f * m
        Matrix.setLookAtM(view, 0, cx, ey, ez, cx, 1.2f, tz, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        drawWorld()
        drawObjects()
        drawPlayer()
    }

    private fun hash(i: Int): Int {
        var x = i * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return (x xor (x ushr 16)) and 0x7fffffff
    }

    private fun drawWorld() {
        val d = game.dist
        val j = floor(d / 10f).toInt()
        val scroll = d - j * 10f
        for (k in -1..9) {
            val i = j + k
            val zs = -k * 10f + scroll
            val zc = zs - 5f
            part(I, if ((i and 1) == 0) C_ROAD_A else C_ROAD_B, 7f, 0.2f, 10f, py = -0.1f, pz = zc)
            part(I, C_LINE, 0.08f, 0.02f, 4f, px = -1.1f, py = 0.01f, pz = zc)
            part(I, C_LINE, 0.08f, 0.02f, 4f, px = 1.1f, py = 0.01f, pz = zc)
            part(I, C_GRASS, 30f, 0.2f, 10f, px = -18.5f, py = -0.2f, pz = zc)
            part(I, C_GRASS, 30f, 0.2f, 10f, px = 18.5f, py = -0.2f, pz = zc)

            val h = hash(i)
            if ((h and 3) != 0) {
                tree(-(6.2f + ((h shr 2) and 3) * 1.6f), zs - 1f - ((h shr 4) and 7), h shr 7)
            }
            val h2 = hash(i + 7919)
            if ((h2 and 3) != 0) {
                tree(6.2f + ((h2 shr 2) and 3) * 1.6f, zs - 1f - ((h2 shr 4) and 7), h2 shr 7)
            }
        }
    }

    private fun tree(x: Float, z: Float, seed: Int) {
        val sc = 0.8f + (seed and 3) * 0.15f
        if (drawSlot("tree", x, 0f, z, spin = (seed and 7) * 45f, scale = sc)) return
        val big = 1f + (seed and 3) * 0.25f
        part(I, C_TRUNK, 0.5f, 1.6f * big, 0.5f, px = x, py = 0.8f * big, pz = z)
        part(
            I, if ((seed and 4) == 0) C_LEAF_A else C_LEAF_B,
            2f * big, 2f * big, 2f * big, px = x, py = 2.5f * big, pz = z
        )
    }

    private fun drawObjects() {
        for (o in game.obstacles) {
            val x = o.lane * Game.LANE_W
            when (o.type) {
                Game.LOW -> if (!drawSlot("crate", x, 0f, o.z)) {
                    part(I, C_CRATE, 1.8f, 0.9f, 1f, px = x, py = 0.45f, pz = o.z)
                    part(I, C_CRATE_TOP, 1.9f, 0.12f, 1.1f, px = x, py = 0.93f, pz = o.z)
                }
                Game.HIGH -> if (!drawSlot("barrier", x, 0f, o.z)) {
                    part(I, C_POST, 0.2f, 2.4f, 0.2f, px = x - 0.9f, py = 1.2f, pz = o.z)
                    part(I, C_POST, 0.2f, 2.4f, 0.2f, px = x + 0.9f, py = 1.2f, pz = o.z)
                    part(I, C_BAR, 2f, 0.7f, 0.3f, px = x, py = 1.75f, pz = o.z)
                    part(I, C_WHITE, 2f, 0.18f, 0.04f, px = x, py = 1.75f, pz = o.z + 0.16f)
                }
                else -> if (!drawSlot("wall", x, 0f, o.z)) {
                    part(I, C_WALL, 1.9f, 2.6f, 0.9f, px = x, py = 1.3f, pz = o.z)
                    part(I, C_WALL_TOP, 2f, 0.1f, 1f, px = x, py = 2.65f, pz = o.z)
                }
            }
        }
        val spin = (game.time * 200f) % 360f
        for (c in game.coins) {
            if (!drawSlot("coin", c.x, 0.65f, c.z, spin = spin)) {
                part(I, C_GOLD, 0.6f, 0.6f, 0.12f, px = c.x, py = 1f, pz = c.z, ry = spin)
            }
        }
        for (p in game.pickups) drawPickup(p)
    }

    private fun drawPickup(p: Pickup) {
        val x = p.lane * Game.LANE_W
        val y = 1.3f + sin(game.time * 3f + p.z * 0.1f) * 0.15f
        val spin = (game.time * 120f) % 360f
        val key = when (p.kind) { Game.MAGNET -> "magnet"; Game.SHIELD -> "shield"; else -> "x2" }
        if (drawSlot(key, x, y - 0.45f, p.z, spin = spin)) return

        val pb = pickBase
        Matrix.setIdentityM(pb, 0)
        Matrix.translateM(pb, 0, x, y, p.z)
        Matrix.rotateM(pb, 0, spin, 0f, 1f, 0f)
        when (p.kind) {
            Game.MAGNET -> {
                part(pb, C_MAG, 0.2f, 0.7f, 0.25f, px = -0.28f)
                part(pb, C_MAG, 0.2f, 0.7f, 0.25f, px = 0.28f)
                part(pb, C_MAG, 0.76f, 0.2f, 0.25f, py = -0.28f)
                part(pb, C_WHITE, 0.2f, 0.18f, 0.25f, px = -0.28f, py = 0.4f)
                part(pb, C_WHITE, 0.2f, 0.18f, 0.25f, px = 0.28f, py = 0.4f)
            }
            Game.SHIELD -> {
                part(pb, C_SHIELD, 0.7f, 0.8f, 0.25f)
                part(pb, C_SHIELD_L, 0.4f, 0.45f, 0.3f)
            }
            else -> {
                part(pb, C_X2, 0.7f, 0.7f, 0.4f)
                part(pb, C_GOLD, 0.3f, 0.3f, 0.45f)
            }
        }
    }

    private fun drawPlayer() {
        val g = game
        val sk = g.skin
        val s = 1f / (1f + g.py * 0.35f)
        part(I, C_SHADOW, 0.9f * s, 0.02f, 0.9f * s, px = g.px, py = 0.02f)

        val blinkHidden = g.invT > 0f && ((g.time * 14f).toInt() and 1) == 0

        if (!blinkHidden) {
            val b = playerBase
            Matrix.setIdentityM(b, 0)
            Matrix.translateM(b, 0, g.px, g.py, 0f)
            if (g.state == Game.READY) Matrix.rotateM(b, 0, g.preview, 0f, 1f, 0f)
            if (g.slideBlend > 0.01f) Matrix.rotateM(b, 0, -78f * g.slideBlend, 1f, 0f, 0f)
            if (g.deadBlend > 0.01f) Matrix.rotateM(b, 0, 85f * g.deadBlend, 1f, 0f, 0f)

            val pm = playerModel(g.skinIndex)
            if (pm != null) {
                // model එක: animation නැතුව bounce + lean + tilt වලින් ජීවය දෙනවා
                val running = g.state == Game.RUNNING && g.py <= 0.05f && g.slideBlend < 0.3f
                val bob = if (running) abs(sin(g.runT)) * 0.14f else 0f
                val lean = if (g.state == Game.RUNNING && g.slideBlend < 0.3f) -8f else 0f
                val roll = if (g.state == Game.RUNNING) -(g.lane * Game.LANE_W - g.px) * 6f else 0f
                val breathe = if (g.state == Game.READY) 1f + sin(g.time * 2f) * 0.012f else 1f
                drawModel(pm, b, 0f, bob, 0f, slots["player"]!!.yaw, rx = lean, rz = roll, sy = breathe)
            } else {
                drawCubePlayer(b, g, sk)
            }
        }

        if (g.shieldT > 0f && g.state == Game.RUNNING) {
            val a = if (g.shieldT < 2f) 0.12f + 0.16f * ((g.time * 10f).toInt() and 1) else 0.28f
            GLES20.glDepthMask(false)
            part(I, C_SHIELD_L, 1.9f, 2.7f, 1.9f, px = g.px, py = g.py + 1.15f, a = a)
            GLES20.glDepthMask(true)
        }
    }

    private fun drawCubePlayer(b: FloatArray, g: Game, sk: Skin) {
        val sw = if (g.state == Game.READY) sin(g.time * 3f) * 6f else sin(g.runT) * 48f
        var legL = sw
        var legR = -sw
        var armL = -sw
        var armR = sw
        if (g.py > 0.05f) {
            legL = 40f; legR = -25f
            armL = 150f; armR = 150f
        }
        if (g.state == Game.DEAD) {
            legL = 10f; legR = -10f; armL = 30f; armR = -30f
        }

        part(b, sk.pants, 0.3f, 0.8f, 0.32f, px = -0.17f, py = 0.95f, rx = legL, oy = -0.4f)
        part(b, sk.shoe, 0.32f, 0.2f, 0.45f, px = -0.17f, py = 0.95f, rx = legL, oy = -0.85f, oz = -0.06f)
        part(b, sk.pants, 0.3f, 0.8f, 0.32f, px = 0.17f, py = 0.95f, rx = legR, oy = -0.4f)
        part(b, sk.shoe, 0.32f, 0.2f, 0.45f, px = 0.17f, py = 0.95f, rx = legR, oy = -0.85f, oz = -0.06f)

        part(b, sk.shirt, 0.74f, 0.72f, 0.42f, py = 1.3f)
        part(b, sk.pack, 0.5f, 0.55f, 0.22f, py = 1.3f, pz = 0.31f)

        part(b, sk.shirt, 0.2f, 0.45f, 0.24f, px = -0.48f, py = 1.6f, rx = armL, oy = -0.225f)
        part(b, sk.skin, 0.18f, 0.3f, 0.2f, px = -0.48f, py = 1.6f, rx = armL, oy = -0.6f)
        part(b, sk.shirt, 0.2f, 0.45f, 0.24f, px = 0.48f, py = 1.6f, rx = armR, oy = -0.225f)
        part(b, sk.skin, 0.18f, 0.3f, 0.2f, px = 0.48f, py = 1.6f, rx = armR, oy = -0.6f)

        part(b, sk.skin, 0.5f, 0.5f, 0.5f, py = 1.92f)
        part(b, sk.hair, 0.54f, 0.2f, 0.54f, py = 2.16f)
        part(b, sk.hair, 0.54f, 0.36f, 0.1f, py = 1.98f, pz = 0.22f)
        part(b, sk.band, 0.55f, 0.07f, 0.55f, py = 2.05f)
    }
}
