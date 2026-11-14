package io.embodit.renderlab.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.sqrt

/** Full Space placement (D9 placeUpright, four sides) + the window level gate + the controller ray pick maths. */
class PlaceTest {
    private val head = V3(0.3f, 1.65f, -0.2f)
    private fun rot(yawDeg: Float, pitchDeg: Float, rollDeg: Float = 0f) =
        Q4.axis(V3.UP, yawDeg * Studio.D2R) * Q4.axis(V3(1f, 0f, 0f), pitchDeg * Studio.D2R) * Q4.axis(V3(0f, 0f, 1f), rollDeg * Studio.D2R)

    private fun below(eye: V3, w: V3) = atan((eye.y - w.y) / sqrt((w.x - eye.x) * (w.x - eye.x) + (w.z - eye.z) * (w.z - eye.z))) / Studio.D2R

    /** Head down 30° / up 30° / tilted 20°: the studio stays upright, same spot, AHEAD out, ROOT_DY from the eye. */
    @Test fun uprightUnderPitchAndRoll() {
        val level = Place.studio(head, rot(0f, 0f))
        for ((p, r) in listOf(-30f to 0f, 30f to 0f, 0f to 20f, -30f to 20f)) {
            val s = Place.studio(head, rot(0f, p, r))
            assertEquals(1f, s.q.rotate(V3.UP).dot(V3.UP), 1e-4f)
            assertEquals(0f, s.p.dist(level.p), 1e-3f)
        }
        val flat = V3(level.p.x - head.x, 0f, level.p.z - head.z)
        assertEquals(Place.AHEAD, flat.length, 1e-3f)
        assertEquals(head.y + Place.ROOT_DY, level.p.y, 1e-4f)
    }

    /** Four sides: whichever way you face, the studio's user side (+Z) points back at you (< 1°). */
    @Test fun fourSidesFaceUser() {
        for (yaw in listOf(0f, 90f, 180f, -90f, 37f)) {
            val s = Place.studio(head, rot(yaw, -15f))
            val toUser = V3(head.x - s.p.x, 0f, head.z - s.p.z).norm()
            assertTrue("yaw $yaw", Studio.angleDeg(s.q.rotate(V3(0f, 0f, 1f)), toUser) < 1f)
            val w = Place.toWorld(s, V3(0.1f, -0.2f, 0.05f))
            assertEquals(0f, Place.toLocal(s, w).dist(V3(0.1f, -0.2f, 0.05f)), 1e-4f)
        }
    }

    /** Front orbs within arm reach (direct pinch), the farthest still >= 2° for the ray (D17). */
    @Test fun reachAndSize() {
        val s = Place.studio(head, rot(0f, 0f))
        var near = Float.MAX_VALUE; var far = 0f
        for (az in -135..135 step 15) for (el in 10..80 step 10) {
            val w = Place.toWorld(s, Studio.orbPos(az.toFloat(), el.toFloat()))
            val d = w.dist(head)
            near = minOf(near, d); far = maxOf(far, d)
            assertTrue(2 * atan(Studio.HIT_R / d) / Studio.D2R >= 2f)
        }
        assertTrue("nearest orb $near m", near < 0.62f)
        println("orb distance from the eye: $near .. $far m")
    }

    /**
     * D54 (headset 2026-11-14, seated): heights follow the eye, not the floor. Seated eye 1.2 m and standing 1.6 m:
     * subject centre 10–15° below the line of sight; the window (opens on the line of sight once level, 572 dp at
     * ~0.057°/dp, system controls ~77 dp under it) keeps its bottom edge above -35°; nothing under the floor.
     */
    @Test fun seatedAndStandingHeights() {
        for (eyeY in listOf(1.2f, 1.6f)) {
            val eye = V3(0f, eyeY, 0f)
            for (pitch in listOf(0f, -25f, 15f)) {
                val s = Place.studio(eye, rot(0f, pitch))
                val subj = below(eye, Place.toWorld(s, Place.SUBJECT_C))
                assertTrue("eye $eyeY pitch $pitch: subject $subj° below", subj in 10f..15f)
                val floor = Place.toWorld(s, V3(0f, Studio.FY - 0.02f, 0f))
                assertTrue("studio above the floor", floor.y > 0.3f)
            }
            val windowBottom = -(WIN_H_DP / 2 + WIN_CTRL_DP) * DEG_PER_DP
            assertTrue("window bottom $windowBottom°", windowBottom >= -35f)
            println("eye $eyeY m: subject centre ${"%.1f".format(below(eye, Place.toWorld(Place.studio(eye, rot(0f, 0f)), Place.SUBJECT_C)))}° below, studio floor ${"%.2f".format(Place.toWorld(Place.studio(eye, rot(0f, 0f)), V3(0f, Studio.FY, 0f)).y)} m above the floor, window bottom ${"%.1f".format(windowBottom)}°")
        }
    }

    /**
     * D41 / D54 side by side: from the eye, every part of the studio (box, wall, orb name plates and ▲▼◀▶ over the
     * whole orb reach) is >= 10° from the window frame (incl. its system controls and TabBar) either sideways or below.
     */
    @Test fun windowBesideStudio() {
        val eye = V3(0f, 1.2f, 0f)
        val s = Place.studio(eye, rot(0f, 0f))
        val wdir = Place.windowDir(rot(0f, 0f))
        val halfW = WIN_W_DP / 2 * DEG_PER_DP
        val bottom = -(WIN_H_DP / 2 + WIN_CTRL_DP) * DEG_PER_DP
        val top = (WIN_H_DP / 2 + WIN_TAB_DP) * DEG_PER_DP
        val pts = ArrayList<V3>()
        for (i in 0..20) for (j in 0..20) {
            val x = -0.335f + 0.67f * i / 20f
            pts += V3(x, Studio.FY - 0.02f + (Studio.WALL_TOP - Studio.FY + 0.02f) * j / 20f, Studio.WZ - 0.01f)
            pts += V3(x, Studio.FY - 0.02f, Studio.WZ + (Studio.ZF - Studio.WZ) * j / 20f)
        }
        for (az in -135..135 step 5) for (el in 10..80 step 5) {
            val o = Studio.orbPos(az.toFloat(), el.toFloat())
            for (dx in listOf(-Studio.LABEL_W / 2, Studio.LABEL_W / 2)) pts += o + V3(dx, Studio.LABEL_DY + Studio.LABEL_H / 2, 0f)
            for (n in Nudge.entries) for (dx in listOf(-1f, 1f)) for (dy in listOf(-1f, 1f)) pts += Studio.nudgePos(o, n) + V3(dx * Studio.NUDGE_S / 2, dy * Studio.NUDGE_S / 2, 0f)
        }
        var worst = 999f
        for (p in pts) {
            val w = Place.toWorld(s, p)
            val flat = V3(w.x - eye.x, 0f, w.z - eye.z).norm()
            val side = -Place.yawBetween(wdir, flat) // + = to the right of the window centre
            val el = -below(eye, w)
            val sideGap = side - halfW
            val belowGap = bottom - el
            val aboveGap = el - top
            val gap = maxOf(sideGap, belowGap, aboveGap)
            worst = minOf(worst, gap)
        }
        println("studio vs window frame: closest ${"%.1f".format(worst)}° (studio ${Place.STUDIO_SIDE_DEG}° right, window ${Place.WINDOW_SIDE_DEG}° left)")
        assertTrue("studio $worst° from the window", worst >= 10f)
    }

    /** The "look here" plate sits where the window opens: on the window's direction, eye height, upright. */
    @Test fun lookHerePlate() {
        val wdir = Place.windowDir(rot(20f, -30f))
        val lp = Place.levelPlate(head, wdir)
        assertEquals(head.y, lp.p.y, 1e-4f)
        assertEquals(1f, lp.q.rotate(V3.UP).dot(V3.UP), 1e-4f)
        val flat = V3(lp.p.x - head.x, 0f, lp.p.z - head.z).norm()
        assertTrue(Studio.angleDeg(flat, wdir) < 0.5f)
        assertTrue(Studio.angleDeg(lp.q.rotate(V3(0f, 0f, 1f)), flat * -1f) < 0.5f)
        // the window spot is WINDOW_SIDE_DEG left of where you looked, the studio STUDIO_SIDE_DEG right
        assertEquals(Place.WINDOW_SIDE_DEG, Place.yawBetween(Place.flatForward(rot(20f, -30f))!!, wdir), 0.1f)
        assertEquals(Place.STUDIO_SIDE_DEG + Place.WINDOW_SIDE_DEG, Place.yawBetween(V3(Place.studio(head, rot(20f, 0f)).p.x - head.x, 0f, Place.studio(head, rot(20f, 0f)).p.z - head.z).norm(), Place.windowDirFor(head, Place.studio(head, rot(20f, 0f)))), 0.1f)
    }

    companion object {
        /** Window drawn size in the Mixed Stage (kinetica / region-snap / traffic-signals measurements, pico-cli-feedback). */
        const val DEG_PER_DP = 0.057f
        const val WIN_W_DP = 600f
        const val WIN_H_DP = 572f
        const val WIN_CTRL_DP = 77f
        const val WIN_TAB_DP = 88f
    }

    @Test fun invalidPose() {
        assertTrue(!Place.valid(V3.ZERO, Q4.ID))
        assertTrue(Place.valid(head, Q4.ID))
    }

    @Test fun levelGate() {
        val g = LevelGate()
        g.ask("start", 0)
        assertNull(g.step(100, -30f)); assertTrue(g.hintWanted(-30f))
        assertNull(g.step(200, 2f)); assertNull(g.step(400, 2f))
        assertEquals("start", g.step(520, 1f))
        g.ask("recall", 1000)
        assertNull(g.step(1000, null)); assertNotNull(g.step(6100, null))
        // v0.1.3: level but looking at the studio (31°+ away from the window spot) keeps waiting; turning there opens
        g.ask("start", 10_000)
        assertNull(g.step(10_100, 0f, 40f)); assertNull(g.step(10_500, 0f, 40f)); assertTrue(g.hintWanted(0f, 40f))
        assertNull(g.step(10_600, 0f, 4f)); assertEquals("start", g.step(10_950, 0f, -3f))
    }

    @Test fun raySphereHit() {
        val t = raySphere(V3(0f, 0f, 1f), V3(0f, 0f, -1f), V3(0f, 0f, 0f), 0.1f)
        assertEquals(0.9f, t!!, 1e-4f)
        assertNull(raySphere(V3(0f, 1f, 1f), V3(0f, 0f, -1f), V3.ZERO, 0.1f))
    }
}
