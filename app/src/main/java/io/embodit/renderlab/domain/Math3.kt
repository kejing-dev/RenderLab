package io.embodit.renderlab.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure 3D vector (metres, volume frame: +X right, +Y up, +Z toward the user). The scene converts to SDK types. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Float get() = sqrt(dot(this))
    fun norm(): V3 { val l = length; return if (l < 1e-9f) this else V3(x / l, y / l, z / l) }
    fun dist(o: V3) = (this - o).length
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    override fun toString() = "(%.3f,%.3f,%.3f)".format(x, y, z)

    companion object {
        val ZERO = V3(0f, 0f, 0f)
        val UP = V3(0f, 1f, 0f)
    }
}

/** Unit quaternion (w, x, y, z). */
data class Q4(val w: Float, val x: Float, val y: Float, val z: Float) {
    operator fun times(o: Q4) = Q4(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun rotate(v: V3): V3 {
        val u = V3(x, y, z)
        val t = u.cross(v) * 2f
        return v + t * w + u.cross(t)
    }

    fun conj() = Q4(w, -x, -y, -z)

    fun slerp(o: Q4, t: Float): Q4 {
        var d = w * o.w + x * o.x + y * o.y + z * o.z
        var b = o
        if (d < 0) { d = -d; b = Q4(-o.w, -o.x, -o.y, -o.z) }
        if (d > 0.9995f) return Q4(w + (b.w - w) * t, x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t).norm()
        val th = acos(d.coerceIn(-1f, 1f))
        val s = sin(th)
        val ka = sin((1 - t) * th) / s
        val kb = sin(t * th) / s
        return Q4(w * ka + b.w * kb, x * ka + b.x * kb, y * ka + b.y * kb, z * ka + b.z * kb)
    }

    fun norm(): Q4 { val l = sqrt(w * w + x * x + y * y + z * z); return Q4(w / l, x / l, y / l, z / l) }

    /** Angle (radians) between two orientations. */
    fun angleTo(o: Q4): Float = 2f * acos(abs(w * o.w + x * o.x + y * o.y + z * o.z).coerceAtMost(1f))

    companion object {
        val ID = Q4(1f, 0f, 0f, 0f)
        fun axis(a: V3, rad: Float): Q4 { val s = sin(rad / 2); val l = a.length; return Q4(cos(rad / 2), a.x / l * s, a.y / l * s, a.z / l * s) }

        /** The shortest rotation taking unit vector [from] onto unit vector [to]. */
        fun fromTo(from: V3, to: V3): Q4 {
            val a = from.norm(); val b = to.norm()
            val d = a.dot(b)
            if (d > 0.99999f) return ID
            if (d < -0.99999f) {
                var ax = V3(1f, 0f, 0f).cross(a)
                if (ax.length < 1e-4f) ax = V3(0f, 1f, 0f).cross(a)
                return axis(ax, Math.PI.toFloat())
            }
            val c = a.cross(b)
            return Q4(1f + d, c.x, c.y, c.z).norm()
        }

    }
}

/** A rigid pose: rotate, then translate. */
data class Pose(val p: V3, val q: Q4 = Q4.ID) {
    fun apply(v: V3) = p + q.rotate(v)
    operator fun times(o: Pose) = Pose(apply(o.p), q * o.q)
    fun lerp(o: Pose, t: Float) = Pose(p.lerp(o.p, t), q.slerp(o.q, t))
}
