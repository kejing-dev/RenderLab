package io.embodit.renderlab.domain

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Full Space placement (user change 2026-11-12: Full Space, the studio on a desk-height spot in front of you). The
 * studio is placed from the head pose only at the first valid frame and on [叫回面前]: yaw only, upright (profile D9:
 * pitch / roll never reach the studio), 0.55 m ahead (front orbs ~0.4 m ahead of you, within arm reach for a direct pinch), its floor 0.85 m
 * below the eye (desk height for a standing or seated user). It never follows the head afterwards.
 */
object Place {
    const val AHEAD = 0.55f
    /** Studio centre below the eye; the studio floor top is a further 0.30 m down. */
    const val BELOW_EYE = 0.55f

    /** Head forward (-Z of the head) flattened onto the floor plane, or null when the head looks straight up / down. */
    fun flatForward(q: Q4): V3? {
        val f = q.rotate(V3(0f, 0f, -1f))
        val flat = V3(f.x, 0f, f.z)
        return if (flat.length < 1e-3f) null else flat.norm()
    }

    /** Head pitch in degrees, + up. */
    fun pitchDeg(q: Q4): Float = asin(q.rotate(V3(0f, 0f, -1f)).y.coerceIn(-1f, 1f)) / Studio.D2R

    /** An all-zero position + identity rotation is the tracker's "not ready" frame, not a pose (lesson 2026-10-29). */
    fun valid(p: V3, q: Q4): Boolean =
        p.x.isFinite() && p.y.isFinite() && p.z.isFinite() && !(p.length < 1e-4f && abs(q.w - 1f) < 1e-5f)

    /** Studio pose in Stage space: its +Z (the user side) faces back toward the head, upright. */
    fun studio(headPos: V3, headRot: Q4): Pose {
        val f = flatForward(headRot) ?: V3(0f, 0f, -1f)
        val toUser = f * -1f
        val yaw = atan2(toUser.x, toUser.z)
        return Pose(headPos + f * AHEAD + V3(0f, -BELOW_EYE, 0f), Q4.axis(V3.UP, yaw))
    }

    /** Lowest pitch of the "look straight ahead" plate: above the wall-top strip and every orb plate (QA #1). */
    const val LEVEL_PLATE_MIN_PITCH = -12f
    const val LEVEL_PLATE_DIST = 0.75f

    /**
     * The "look straight ahead" plate while the window waits for a level head: 0.75 m out on the head's line of sight,
     * pitch clamped to [-12°, 40°], upright, facing back. Looking down at the studio it therefore hangs above the
     * studio's wall top instead of over the fill light's name plate (QA #1 panel-reopened.png).
     */
    fun levelPlate(headPos: V3, headRot: Q4): Pose {
        val f = flatForward(headRot) ?: V3(0f, 0f, -1f)
        val pitch = pitchDeg(headRot).coerceIn(LEVEL_PLATE_MIN_PITCH, 40f) * Studio.D2R
        val pos = headPos + (f * kotlin.math.cos(pitch) + V3.UP * kotlin.math.sin(pitch)) * LEVEL_PLATE_DIST
        return Pose(pos, Q4.axis(V3.UP, atan2(-f.x, -f.z)))
    }

    fun toLocal(studio: Pose, world: V3): V3 = studio.q.conj().rotate(world - studio.p)
    fun dirToLocal(studio: Pose, dir: V3): V3 = studio.q.conj().rotate(dir)
    fun toWorld(studio: Pose, local: V3): V3 = studio.apply(local)
}

/**
 * Window opens only with a level head (native-window-move §7 item 9, D9): pure Kotlin, unit tested. The caller feeds
 * time and head pitch (degrees, + up; null without a valid head pose) every frame.
 */
class LevelGate(val maxPitchDeg: Float = 10f, val dwellMs: Long = 300L, val noHeadMs: Long = 5000L) {
    var pending: String? = null; private set
    private var askedMs = 0L; private var levelSinceMs = -1L; private var noHeadSinceMs = -1L
    val waiting get() = pending != null
    fun ask(why: String, now: Long) { if (pending == null) askedMs = now; pending = why; levelSinceMs = -1L; noHeadSinceMs = -1L }
    fun cancel() { pending = null; levelSinceMs = -1L; noHeadSinceMs = -1L }
    fun waitedMs(now: Long) = if (pending == null) 0L else now - askedMs

    /** The reason to open now, null = keep waiting. */
    fun step(now: Long, pitchDeg: Float?): String? {
        val why = pending ?: return null
        if (pitchDeg == null || !pitchDeg.isFinite()) {
            levelSinceMs = -1L
            if (noHeadSinceMs < 0L) noHeadSinceMs = now
            if (now - noHeadSinceMs < noHeadMs) return null
            cancel(); return "$why/nohead"
        }
        noHeadSinceMs = -1L
        if (abs(pitchDeg) > maxPitchDeg) { levelSinceMs = -1L; return null }
        if (levelSinceMs < 0L) levelSinceMs = now
        if (now - levelSinceMs < dwellMs) return null
        cancel(); return why
    }

    fun hintWanted(pitchDeg: Float?) = pending != null && pitchDeg != null && abs(pitchDeg) > maxPitchDeg
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
