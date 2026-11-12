package io.embodit.renderlab.domain

import io.embodit.renderlab.domain.Studio.FY
import io.embodit.renderlab.domain.Studio.WALL_TOP
import io.embodit.renderlab.domain.Studio.WZ
import io.embodit.renderlab.domain.Studio.X0
import io.embodit.renderlab.domain.Studio.X1
import io.embodit.renderlab.domain.Studio.ZF

/** A 2D point: on the floor (x, z); on the wall (x, y). */
data class P2(val a: Float, val b: Float)

/** Floor polygons (x, z at y = FY) and wall polygons (x, y at z = WZ), one per convex part that reaches the surface. */
data class Hulls(val floor: List<List<P2>>, val wall: List<List<P2>>) {
    val isEmpty: Boolean get() = floor.isEmpty() && wall.isEmpty()
}

/**
 * The self-computed shadow (design lightingModel.shadow, same as sample.html): every convex part is projected twice
 * along the key light, onto the floor plane and (when the light runs toward the wall) onto the wall plane; each
 * projection's convex hull is clipped to the receiving rectangle (Sutherland-Hodgman). Scoring rasterises the same
 * hulls on a 1 cm grid (floor 66 x 52, wall 66 x 54) and compares by IoU.
 */
object Shadow {
    const val CELL = 0.01f
    const val NX = 66
    const val NZ = 52
    const val NY = 54
    const val CELLS = NX * (NZ + NY)

    /** [toLight] = direction from C toward the light (the light travels the other way). */
    fun hulls(parts: List<List<V3>>, toLight: V3): Hulls {
        val d = toLight.norm() * -1f
        val fl = ArrayList<List<P2>>()
        val wa = ArrayList<List<P2>>()
        for (pts in parts) {
            if (d.y < -1e-4f) {
                val pr = pts.map { p -> val t = (FY - p.y) / d.y; P2(p.x + t * d.x, p.z + t * d.z) }
                clip(hull(pr), X0, X1, WZ, ZF).takeIf { it.size >= 3 }?.let { fl += it }
            }
            if (d.z < -1e-4f) {
                val pr = pts.map { p -> val t = (WZ - p.z) / d.z; P2(p.x + t * d.x, p.y + t * d.y) }
                clip(hull(pr), X0, X1, FY, WALL_TOP).takeIf { it.size >= 3 }?.let { wa += it }
            }
        }
        return Hulls(fl, wa)
    }

    /** Whether every projected point lands inside the floor + wall rectangles (PCG rejection ①). */
    fun fitsInside(parts: List<List<V3>>, toLight: V3): Boolean {
        val d = toLight.norm() * -1f
        if (d.y >= -1e-4f) return false
        for (pts in parts) for (p in pts) {
            val t = (FY - p.y) / d.y
            val x = p.x + t * d.x; val z = p.z + t * d.z
            if (z >= WZ) { if (x < X0 || x > X1 || z > ZF) return false; continue }
            if (d.z >= -1e-4f) return false
            val tw = (WZ - p.z) / d.z
            val xw = p.x + tw * d.x; val yw = p.y + tw * d.y
            if (xw < X0 || xw > X1 || yw > WALL_TOP || yw < FY - 1e-3f) return false
        }
        return true
    }

    /** Monotone-chain convex hull, counter-clockwise. */
    fun hull(input: List<P2>): List<P2> {
        val p = input.sortedWith(compareBy<P2>({ it.a }, { it.b }))
        if (p.size < 3) return p
        fun cr(o: P2, a: P2, b: P2) = (a.a - o.a) * (b.b - o.b) - (a.b - o.b) * (b.a - o.a)
        val lo = ArrayList<P2>(); val up = ArrayList<P2>()
        for (q in p) { while (lo.size >= 2 && cr(lo[lo.size - 2], lo[lo.size - 1], q) <= 0) lo.removeAt(lo.size - 1); lo += q }
        for (i in p.indices.reversed()) { val q = p[i]; while (up.size >= 2 && cr(up[up.size - 2], up[up.size - 1], q) <= 0) up.removeAt(up.size - 1); up += q }
        lo.removeAt(lo.size - 1); up.removeAt(up.size - 1)
        return lo + up
    }

    /** Clip a convex polygon to a <= a <= aMax, bMin <= b <= bMax. */
    fun clip(poly: List<P2>, aMin: Float, aMax: Float, bMin: Float, bMax: Float): List<P2> {
        var out = poly
        val edges = listOf(Triple(0, aMin, 1f), Triple(0, aMax, -1f), Triple(1, bMin, 1f), Triple(1, bMax, -1f))
        for ((ax, v, s) in edges) {
            val inp = out
            if (inp.isEmpty()) break
            val next = ArrayList<P2>()
            fun c(p: P2) = if (ax == 0) p.a else p.b
            for (i in inp.indices) {
                val a = inp[i]; val b = inp[(i + 1) % inp.size]
                val ia = (c(a) - v) * s >= 0; val ib = (c(b) - v) * s >= 0
                if (ia) next += a
                if (ia != ib) { val t = (v - c(a)) / (c(b) - c(a)); next += P2(a.a + t * (b.a - a.a), a.b + t * (b.b - a.b)) }
            }
            out = next
        }
        return out
    }

    /** Point in a counter-clockwise convex polygon (edges included). */
    fun inside(poly: List<P2>, a: Float, b: Float): Boolean {
        if (poly.size < 3) return false
        for (i in poly.indices) {
            val p = poly[i]; val q = poly[(i + 1) % poly.size]
            if ((q.a - p.a) * (b - p.b) - (q.b - p.b) * (a - p.a) < -1e-9f) return false
        }
        return true
    }

    /** 1 cm raster: index j * NX + i; rows 0 until NZ = floor (z from the wall forward), NZ until NZ + NY = wall (y up). */
    fun raster(h: Hulls): BooleanArray {
        val m = BooleanArray(CELLS)
        for (poly in h.floor) fill(poly, m, 0, NZ, WZ)
        for (poly in h.wall) fill(poly, m, NZ, NY, FY)
        return m
    }

    private fun fill(poly: List<P2>, m: BooleanArray, row0: Int, rows: Int, b0: Float) {
        val minA = poly.minOf { it.a }; val maxA = poly.maxOf { it.a }
        val minB = poly.minOf { it.b }; val maxB = poly.maxOf { it.b }
        val i0 = ((minA - X0) / CELL - 0.5f).toInt().coerceIn(0, NX - 1)
        val i1 = ((maxA - X0) / CELL + 0.5f).toInt().coerceIn(0, NX - 1)
        val j0 = ((minB - b0) / CELL - 0.5f).toInt().coerceIn(0, rows - 1)
        val j1 = ((maxB - b0) / CELL + 0.5f).toInt().coerceIn(0, rows - 1)
        for (j in j0..j1) {
            val b = b0 + (j + 0.5f) * CELL
            for (i in i0..i1) {
                val k = (row0 + j) * NX + i
                if (!m[k] && inside(poly, X0 + (i + 0.5f) * CELL, b)) m[k] = true
            }
        }
    }

    fun count(m: BooleanArray) = m.count { it }

    fun iou(a: BooleanArray, b: BooleanArray): Float {
        var i = 0; var u = 0
        for (k in a.indices) if (a[k] || b[k]) { u++; if (a[k] && b[k]) i++ }
        return if (u == 0) 0f else i.toFloat() / u
    }

    /** The centre of raster cell [k] in volume coordinates (1 mm off its surface). */
    fun cellPoint(k: Int): V3 {
        val i = k % NX; val j = k / NX
        val x = X0 + (i + 0.5f) * CELL
        return if (j < NZ) V3(x, FY + 0.001f, WZ + (j + 0.5f) * CELL) else V3(x, FY + (j - NZ + 0.5f) * CELL, WZ + 0.001f)
    }

    /**
     * Share of the shadow cells [m] the nominal eye can see (design scoring.visibility, PCG rejection ②). A cell is
     * hidden when the eye -> cell ray passes through a part (part silhouette from the eye, part in front of the cell).
     * Cells under the subject's own footprint are left out (sample.html "去掉主体脚下那块").
     */
    fun visibleShare(m: BooleanArray, parts: List<List<V3>>, eye: V3 = Studio.EYE): Float {
        fun proj(p: V3): P2 { val v = p - eye; val k = -1f / v.z; return P2(v.x * k, v.y * k) }
        val sil = parts.map { pts -> hull(pts.map(::proj)) to pts.minOf { it.dist(eye) } }
        val foot = parts.map { pts -> hull(pts.filter { it.y < FY + 0.01f }.map { P2(it.x, it.z) }) }
        var seen = 0; var total = 0
        for (k in m.indices) {
            if (!m[k]) continue
            val p = cellPoint(k)
            if (k / NX < NZ && foot.any { it.size >= 3 && inside(it, p.x, p.z) }) continue
            total++
            val q = proj(p)
            val d = p.dist(eye)
            if (sil.none { (h, near) -> near < d && inside(h, q.a, q.b) }) seen++
        }
        return if (total == 0) 0f else seen.toFloat() / total
    }

    /** The dashed fence outline: every hull edge that is not inside another hull of the same surface (sample.html walk). */
    fun outline(list: List<List<P2>>, step: Float = 0.002f): List<Pair<P2, P2>> {
        val out = ArrayList<Pair<P2, P2>>()
        list.forEachIndexed { hi, h ->
            for (i in h.indices) {
                val a = h[i]; val b = h[(i + 1) % h.size]
                val len = kotlin.math.hypot(b.a - a.a, b.b - a.b)
                if (len < 1e-6f) continue
                val n = maxOf(1, kotlin.math.ceil(len / step).toInt())
                for (k in 0 until n) {
                    val p0 = P2(a.a + (b.a - a.a) * k / n, a.b + (b.b - a.b) * k / n)
                    val p1 = P2(a.a + (b.a - a.a) * (k + 1) / n, a.b + (b.b - a.b) * (k + 1) / n)
                    val mid = P2((p0.a + p1.a) / 2, (p0.b + p1.b) / 2)
                    if (list.withIndex().any { (oi, o) -> oi != hi && insideStrict(o, mid.a, mid.b) }) continue
                    out += p0 to p1
                }
            }
        }
        return out
    }

    private fun insideStrict(poly: List<P2>, a: Float, b: Float): Boolean {
        if (poly.size < 3) return false
        for (i in poly.indices) {
            val p = poly[i]; val q = poly[(i + 1) % poly.size]
            if ((q.a - p.a) * (b - p.b) - (q.b - p.b) * (a - p.a) <= 1e-7f) return false
        }
        return true
    }
}
