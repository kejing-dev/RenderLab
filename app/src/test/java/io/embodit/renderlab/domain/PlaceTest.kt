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

    /** Head down 30° / up 30° / tilted 20°: the studio stays upright, same spot, 0.70 m ahead, 0.60 m below the eye. */
    @Test fun uprightUnderPitchAndRoll() {
        val level = Place.studio(head, rot(0f, 0f))
        for ((p, r) in listOf(-30f to 0f, 30f to 0f, 0f to 20f, -30f to 20f)) {
            val s = Place.studio(head, rot(0f, p, r))
            assertEquals(1f, s.q.rotate(V3.UP).dot(V3.UP), 1e-4f)
            assertEquals(0f, s.p.dist(level.p), 1e-3f)
        }
        val flat = V3(level.p.x - head.x, 0f, level.p.z - head.z)
        assertEquals(Place.AHEAD, flat.length, 1e-3f)
        assertEquals(head.y - Place.BELOW_EYE, level.p.y, 1e-4f)
    }

    /** Four sides: whichever way you face, the studio's user side (+Z) points back at you (< 1°). */
    @Test fun fourSidesFaceUser() {
        for (yaw in listOf(0f, 90f, 180f, -90f, 37f)) {
            val s = Place.studio(head, rot(yaw, -15f))
            val toUser = V3(head.x - s.p.x, 0f, head.z - s.p.z).norm()
            assertTrue("yaw $yaw", Studio.angleDeg(s.q.rotate(V3(0f, 0f, 1f)), toUser) < 1f)
            // local / world round trip
            val w = Place.toWorld(s, V3(0.1f, -0.2f, 0.05f))
            assertEquals(0f, Place.toLocal(s, w).dist(V3(0.1f, -0.2f, 0.05f)), 1e-4f)
        }
    }

    /** Front orbs within arm reach (direct pinch), the farthest still >= 2° for the ray (D17), all below the eye. */
    @Test fun reachAndSize() {
        val s = Place.studio(head, rot(0f, 0f))
        var near = Float.MAX_VALUE; var far = 0f
        for (az in -135..135 step 15) for (el in 10..80 step 10) {
            val w = Place.toWorld(s, Studio.orbPos(az.toFloat(), el.toFloat()))
            val d = w.dist(head)
            near = minOf(near, d); far = maxOf(far, d)
            assertTrue(2 * atan(Studio.HIT_R / d) / Studio.D2R >= 2f)
            assertTrue(w.y < head.y)
        }
        assertTrue("nearest orb $near m", near < 0.80f)
        println("orb distance from the eye: $near .. $far m")
    }

    /** The studio's top (wall top) sits below the window, whose centre opens at eye level straight ahead. */
    @Test fun studioBelowWindow() {
        val s = Place.studio(head, rot(0f, 0f))
        val top = Place.toWorld(s, V3(0f, Studio.WALL_TOP, Studio.WZ))
        val dist = sqrt((top.x - head.x) * (top.x - head.x) + (top.z - head.z) * (top.z - head.z))
        val deg = atan((head.y - top.y) / dist) / Studio.D2R
        println("wall top ${"%.1f".format(deg)}° below the eye")
        assertTrue(deg > 15f)
    }

    /**
     * QA #1: the "look straight ahead" plate must not cover the studio while you look down at it. Head down 20-60°
     * (also turned ±30° away from the studio): the plate's bottom edge stays >= 1° above the wall-top strip and above
     * the highest orb name plate; looking level it is on the line of sight.
     */
    @Test fun levelPlateClearsStudio() {
        val s = Place.studio(head, rot(0f, 0f))
        fun below(w: V3) = atan((head.y - w.y) / sqrt((w.x - head.x) * (w.x - head.x) + (w.z - head.z) * (w.z - head.z))) / Studio.D2R
        val stripTop = below(Place.toWorld(s, Studio.STRIP_C + V3(0f, Studio.STRIP_H / 2, 0f)))
        var plateTop = 90f
        for (az in -135..135 step 5) for (el in 10..80 step 5) {
            val o = Studio.orbPos(az.toFloat(), el.toFloat())
            plateTop = minOf(plateTop, below(Place.toWorld(s, o + V3(0f, Studio.LABEL_DY + Studio.LABEL_H / 2, 0f))))
        }
        for (pitch in listOf(-20f, -35f, -45f, -60f)) {
            val lp = Place.levelPlate(head, rot(0f, pitch))
            val bottom = below(lp.p - V3(0f, Studio.STRIP_H / 2, 0f))
            assertTrue("pitch $pitch: plate bottom $bottom° vs strip top $stripTop°", bottom + 1f <= stripTop)
            assertTrue("pitch $pitch: plate bottom $bottom° vs orb plates $plateTop°", bottom + 1f <= plateTop)
            assertEquals(1f, lp.q.rotate(V3.UP).dot(V3.UP), 1e-4f)
        }
        val level = Place.levelPlate(head, rot(0f, 0f))
        assertEquals(head.y, level.p.y, 1e-4f)
        println("level plate bottom at -45°: ${"%.1f".format(below(Place.levelPlate(head, rot(0f, -45f)).p - V3(0f, Studio.STRIP_H / 2, 0f)))}° below the eye; strip top $stripTop°, highest orb plate $plateTop°")
    }

    /** QA #1 asked whether the subject can come up: print the eye-down angles used in ui-audit §0 (no assertion). */
    @Test fun subjectAngleReport() {
        val s = Place.studio(head, rot(0f, 0f))
        fun below(w: V3) = atan((head.y - w.y) / sqrt((w.x - head.x) * (w.x - head.x) + (w.z - head.z) * (w.z - head.z))) / Studio.D2R
        val subj = below(Place.toWorld(s, V3(0f, Studio.FY, -0.10f)))
        val subjMid = below(Place.toWorld(s, V3(0f, Studio.FY + 0.08f, -0.10f)))
        val wallTop = below(Place.toWorld(s, V3(0f, Studio.WALL_TOP, Studio.WZ)))
        println("subject base ${"%.1f".format(subj)}°, subject middle ${"%.1f".format(subjMid)}°, wall top ${"%.1f".format(wallTop)}° below the eye")
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
    }

    @Test fun raySphereHit() {
        val t = raySphere(V3(0f, 0f, 1f), V3(0f, 0f, -1f), V3(0f, 0f, 0f), 0.1f)
        assertEquals(0.9f, t!!, 1e-4f)
        assertNull(raySphere(V3(0f, 1f, 1f), V3(0f, 0f, -1f), V3.ZERO, 0.1f))
    }
}
