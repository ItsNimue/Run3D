package lk.maleemahi.run3d

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private const val SR = 22050

/** තත්පර ගණනක PCM buffer එකක්, ඒකට tones එකතු කරනවා */
private class Track(seconds: Double) {
    val buf = FloatArray((seconds * SR).toInt())
    private val rnd = Random(7)

    /** wave: 0 square, 1 triangle, 2 sine, 3 noise */
    fun tone(t0: Double, dur: Double, f0: Double, f1: Double, vol: Double, wave: Int) {
        val start = (t0 * SR).toInt()
        val n = (dur * SR).toInt()
        var phase = 0.0
        for (i in 0 until n) {
            val idx = start + i
            if (idx >= buf.size) break
            val p = i.toDouble() / n
            phase += (f0 + (f1 - f0) * p) / SR
            val w = when (wave) {
                0 -> if (phase % 1.0 < 0.5) 1.0 else -1.0
                1 -> 4.0 * abs((phase % 1.0) - 0.5) - 1.0
                2 -> sin(2.0 * PI * phase)
                else -> rnd.nextDouble() * 2.0 - 1.0
            }
            val env = min(1.0, i / (0.004 * SR)) * (1.0 - p).pow(1.5)
            buf[idx] += (w * env * vol).toFloat()
        }
    }

    fun pcm(peak: Float = 0.9f): ShortArray {
        var m = 0.0001f
        for (v in buf) m = max(m, abs(v))
        val g = if (m > peak) peak / m else 1f
        return ShortArray(buf.size) { (buf[it] * g * 32767f).toInt().toShort() }
    }
}

class Sound(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("run3d", Context.MODE_PRIVATE)
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids = ConcurrentHashMap<String, Int>()

    @Volatile private var music: MediaPlayer? = null
    @Volatile private var wantMusic = false

    var muted: Boolean
        get() = prefs.getBoolean("muted", false)
        set(v) {
            prefs.edit().putBoolean("muted", v).apply()
            applyMusic()
        }

    init {
        Thread { load() }.start()
    }

    // ------------------------------------------------------------ generate
    private fun load() {
        val effects = mapOf(
            "coin" to { Track(0.3).apply {
                tone(0.0, 0.08, 988.0, 988.0, 0.4, 0)
                tone(0.07, 0.22, 1319.0, 1319.0, 0.4, 0)
            }.pcm() },
            "jump" to { Track(0.25).apply { tone(0.0, 0.2, 280.0, 720.0, 0.4, 1) }.pcm() },
            "slide" to { Track(0.3).apply { tone(0.0, 0.28, 0.0, 0.0, 0.3, 3) }.pcm() },
            "hit" to { Track(0.5).apply {
                tone(0.0, 0.45, 140.0, 40.0, 0.7, 2)
                tone(0.0, 0.3, 0.0, 0.0, 0.5, 3)
            }.pcm() },
            "power" to { Track(0.5).apply {
                val n = doubleArrayOf(523.25, 659.25, 783.99, 1046.5)
                for (i in n.indices) tone(i * 0.07, 0.18, n[i], n[i], 0.35, 0)
            }.pcm() },
            "shield" to { Track(0.45).apply {
                tone(0.0, 0.4, 900.0, 200.0, 0.45, 0)
                tone(0.0, 0.3, 0.0, 0.0, 0.3, 3)
            }.pcm() },
            "click" to { Track(0.1).apply { tone(0.0, 0.06, 900.0, 700.0, 0.35, 0) }.pcm() }
        )
        for ((name, gen) in effects) {
            val f = File(ctx.cacheDir, "v1_$name.wav")
            if (!f.exists()) writeWav(f, gen())
            ids[name] = pool.load(f.path, 1)
        }

        val mf = File(ctx.cacheDir, "v1_music.wav")
        if (!mf.exists()) writeWav(mf, buildMusic())
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(mf.path)
            mp.isLooping = true
            mp.setVolume(0.45f, 0.45f)
            mp.prepare()
            music = mp
            applyMusic()
        } catch (_: Exception) { }
    }

    private fun note(n: Int): Double = 440.0 * 2.0.pow((n - 69) / 12.0)

    private fun buildMusic(): ShortArray {
        val step = 0.2
        val t = Track(32 * step)
        val lead = intArrayOf(
            69, 72, 76, 72, 69, 72, 76, 81,
            65, 69, 72, 69, 65, 69, 72, 77,
            67, 72, 76, 72, 67, 72, 76, 79,
            67, 71, 74, 71, 67, 71, 74, 79
        )
        val roots = intArrayOf(45, 41, 48, 43)
        for (i in 0 until 32) {
            val ti = i * step
            t.tone(ti, 0.18, note(lead[i]), note(lead[i]), 0.10, 0)
            if (i % 2 == 0) {
                val r = roots[i / 8] + (if ((i / 2) % 2 == 1) 12 else 0)
                t.tone(ti, 0.38, note(r), note(r), 0.22, 1)
            }
            t.tone(ti, 0.03, 0.0, 0.0, 0.05, 3)
            if (i % 4 == 0) t.tone(ti, 0.12, 130.0, 45.0, 0.45, 2)
            if (i % 8 == 2 || i % 8 == 6) t.tone(ti, 0.1, 0.0, 0.0, 0.15, 3)
        }
        return t.pcm(0.8f)
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16)
        bb.putShort(1); bb.putShort(1); bb.putInt(SR); bb.putInt(SR * 2)
        bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        f.outputStream().use { it.write(bb.array()) }
    }

    // ---------------------------------------------------------------- play
    private fun play(name: String) {
        if (muted) return
        val id = ids[name] ?: return
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    fun coin() = play("coin")
    fun jump() = play("jump")
    fun slide() = play("slide")
    fun hit() = play("hit")
    fun power() = play("power")
    fun shield() = play("shield")
    fun click() = play("click")

    fun resumeMusic() {
        wantMusic = true
        applyMusic()
    }

    fun pauseMusic() {
        wantMusic = false
        applyMusic()
    }

    private fun applyMusic() {
        val m = music ?: return
        try {
            if (wantMusic && !muted) {
                if (!m.isPlaying) m.start()
            } else if (m.isPlaying) {
                m.pause()
            }
        } catch (_: Exception) { }
    }

    fun release() {
        try { music?.release() } catch (_: Exception) { }
        pool.release()
    }
}
