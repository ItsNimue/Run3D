package lk.maleemahi.run3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** vertex: pos(3) normal(3) uv(2) = floats 8 */
fun uploadVbo(arr: FloatArray): Int {
    val fb = ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    fb.put(arr)
    fb.position(0)
    val ids = IntArray(1)
    GLES20.glGenBuffers(1, ids, 0)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
    GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, arr.size * 4, fb, GLES20.GL_STATIC_DRAW)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    return ids[0]
}

class Prim(val vbo: Int, val count: Int, val tex: Int, val color: FloatArray)

private class RawPrim(val verts: FloatArray, val color: FloatArray, val img: Int)

class GlbModel(val prims: List<Prim>) {

    companion object {
        /** GL thread එකේ කැඳවන්න. file නැත්නම් / කියවන්න බැරි නම් null */
        fun load(ctx: Context, path: String, size: Float, byHeight: Boolean): GlbModel? = try {
            val bytes = ctx.assets.open(path).use { it.readBytes() }
            parse(bytes, size, byHeight)
        } catch (e: Exception) {
            null
        }

        private fun isPot(n: Int) = n > 0 && (n and (n - 1)) == 0

        private fun quat(x: Float, y: Float, z: Float, w: Float): FloatArray {
            val xx = x * x; val yy = y * y; val zz = z * z
            val xy = x * y; val xz = x * z; val yz = y * z
            val wx = w * x; val wy = w * y; val wz = w * z
            return floatArrayOf(
                1 - 2 * (yy + zz), 2 * (xy + wz), 2 * (xz - wy), 0f,
                2 * (xy - wz), 1 - 2 * (xx + zz), 2 * (yz + wx), 0f,
                2 * (xz + wy), 2 * (yz - wx), 1 - 2 * (xx + yy), 0f,
                0f, 0f, 0f, 1f
            )
        }

        private fun parse(data: ByteArray, size: Float, byHeight: Boolean): GlbModel? {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.getInt(0) != 0x46546C67) return null            // "glTF"
            var off = 12
            var json: JSONObject? = null
            var binStart = 0
            while (off + 8 <= data.size) {
                val len = bb.getInt(off)
                val type = bb.getInt(off + 4)
                val start = off + 8
                if (type == 0x4E4F534A) json = JSONObject(String(data, start, len, Charsets.UTF_8))
                else if (type == 0x004E4942) binStart = start
                off = start + len
            }
            val j = json ?: return null
            val bin = binStart
            val accessors = j.getJSONArray("accessors")
            val views = j.getJSONArray("bufferViews")
            val nodes = j.getJSONArray("nodes")
            val meshes = j.getJSONArray("meshes")

            fun floats(ai: Int, comps: Int): FloatArray {
                val a = accessors.getJSONObject(ai)
                val count = a.getInt("count")
                val ctype = a.getInt("componentType")
                val norm = a.optBoolean("normalized", false)
                val bv = views.getJSONObject(a.getInt("bufferView"))
                val base = bin + bv.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
                val csize = when (ctype) { 5126, 5125 -> 4; 5123, 5122 -> 2; else -> 1 }
                val stride = bv.optInt("byteStride", 0).let { if (it == 0) csize * comps else it }
                val out = FloatArray(count * comps)
                for (i in 0 until count) {
                    for (c in 0 until comps) {
                        val p = base + i * stride + c * csize
                        out[i * comps + c] = when (ctype) {
                            5126 -> bb.getFloat(p)
                            5123 -> (bb.getShort(p).toInt() and 0xFFFF).let { if (norm) it / 65535f else it.toFloat() }
                            5121 -> (bb.get(p).toInt() and 0xFF).let { if (norm) it / 255f else it.toFloat() }
                            5122 -> bb.getShort(p).let { if (norm) max(it / 32767f, -1f) else it.toFloat() }
                            else -> bb.get(p).let { if (norm) max(it / 127f, -1f) else it.toFloat() }
                        }
                    }
                }
                return out
            }

            fun indices(ai: Int): IntArray {
                val a = accessors.getJSONObject(ai)
                val count = a.getInt("count")
                val ctype = a.getInt("componentType")
                val bv = views.getJSONObject(a.getInt("bufferView"))
                val base = bin + bv.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
                val csize = when (ctype) { 5125 -> 4; 5123 -> 2; else -> 1 }
                val stride = bv.optInt("byteStride", 0).let { if (it == 0) csize else it }
                return IntArray(count) { i ->
                    val p = base + i * stride
                    when (ctype) {
                        5125 -> bb.getInt(p)
                        5123 -> bb.getShort(p).toInt() and 0xFFFF
                        else -> bb.get(p).toInt() and 0xFF
                    }
                }
            }

            fun nodeMatrix(n: JSONObject): FloatArray {
                val m = FloatArray(16)
                if (n.has("matrix")) {
                    val a = n.getJSONArray("matrix")
                    for (i in 0 until 16) m[i] = a.getDouble(i).toFloat()
                    return m
                }
                Matrix.setIdentityM(m, 0)
                n.optJSONArray("translation")?.let {
                    Matrix.translateM(m, 0, it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat())
                }
                n.optJSONArray("rotation")?.let {
                    val r = quat(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat())
                    val t = FloatArray(16)
                    Matrix.multiplyMM(t, 0, m, 0, r, 0)
                    System.arraycopy(t, 0, m, 0, 16)
                }
                n.optJSONArray("scale")?.let {
                    Matrix.scaleM(m, 0, it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat())
                }
                return m
            }

            fun material(mi: Int): Pair<FloatArray, Int> {
                var color = floatArrayOf(1f, 1f, 1f)
                var img = -1
                if (mi >= 0) {
                    val pbr = j.getJSONArray("materials").getJSONObject(mi).optJSONObject("pbrMetallicRoughness")
                    pbr?.optJSONArray("baseColorFactor")?.let {
                        color = floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat())
                    }
                    val ti = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1) ?: -1
                    if (ti >= 0) img = j.getJSONArray("textures").getJSONObject(ti).optInt("source", -1)
                }
                return color to img
            }

            val raws = ArrayList<RawPrim>()

            fun addMesh(mi: Int, world: FloatArray) {
                val prims = meshes.getJSONObject(mi).getJSONArray("primitives")
                for (pi in 0 until prims.length()) {
                    val p = prims.getJSONObject(pi)
                    if (p.optInt("mode", 4) != 4) continue
                    val at = p.getJSONObject("attributes")
                    if (!at.has("POSITION")) continue
                    val pos = floats(at.getInt("POSITION"), 3)
                    val nor = if (at.has("NORMAL")) floats(at.getInt("NORMAL"), 3) else null
                    val uv = if (at.has("TEXCOORD_0")) floats(at.getInt("TEXCOORD_0"), 2) else null
                    val idx = if (p.has("indices")) indices(p.getInt("indices")) else IntArray(pos.size / 3) { it }
                    val tris = idx.size / 3 * 3
                    val out = FloatArray(tris * 8)
                    val v = FloatArray(4)
                    val r = FloatArray(4)
                    var k = 0
                    for (t in 0 until tris) {
                        val vi = idx[t]
                        v[0] = pos[vi * 3]; v[1] = pos[vi * 3 + 1]; v[2] = pos[vi * 3 + 2]; v[3] = 1f
                        Matrix.multiplyMV(r, 0, world, 0, v, 0)
                        out[k++] = r[0]; out[k++] = r[1]; out[k++] = r[2]
                        if (nor != null) {
                            v[0] = nor[vi * 3]; v[1] = nor[vi * 3 + 1]; v[2] = nor[vi * 3 + 2]; v[3] = 0f
                            Matrix.multiplyMV(r, 0, world, 0, v, 0)
                            val l = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]).let { if (it > 0f) it else 1f }
                            out[k++] = r[0] / l; out[k++] = r[1] / l; out[k++] = r[2] / l
                        } else {
                            out[k++] = 0f; out[k++] = 1f; out[k++] = 0f
                        }
                        if (uv != null) {
                            out[k++] = uv[vi * 2]; out[k++] = uv[vi * 2 + 1]
                        } else {
                            out[k++] = 0f; out[k++] = 0f
                        }
                    }
                    val (color, img) = material(p.optInt("material", -1))
                    raws.add(RawPrim(out, color, img))
                }
            }

            fun visit(ni: Int, parent: FloatArray) {
                val n = nodes.getJSONObject(ni)
                val world = FloatArray(16)
                Matrix.multiplyMM(world, 0, parent, 0, nodeMatrix(n), 0)
                if (n.has("mesh")) addMesh(n.getInt("mesh"), world)
                val ch: JSONArray? = n.optJSONArray("children")
                if (ch != null) for (c in 0 until ch.length()) visit(ch.getInt(c), world)
            }

            val roots = j.optJSONArray("scenes")
                ?.getJSONObject(j.optInt("scene", 0))
                ?.optJSONArray("nodes") ?: return null
            val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            for (i in 0 until roots.length()) visit(roots.getInt(i), identity)
            if (raws.isEmpty()) return null

            // ---- ප්‍රමාණය / මැද / පතුල normalize කිරීම
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (r in raws) {
                var i = 0
                while (i < r.verts.size) {
                    minX = min(minX, r.verts[i]); maxX = max(maxX, r.verts[i])
                    minY = min(minY, r.verts[i + 1]); maxY = max(maxY, r.verts[i + 1])
                    minZ = min(minZ, r.verts[i + 2]); maxZ = max(maxZ, r.verts[i + 2])
                    i += 8
                }
            }
            val dim = if (byHeight) maxY - minY else max(maxX - minX, max(maxY - minY, maxZ - minZ))
            val s = if (dim > 0f) size / dim else 1f
            val cx = (minX + maxX) / 2f
            val cz = (minZ + maxZ) / 2f
            for (r in raws) {
                var i = 0
                while (i < r.verts.size) {
                    r.verts[i] = (r.verts[i] - cx) * s
                    r.verts[i + 1] = (r.verts[i + 1] - minY) * s
                    r.verts[i + 2] = (r.verts[i + 2] - cz) * s
                    i += 8
                }
            }

            // ---- textures decode (උපරිම 1024px) + GL වලට upload
            val texByImg = HashMap<Int, Int>()
            val prims = ArrayList<Prim>()
            for (r in raws) {
                var tex = 0
                if (r.img >= 0) {
                    tex = texByImg.getOrPut(r.img) {
                        val img = j.getJSONArray("images").getJSONObject(r.img)
                        if (!img.has("bufferView")) return@getOrPut 0
                        val bv = views.getJSONObject(img.getInt("bufferView"))
                        val o = bin + bv.optInt("byteOffset", 0)
                        val len = bv.getInt("byteLength")
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(data, o, len, bounds)
                        var ss = 1
                        while (bounds.outWidth / ss > 1024) ss *= 2
                        val bmp: Bitmap = BitmapFactory.decodeByteArray(
                            data, o, len, BitmapFactory.Options().apply { inSampleSize = ss }
                        ) ?: return@getOrPut 0
                        val ids = IntArray(1)
                        GLES20.glGenTextures(1, ids, 0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                        if (isPot(bmp.width) && isPot(bmp.height)) {
                            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
                        } else {
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                        }
                        bmp.recycle()
                        ids[0]
                    }
                }
                prims.add(Prim(uploadVbo(r.verts), r.verts.size / 8, tex, r.color))
            }
            return GlbModel(prims)
        }
    }
}
