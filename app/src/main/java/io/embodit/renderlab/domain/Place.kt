package io.embodit.renderlab.domain

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.tan

/**
 * Full Space placement. v0.1.3 (headset 2026-11-14, seated: "生成在我腿这边"; profile D54 补): heights follow the eye
 * at start, never the floor: the subject centre sits [SUBJECT_DROP_DEG] below the line of sight for a seated (1.2 m)
 * or standing (1.6 m) eye alike. At that height the studio (wall top above the eye) and the system window (opens on the
 * line of sight, ~0.057°/dp, kinetica / region-snap measurement) would overlap, so they stand side by side (D41: window
 * frame and studio >= 10° apart): the studio [STUDIO_SIDE_DEG] right of where you look, the window [WINDOW_SIDE_DEG]
 * left (the window gate waits until you look there). Yaw only, upright (D9). Placed at the first valid head pose and on
 * [叫回面前]; it never follows the head.
 */
object Place {
    /** Studio root, horizontally ahead of the eye (front orbs ~0.55 m: a direct pinch still reaches them). */
    const val AHEAD = 0.70f
    /** D54: subject centre this far below the line of sight (10–15°). */
    const val SUBJECT_DROP_DEG = 13f
    /** Subject centre in studio space (the three subjects are 8–12 cm tall on the floor at z -0.10). */
    val SUBJECT_C = V3(0f, Studio.FY + 0.05f, -0.10f)
    /** Studio root height relative to the eye (+ = above). */
    val ROOT_DY: Float = -tan(SUBJECT_DROP_DEG * Studio.D2R) * (AHEAD - SUBJECT_C.z) - SUBJECT_C.y
    const val STUDIO_SIDE_DEG = 31f
    const val WINDOW_SIDE_DEG = 31f
    /** The window gate opens when you look within this of the window's direction (and level). */
    const val WINDOW_YAW_TOL = 6f

    /** Head forward (-Z of the head) flattened onto the floor plane, or null when the head looks straight up / down. */
    fun flatForward(q: Q4): V3? {
        val f = q.rotate(V3(0f, 0f, -1f))
        val flat = V3(f.x, 0f, f.z)
        return if (flat.length < 1e-3f) null else flat.norm()
    }

    /** [v] turned about the vertical by [deg] (+ = to the left, seen from above). */
    fun yawed(v: V3, deg: Float): V3 = Q4.axis(V3.UP, deg * Studio.D2R).rotate(v)

    /** Signed yaw from [a] to [b] (flat unit vectors), degrees, + = b is to the left of a. */
    fun yawBetween(a: V3, b: V3): Float = atan2(a.z * b.x - a.x * b.z, a.x * b.x + a.z * b.z) / Studio.D2R

    /** Head pitch in degrees, + up. */
    fun pitchDeg(q: Q4): Float = asin(q.rotate(V3(0f, 0f, -1f)).y.coerceIn(-1f, 1f)) / Studio.D2R

    /** An all-zero position + identity rotation is the tracker's "not ready" frame, not a pose (lesson 2026-10-29). */
    fun valid(p: V3, q: Q4): Boolean =
        p.x.isFinite() && p.y.isFinite() && p.z.isFinite() && !(p.length < 1e-4f && abs(q.w - 1f) < 1e-5f)

    /** Studio pose in Stage space: [STUDIO_SIDE_DEG] right of the look direction, its +Z (user side) facing the head, upright. */
    fun studio(headPos: V3, headRot: Q4): Pose = studioAt(headPos, yawed(flatForward(headRot) ?: V3(0f, 0f, -1f), -STUDIO_SIDE_DEG))

    /** Studio straight along [dir] (flat unit vector from the eye). */
    fun studioAt(headPos: V3, dir: V3): Pose {
        val toUser = dir * -1f
        return Pose(headPos + dir * AHEAD + V3(0f, ROOT_DY, 0f), Q4.axis(V3.UP, atan2(toUser.x, toUser.z)))
    }

    /** Where the window should open (flat unit vector from the eye): [WINDOW_SIDE_DEG] left of the look direction at placement. */
    fun windowDir(headRot: Q4): V3 = yawed(flatForward(headRot) ?: V3(0f, 0f, -1f), WINDOW_SIDE_DEG)

    /** The window's direction for a studio already placed at [studio] (the same pair as [studio] + [windowDir]). */
    fun windowDirFor(headPos: V3, studio: Pose): V3 {
        val d = V3(studio.p.x - headPos.x, 0f, studio.p.z - headPos.z).norm()
        return yawed(d, STUDIO_SIDE_DEG + WINDOW_SIDE_DEG)
    }

    /** Head yaw away from [dir] in degrees (0 = looking that way); null without a usable forward. */
    fun yawOff(headRot: Q4, dir: V3): Float? = flatForward(headRot)?.let { yawBetween(it, dir) }

    const val LEVEL_PLATE_DIST = 0.75f

    /**
     * The "look here, the window opens here" plate while the window waits (level head + looking toward [dir]): 0.75 m
     * out along the window's direction at eye height, upright, facing back (D9). It marks the spot the window opens at,
     * so it never covers the studio (side by side).
     */
    fun levelPlate(headPos: V3, dir: V3): Pose {
        val pos = headPos + dir * LEVEL_PLATE_DIST
        return Pose(pos, Q4.axis(V3.UP, atan2(-dir.x, -dir.z)))
    }

    fun toLocal(studio: Pose, world: V3): V3 = studio.q.conj().rotate(world - studio.p)
    fun dirToLocal(studio: Pose, dir: V3): V3 = studio.q.conj().rotate(dir)
    fun toWorld(studio: Pose, local: V3): V3 = studio.apply(local)
}

/**
 * Window opens only with a level head (native-window-move §7 item 9, D9): pure Kotlin, unit tested. The caller feeds
 * time and head pitch (degrees, + up; null without a valid head pose) every frame.
 */
class LevelGate(val maxPitchDeg: Float = 10f, val dwellMs: Long = 300L, val noHeadMs: Long = 5000L, val maxYawDeg: Float = Place.WINDOW_YAW_TOL) {
    var pending: String? = null; private set
    private var askedMs = 0L; private var levelSinceMs = -1L; private var noHeadSinceMs = -1L
    val waiting get() = pending != null
    fun ask(why: String, now: Long) { if (pending == null) askedMs = now; pending = why; levelSinceMs = -1L; noHeadSinceMs = -1L }
    fun cancel() { pending = null; levelSinceMs = -1L; noHeadSinceMs = -1L }
    fun waitedMs(now: Long) = if (pending == null) 0L else now - askedMs

    /**
     * The reason to open now, null = keep waiting. [yawOffDeg]: how far the head looks away from where the window should
     * open (null = no preference); it must be within [maxYawDeg] too.
     */
    fun step(now: Long, pitchDeg: Float?, yawOffDeg: Float? = null): String? {
        val why = pending ?: return null
        if (pitchDeg == null || !pitchDeg.isFinite()) {
            levelSinceMs = -1L
            if (noHeadSinceMs < 0L) noHeadSinceMs = now
            if (now - noHeadSinceMs < noHeadMs) return null
            cancel(); return "$why/nohead"
        }
        noHeadSinceMs = -1L
        if (abs(pitchDeg) > maxPitchDeg || (yawOffDeg != null && abs(yawOffDeg) > maxYawDeg)) { levelSinceMs = -1L; return null }
        if (levelSinceMs < 0L) levelSinceMs = now
        if (now - levelSinceMs < dwellMs) return null
        cancel(); return why
    }

    fun hintWanted(pitchDeg: Float?, yawOffDeg: Float? = null) =
        pending != null && pitchDeg != null && (abs(pitchDeg) > maxPitchDeg || (yawOffDeg != null && abs(yawOffDeg) > maxYawDeg))
}

/** A ray / sphere hit along a unit direction: the distance t, or null. */
fun raySphere(o: V3, d: V3, c: V3, r: Float): Float? {
    val oc = o - c
    val b = oc.dot(d)
    val cc = oc.dot(oc) - r * r
    val disc = b * b - cc
    if (disc < 0) return null
    val t = -b - kotlin.math.sqrt(disc)
    return if (t >= 0) t else (-b + kotlin.math.sqrt(disc)).takeIf { it >= 0 }
}
