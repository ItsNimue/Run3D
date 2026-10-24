package lk.maleemahi.run3d

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.floor
import kotlin.math.sin

class GameRenderer(private val game: Game) : GLSurfaceView.Renderer {

    private fun col(r: Int, g: Int, b: Int) = floatArrayOf(r / 255f, g / 255f, b / 255f)

    private val SKY = col(140, 205, 242)
    private val C_ROAD_A = col(92, 96, 108)
    private val C_ROAD_B = col(104, 108, 120)
    private val C_LINE = col(235, 235, 235)
    private val C_GRASS = col(96, 168, 80)
    private val C_TRUNK = col(110, 76, 48)
    private val C_LEAF_A = col(52, 140, 70)
    private val C_LEAF_B = col(78, 170, 86)
    private val C_CRATE = col(176, 112, 56)
    private val C_CRATE_TOP = col(214, 150, 80)
    private val C_POST = col(70, 70, 80)
    private val C_BAR = col(220, 60, 60)
    private val C_WHITE = col(245, 245, 245)
    private val C_WALL = col(90, 110, 150)
    private val C_WALL_TOP = col(130, 150, 190)
    private val C_GOLD = col(255, 200, 40)
    private val C_SHADOW = col(60, 66, 76)

    private val C_SKIN = col(244, 196, 152)
    private val C_SHIRT = col(50, 130, 240)
    private val C_PANTS = col(52, 56, 96)
    private val C_SHOE = col(245, 245, 245)
    private val C_HAIR = col(40, 28, 22)
    private val C_BAND = col(230, 50, 60)
    private val C_PACK = col(245, 120, 40)

    private val vertexSrc = """
        uniform mat4 uMVP;
        uniform mat4 uModel;
        uniform vec3 uColor;
        uniform vec3 uFog;
        attribute vec3 aPos;
        attribute vec3 aNormal;
        varying vec3 vColor;
        void main() {
            gl_Position = uMVP * vec4(aPos, 1.0);
            vec3 n = normalize((uModel * vec4(aNormal, 0.0)).xyz);
            float l = max(dot(n, normalize(vec3(0.4, 1.0, 0.6))), 0.0);
            vec3 c = uColor * (0.45 + 0.6 * l);
            float f = clamp((gl_Position.w - 35.0) / 55.0, 0.0, 1.0);
            vColor = mix(c, uFog, f);
        }
    """.trimIndent()

    private val fragmentSrc = """
        precision mediump float;
        varying vec3 vColor;
        void main() {
            gl_FragColor = vec4(vColor, 1.0);
        }
    """.trimIndent()

    private var prog = 0
    private var aPos = 0
    private var aNormal = 0
    private var uMVP = 0
    private var uModel = 0
    private var uColor = 0
    private var uFog = 0

    private lateinit var cube: FloatBuffer

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val I = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val playerBase = FloatArray(16)

    private var last = 0L

    // -------------------------------------------------------------- GL setup
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(SKY[0], SKY[1], SKY[2], 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        GLES20.glUseProgram(prog)

        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNormal = GLES20.glGetAttribLocation(prog, "aNormal")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uColor = GLES20.glGetUniformLocation(prog, "uColor")
        uFog = GLES20.glGetUniformLocation(prog, "uFog")

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNormal)
        cube = buildCube()
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

    private fun buildCube(): FloatBuffer {
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
        val arr = FloatArray(6 * 6 * 6)
        var k = 0
        for (f in faces) {
            for (i in idx) {
                arr[k++] = f[3 + i * 3]
                arr[k++] = f[4 + i * 3]
                arr[k++] = f[5 + i * 3]
                arr[k++] = f[0]
                arr[k++] = f[1]
                arr[k++] = f[2]
            }
        }
        val fb = ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(arr)
        fb.position(0)
        return fb
    }

    // ------------------------------------------------------------ drawing
    /** model = base * T(p) * Ry * Rx * T(o) * S : ඔක්කොම දේවල් මේකෙන් අඳිනවා (cube එකක්) */
    private fun part(
        base: FloatArray, c: FloatArray, sx: Float, sy: Float, sz: Float,
        px: Float = 0f, py: Float = 0f, pz: Float = 0f,
        rx: Float = 0f, ry: Float = 0f,
        ox: Float = 0f, oy: Float = 0f, oz: Float = 0f
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
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        var dt = (now - last) / 1e9f
        last = now
        if (dt > 0.05f) dt = 0.05f
        game.update(dt)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(prog)
        GLES20.glUniform3f(uFog, SKY[0], SKY[1], SKY[2])

        cube.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, cube)
        cube.position(3)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 24, cube)

        val cx = game.px * 0.45f
        Matrix.setLookAtM(view, 0, cx, 4.8f, 10f, cx, 1.2f, -6f, 0f, 1f, 0f)
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
                Game.LOW -> {
                    part(I, C_CRATE, 1.8f, 0.9f, 1f, px = x, py = 0.45f, pz = o.z)
                    part(I, C_CRATE_TOP, 1.9f, 0.12f, 1.1f, px = x, py = 0.93f, pz = o.z)
                }
                Game.HIGH -> {
                    part(I, C_POST, 0.2f, 2.4f, 0.2f, px = x - 0.9f, py = 1.2f, pz = o.z)
                    part(I, C_POST, 0.2f, 2.4f, 0.2f, px = x + 0.9f, py = 1.2f, pz = o.z)
                    part(I, C_BAR, 2f, 0.7f, 0.3f, px = x, py = 1.75f, pz = o.z)
                    part(I, C_WHITE, 2f, 0.18f, 0.04f, px = x, py = 1.75f, pz = o.z + 0.16f)
                }
                else -> {
                    part(I, C_WALL, 1.9f, 2.6f, 0.9f, px = x, py = 1.3f, pz = o.z)
                    part(I, C_WALL_TOP, 2f, 0.1f, 1f, px = x, py = 2.65f, pz = o.z)
                }
            }
        }
        val spin = (game.time * 200f) % 360f
        for (c in game.coins) {
            part(I, C_GOLD, 0.6f, 0.6f, 0.12f, px = c.lane * Game.LANE_W, py = 1f, pz = c.z, ry = spin)
        }
    }

    private fun drawPlayer() {
        val g = game
        val s = 1f / (1f + g.py * 0.35f)
        part(I, C_SHADOW, 0.9f * s, 0.02f, 0.9f * s, px = g.px, py = 0.02f)

        val b = playerBase
        Matrix.setIdentityM(b, 0)
        Matrix.translateM(b, 0, g.px, g.py, 0f)
        if (g.slideBlend > 0.01f) Matrix.rotateM(b, 0, -78f * g.slideBlend, 1f, 0f, 0f)
        if (g.deadBlend > 0.01f) Matrix.rotateM(b, 0, 85f * g.deadBlend, 1f, 0f, 0f)

        var sw = if (g.state == Game.READY) sin(g.time * 3f) * 6f else sin(g.runT) * 48f
        var legL = sw
        var legR = -sw
        var armL = -sw
        var armR = sw
        if (g.py > 0.05f) {          // අහසේ ඉන්නකොට
            legL = 40f; legR = -25f
            armL = 150f; armR = 150f
        }
        if (g.state == Game.DEAD) {
            legL = 10f; legR = -10f; armL = 30f; armR = -30f
        }

        // කකුල් + සපත්තු
        part(b, C_PANTS, 0.3f, 0.8f, 0.32f, px = -0.17f, py = 0.95f, rx = legL, oy = -0.4f)
        part(b, C_SHOE, 0.32f, 0.2f, 0.45f, px = -0.17f, py = 0.95f, rx = legL, oy = -0.85f, oz = -0.06f)
        part(b, C_PANTS, 0.3f, 0.8f, 0.32f, px = 0.17f, py = 0.95f, rx = legR, oy = -0.4f)
        part(b, C_SHOE, 0.32f, 0.2f, 0.45f, px = 0.17f, py = 0.95f, rx = legR, oy = -0.85f, oz = -0.06f)

        // ශරීරය + බෑගය
        part(b, C_SHIRT, 0.74f, 0.72f, 0.42f, py = 1.3f)
        part(b, C_PACK, 0.5f, 0.55f, 0.22f, py = 1.3f, pz = 0.31f)

        // අත්
        part(b, C_SHIRT, 0.2f, 0.45f, 0.24f, px = -0.48f, py = 1.6f, rx = armL, oy = -0.225f)
        part(b, C_SKIN, 0.18f, 0.3f, 0.2f, px = -0.48f, py = 1.6f, rx = armL, oy = -0.6f)
        part(b, C_SHIRT, 0.2f, 0.45f, 0.24f, px = 0.48f, py = 1.6f, rx = armR, oy = -0.225f)
        part(b, C_SKIN, 0.18f, 0.3f, 0.2f, px = 0.48f, py = 1.6f, rx = armR, oy = -0.6f)

        // හිස
        part(b, C_SKIN, 0.5f, 0.5f, 0.5f, py = 1.92f)
        part(b, C_HAIR, 0.54f, 0.2f, 0.54f, py = 2.16f)
        part(b, C_HAIR, 0.54f, 0.36f, 0.1f, py = 1.98f, pz = 0.22f)
        part(b, C_BAND, 0.55f, 0.07f, 0.55f, py = 2.05f)
    }
}
