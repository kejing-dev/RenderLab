package io.embodit.renderlab.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A triangle mesh in plain arrays (metres): positions, smooth normals, uv, triangle indices. Built on the CPU (pure,
 * unit-testable); only the scene turns it into an SDK MeshResource. The same vertices feed the self-computed shadows
 * (design lightingModel.shadow), so the shadow always matches what is drawn.
 */
class MeshData(val pos: FloatArray, val nor: FloatArray, val uv: FloatArray, val idx: IntArray) {
    val vertexCount: Int get() = pos.size / 3
    val triangles: Int get() = idx.size / 3

    fun point(i: Int) = V3(pos[3 * i], pos[3 * i + 1], pos[3 * i + 2])

    fun points(max: Int = 400): List<V3> {
        val step = maxOf(1, vertexCount / max)
        return (0 until vertexCount step step).map { point(it) }
    }

    fun bounds(): Pair<V3, V3> {
        var lo = V3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        var hi = V3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until vertexCount) {
            val p = point(i)
            lo = V3(minOf(lo.x, p.x), minOf(lo.y, p.y), minOf(lo.z, p.z))
            hi = V3(maxOf(hi.x, p.x), maxOf(hi.y, p.y), maxOf(hi.z, p.z))
        }
        return lo to hi
    }
}

/** Accumulates vertices and triangles. */
class MeshBuilder {
    private val p = ArrayList<Float>()
    private val n = ArrayList<Float>()
    private val t = ArrayList<Float>()
    private val i = ArrayList<Int>()
    val count: Int get() = p.size / 3

    fun v(pos: V3, nor: V3, u: Float = 0f, w: Float = 0f): Int {
        p += pos.x; p += pos.y; p += pos.z
        val nn = nor.norm(); n += nn.x; n += nn.y; n += nn.z
        t += u; t += w
        return count - 1
    }

    fun tri(a: Int, b: Int, c: Int) { i += a; i += b; i += c }
    fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }

    fun build() = MeshData(p.toFloatArray(), n.toFloatArray(), t.toFloatArray(), i.toIntArray())
}

/** Procedural shapes (design subjects[].build: true circles, smooth normals, exact sizes; gate B Q2 = procedural). */
object Shapes {
    private const val TAU = (2 * PI).toFloat()

    /** Box [size] centred on [c], turned [yaw] radians about Y; flat faces (24 vertices). */
    fun box(c: V3, size: V3, yaw: Float = 0f): MeshData {
        val b = MeshBuilder()
        val q = Q4.axis(V3.UP, yaw)
        val h = size * 0.5f
        val faces = listOf(
            V3(1f, 0f, 0f) to V3(0f, 0f, -1f), V3(-1f, 0f, 0f) to V3(0f, 0f, 1f),
            V3(0f, 1f, 0f) to V3(1f, 0f, 0f), V3(0f, -1f, 0f) to V3(1f, 0f, 0f),
            V3(0f, 0f, 1f) to V3(1f, 0f, 0f), V3(0f, 0f, -1f) to V3(-1f, 0f, 0f),
        )
        for ((nrm, tan) in faces) {
            val bit = nrm.cross(tan)
            fun corner(s: Float, r: Float): V3 {
                val local = V3(nrm.x * h.x + tan.x * s * h.x + bit.x * r * h.x, nrm.y * h.y + tan.y * s * h.y + bit.y * r * h.y, nrm.z * h.z + tan.z * s * h.z + bit.z * r * h.z)
                return c + q.rotate(local)
            }
            val wn = q.rotate(nrm)
            val a = b.v(corner(-1f, -1f), wn, 0f, 0f)
            val bb = b.v(corner(1f, -1f), wn, 1f, 0f)
            val cc = b.v(corner(1f, 1f), wn, 1f, 1f)
            val d = b.v(corner(-1f, 1f), wn, 0f, 1f)
            b.quad(a, d, cc, bb)
        }
        return fixWinding(b.build())
    }

    /** UV sphere of radius [r] about [c]. */
    fun sphere(c: V3, r: Float, seg: Int = 64, rings: Int = 32): MeshData {
        val b = MeshBuilder()
        for (j in 0..rings) {
            val th = PI.toFloat() * j / rings
            for (k in 0..seg) {
                val ph = TAU * k / seg
                val n = V3(sin(th) * sin(ph), cos(th), sin(th) * cos(ph))
                b.v(c + n * r, n, k.toFloat() / seg, j.toFloat() / rings)
            }
        }
        val row = seg + 1
        for (j in 0 until rings) for (k in 0 until seg) {
            val a = j * row + k
            b.quad(a, a + row, a + row + 1, a + 1)
        }
        return fixWinding(b.build())
    }

    /**
     * Lathe: [profile] (radius, height) points turned about the vertical axis through [base] ([seg] segments). Normals
     * follow the profile's tangent; the first / last point on the axis close the solid.
     */
    fun lathe(base: V3, profile: List<Pair<Float, Float>>, seg: Int = 64): MeshData {
        val b = MeshBuilder()
        val m = profile.size
        val norms = (0 until m).map { i ->
            val a = profile[maxOf(0, i - 1)]; val c = profile[minOf(m - 1, i + 1)]
            val dr = c.first - a.first; val dy = c.second - a.second
            Pair(dy, -dr)
        }
        for (i in 0 until m) {
            val (r, y) = profile[i]
            val (nr, ny) = norms[i]
            for (k in 0..seg) {
                val ph = TAU * k / seg
                val s = sin(ph); val co = cos(ph)
                b.v(V3(base.x + r * s, base.y + y, base.z + r * co), V3(nr * s, ny, nr * co), k.toFloat() / seg, i.toFloat() / (m - 1))
            }
        }
        val row = seg + 1
        for (i in 0 until m - 1) for (k in 0 until seg) {
            val a = i * row + k
            b.quad(a, a + 1, a + row + 1, a + row)
        }
        return fixWinding(b.build())
    }

    /**
     * Catmull-Rom refinement of a lathe profile ([k] points per segment) so the turned silhouette reads round; segments
     * that touch the axis (r ~ 0, the flat bottom / top caps) stay straight.
     */
    fun smoothProfile(p: List<Pair<Float, Float>>, k: Int = 4): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (i in 0 until p.size - 1) {
            val a = p[i]; val b = p[i + 1]
            val straight = a.first < 0.002f || b.first < 0.002f
            val p0 = p[maxOf(0, i - 1)]; val p3 = p[minOf(p.size - 1, i + 2)]
            for (s in 0 until k) {
                val u = s.toFloat() / k
                if (straight || s == 0) { out += (a.first + (b.first - a.first) * u) to (a.second + (b.second - a.second) * u); continue }
                val u2 = u * u; val u3 = u2 * u
                fun cr(x0: Float, x1: Float, x2: Float, x3: Float) = 0.5f * (2 * x1 + (x2 - x0) * u + (2 * x0 - 5 * x1 + 4 * x2 - x3) * u2 + (x3 - x0 + 3 * x1 - 3 * x2) * u3)
                out += maxOf(0f, cr(p0.first, a.first, b.first, p3.first)) to cr(p0.second, a.second, b.second, p3.second)
            }
        }
        out += p.last()
        return out
    }

    /** Cone (apex up) of base radius [r], height [h], base centre [base]; smooth sides + flat bottom. */
    fun cone(base: V3, r: Float, h: Float, seg: Int = 48): MeshData {
        val b = MeshBuilder()
        val slope = r / h
        for (k in 0..seg) {
            val ph = TAU * k / seg
            val s = sin(ph); val co = cos(ph)
            val n = V3(s, slope, co)
            b.v(V3(base.x + r * s, base.y, base.z + r * co), n, k.toFloat() / seg, 0f)
            b.v(V3(base.x, base.y + h, base.z), n, k.toFloat() / seg, 1f)
        }
        for (k in 0 until seg) { val a = 2 * k; b.tri(a, a + 2, a + 1) }
        val centre = b.v(base, V3(0f, -1f, 0f))
        val first = b.count
        for (k in 0..seg) { val ph = TAU * k / seg; b.v(V3(base.x + r * sin(ph), base.y, base.z + r * cos(ph)), V3(0f, -1f, 0f)) }
        for (k in 0 until seg) b.tri(centre, first + k + 1, first + k)
        return fixWinding(b.build())
    }

    /** A tube of radius [r] along a Catmull-Rom curve through [pts] ([along] x [around] segments, smooth normals). */
    fun tube(pts: List<V3>, r: Float, along: Int = 24, around: Int = 16): MeshData {
        val b = MeshBuilder()
        fun cr(t: Float): V3 {
            val n = pts.size - 1
            val f = (t * n).coerceIn(0f, n - 1e-4f)
            val i = f.toInt(); val u = f - i
            val p0 = pts[maxOf(0, i - 1)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[minOf(n, i + 2)]
            val u2 = u * u; val u3 = u2 * u
            return (p1 * 2f + (p2 - p0) * u + (p0 * 2f - p1 * 5f + p2 * 4f - p3) * u2 + (p3 - p0 + p1 * 3f - p2 * 3f) * u3) * 0.5f
        }
        var ref = V3(0f, 0f, 1f)
        for (i in 0..along) {
            val t = i.toFloat() / along
            val c = cr(t)
            val tan = (cr(minOf(1f, t + 0.01f)) - cr(maxOf(0f, t - 0.01f))).norm()
            var nx = ref.cross(tan).norm()
            if (nx.length < 0.5f) nx = V3(1f, 0f, 0f)
            val ny = tan.cross(nx).norm()
            ref = ny
            for (k in 0..around) {
                val ph = TAU * k / around
                val n = nx * cos(ph) + ny * sin(ph)
                b.v(c + n * r, n, t, k.toFloat() / around)
            }
        }
        val row = around + 1
        for (i in 0 until along) for (k in 0 until around) {
            val a = i * row + k
            b.quad(a, a + 1, a + row + 1, a + row)
        }
        return fixWinding(b.build())
    }

    /**
     * Part of a torus in the plane spanned by [u] and [v] about [c]: ring radius [big], tube radius [small], from angle
     * [a0] to [a1] (radians, measured from [u] toward [v]).
     */
    fun torusArc(c: V3, u: V3, v: V3, big: Float, small: Float, a0: Float, a1: Float, ring: Int = 40, tubeSeg: Int = 16): MeshData {
        val b = MeshBuilder()
        val w = u.cross(v).norm()
        for (i in 0..ring) {
            val a = a0 + (a1 - a0) * i / ring
            val radial = u * cos(a) + v * sin(a)
            val centre = c + radial * big
            for (k in 0..tubeSeg) {
                val ph = TAU * k / tubeSeg
                val n = radial * cos(ph) + w * sin(ph)
                b.v(centre + n * small, n, i.toFloat() / ring, k.toFloat() / tubeSeg)
            }
        }
        val row = tubeSeg + 1
        for (i in 0 until ring) for (k in 0 until tubeSeg) {
            val a = i * row + k
            b.quad(a, a + 1, a + row + 1, a + row)
        }
        return fixWinding(b.build())
    }

    /**
     * Makes every triangle wind counter-clockwise seen from the side its vertex normals point to (the SDK culls back
     * faces). Generators above pick their quad order loosely; this one pass fixes it so all of them render the same.
     */
    fun fixWinding(m: MeshData): MeshData {
        val idx = m.idx.copyOf()
        for (t in 0 until idx.size / 3) {
            val a = m.point(idx[3 * t]); val b = m.point(idx[3 * t + 1]); val c = m.point(idx[3 * t + 2])
            val fn = (b - a).cross(c - a)
            var vn = V3.ZERO
            for (j in 0 until 3) { val k = idx[3 * t + j]; vn = vn + V3(m.nor[3 * k], m.nor[3 * k + 1], m.nor[3 * k + 2]) }
            if (fn.dot(vn) < 0) { val s = idx[3 * t + 1]; idx[3 * t + 1] = idx[3 * t + 2]; idx[3 * t + 2] = s }
        }
        return MeshData(m.pos, m.nor, m.uv, idx)
    }

    /** One flat polygon (convex, in order) as a triangle fan, facing [normal]. */
    fun fan(poly: List<V3>, normal: V3): MeshData {
        val b = MeshBuilder()
        poly.forEach { b.v(it, normal) }
        for (i in 1 until poly.size - 1) b.tri(0, i, i + 1)
        return fixWinding(b.build())
    }

    fun merge(parts: List<MeshData>): MeshData {
        val pos = ArrayList<Float>(); val nor = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>()
        var base = 0
        for (m in parts) {
            pos.addAll(m.pos.toList()); nor.addAll(m.nor.toList()); uv.addAll(m.uv.toList())
            m.idx.forEach { idx += it + base }
            base += m.vertexCount
        }
        return MeshData(pos.toFloatArray(), nor.toFloatArray(), uv.toFloatArray(), idx.toIntArray())
    }

    @Suppress("unused")
    private fun len(x: Float, y: Float) = sqrt(x * x + y * y)
}

/**
 * The three subjects (design subjects[]; sizes and placement as in the sample's three.js scene). Each is a list of
 * convex-ish parts: [Subject.meshes] for drawing, [Subject.parts] (vertex samples per part) for the shadow.
 */
class Subject(val id: SubjectId, val meshes: List<MeshData>, val parts: List<List<V3>>) {
    val triangles: Int get() = meshes.sumOf { it.triangles }

    companion object {
        private val FY = Studio.FY

        fun build(id: SubjectId): Subject = when (id) {
            SubjectId.PLASTER -> {
                val cube = Shapes.box(V3(-0.05f, FY + 0.04f, -0.06f), V3(0.08f, 0.08f, 0.08f), 0.35f)
                val ball = Shapes.sphere(V3(0.055f, FY + 0.035f, -0.065f), 0.035f, 64, 32)
                val cone = Shapes.cone(V3(0f, FY, -0.145f), 0.03f, 0.12f, 48)
                Subject(id, listOf(cube, ball, cone), listOf(cube.points(), ball.points(), cone.points()))
            }
            SubjectId.TEAPOT -> {
                val at = V3(0f, FY, -0.10f)
                val body = Shapes.lathe(at, Shapes.smoothProfile(listOf(0f to 0f, 0.045f to 0.002f, 0.06f to 0.022f, 0.065f to 0.048f, 0.058f to 0.075f, 0.042f to 0.09f, 0f to 0.09f), 5), 64)
                val lid = Shapes.lathe(at, Shapes.smoothProfile(listOf(0.001f to 0.09f, 0.04f to 0.09f, 0.03f to 0.1f, 0.012f to 0.104f, 0.008f to 0.112f, 0.001f to 0.12f), 4), 48)
                val spout = Shapes.tube(listOf(V3(0.05f, FY + 0.03f, -0.10f), V3(0.075f, FY + 0.045f, -0.10f), V3(0.09f, FY + 0.075f, -0.10f), V3(0.10f, FY + 0.09f, -0.10f)), 0.01f, 40, 20)
                // handle: half a ring on the left (-X), in the XY plane
                val handle = Shapes.torusArc(V3(-0.062f, FY + 0.05f, -0.10f), V3(0f, 1f, 0f), V3(-1f, 0f, 0f), 0.03f, 0.008f, 0f, PI.toFloat(), 40, 16)
                Subject(id, listOf(body, lid, spout, handle), listOf(body.points(), lid.points(), spout.points(), handle.points()))
            }
            SubjectId.VASE -> {
                val prof = listOf(0.001f to 0f, 0.035f to 0f, 0.048f to 0.02f, 0.05f to 0.05f, 0.04f to 0.08f, 0.022f to 0.1f, 0.015f to 0.12f, 0.014f to 0.14f, 0.02f to 0.158f, 0.022f to 0.16f)
                val m = Shapes.lathe(V3(0f, FY, -0.10f), Shapes.smoothProfile(prof, 5), 64)
                val all = m.points(1000)
                // two convex parts for the shadow: belly and neck (design lightingModel.shadow)
                Subject(id, listOf(m), listOf(all.filter { it.y <= FY + 0.09f }, all.filter { it.y >= FY + 0.085f }))
            }
        }
    }
}
