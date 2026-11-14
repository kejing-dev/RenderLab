package io.embodit.renderlab.ui.studio

import io.embodit.renderlab.domain.LabFlow
import io.embodit.renderlab.domain.LightId
import io.embodit.renderlab.domain.Nudge
import io.embodit.renderlab.domain.Place
import io.embodit.renderlab.domain.Q4
import io.embodit.renderlab.domain.SaveStore
import io.embodit.renderlab.domain.Saved
import io.embodit.renderlab.domain.Studio
import io.embodit.renderlab.domain.V3
import io.embodit.renderlab.platform.ControllerSample
import io.embodit.renderlab.ui.studio.input.DragTotal
import io.embodit.renderlab.ui.studio.input.InputSource
import io.embodit.renderlab.ui.studio.input.Offset3M
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.3 root cause (headset input-log 2026-11-14: drags taken=true but `travel` 2–5 cm over 2–4 s and the light did
 * not follow): SDK 6.1.9 sends `dragAmount` as one event's delta, the app read it as the travel since the press.
 */
class DragFixTest {
    private class Mem : SaveStore { var s: Saved? = null; override fun load(): Saved? = s; override fun save(s: Saved) { this.s = s }; override fun clear() { s = null } }
    private var now = 0L
    private fun flow() = LabFlow(CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, { now }, Mem()).also { it.start(); it.beginFromWelcome() }
    private fun listener(f: LabFlow) = StudioListener(f, null, repeat = NudgeRepeat(f) { now })
    private fun moved(a: io.embodit.renderlab.domain.LightSet, b: io.embodit.renderlab.domain.LightSet) = Studio.angleDeg(a.pos - Studio.C, b.pos - Studio.C)

    /** The headset's pattern: 60 events of 2 mm to the left (12 cm of hand travel), direct pinch. */
    @Test fun summedDeltasMoveTheLight() {
        val f = flow(); val l = listener(f)
        val before = f.state.value.lights.key
        val total = DragTotal()
        assertTrue(l.onDragStart("orb:key", InputSource.DIRECT))
        repeat(60) { l.onDragMove(total.add(Offset3M(-0.002f, 0f, 0f))) }
        l.onDragEnd()
        val after = f.state.value.lights.key
        assertEquals(-0.12f, total.x, 1e-4f)
        assertTrue("moved ${moved(before, after)}°", moved(before, after) > 20f)
        assertTrue("went left", after.az < before.az)
        assertTrue(f.state.value.guideDone)
    }

    /** What v0.1.2 did with the same events (each delta taken as the total): the light stays put (< 1°). */
    @Test fun oldReadingPinnedTheLight() {
        val f = flow(); val l = listener(f)
        val before = f.state.value.lights.key
        assertTrue(l.onDragStart("orb:key", InputSource.DIRECT))
        repeat(60) { l.onDragMove(Offset3M(-0.002f, 0f, 0f)) }
        l.onDragEnd()
        assertTrue(moved(before, f.state.value.lights.key) < 1f)
        assertFalse(f.state.value.guideDone)
    }

    /** Every frame of a drag the light is where the summed hand travel puts it (follows the hand, no lag, no snap back). */
    @Test fun followsEveryFrame() {
        val f = flow(); val l = listener(f)
        val total = DragTotal()
        l.onDragStart("orb:key", InputSource.EYE_HAND)
        val x0 = f.state.value.lights.key.pos.x
        var last = x0
        repeat(20) {
            l.onDragMove(total.add(Offset3M(0.003f, 0.001f, 0f)))
            val x = f.state.value.lights.key.pos.x
            assertTrue("frame $it: $x vs $last", x >= last - 1e-4f)
            last = x
        }
        assertTrue(last - x0 > 0.05f)
        val az = f.state.value.lights.key.az
        l.onDragEnd()
        // release: it stays where it was dropped (D1)
        assertEquals(az, f.state.value.lights.key.az, 1e-4f)
    }

    /** Ray / gaze drags get the distance gain, direct pinch is 1:1. */
    @Test fun gainBySource() {
        assertEquals(1f, StudioListener.dragGain(InputSource.DIRECT))
        assertEquals(1f, StudioListener.dragGain(InputSource.POKE))
        assertEquals(2f, StudioListener.dragGain(InputSource.HAND_RAY))
        assertEquals(2f, StudioListener.dragGain(InputSource.EYE_HAND))
    }

    /** D53: ▲▼◀▶ step the selected light the way you see it; a held button repeats; the tap of the same press is not a second step. */
    @Test fun nudgeButtons() {
        val f = flow()
        val rep = NudgeRepeat(f) { now }
        val l = StudioListener(f, null, repeat = rep)
        val k0 = f.state.value.lights.key
        l.onTap("nudge:up", InputSource.HAND_RAY, null)
        assertEquals(k0.el + LabFlow.NUDGE_DEG, f.state.value.lights.key.el, 1e-3f)
        // front light (az 40): ▶ = +az = to your right
        val a0 = f.state.value.lights.key.az
        now += 1000; l.onTap("nudge:right", InputSource.EYE_HAND, null)
        assertTrue(f.state.value.lights.key.az > a0)
        assertTrue(Studio.orbPos(f.state.value.lights.key.az, 0f).x > Studio.orbPos(a0, 0f).x)
        // held: one step on the press, then repeats while held, none after release
        now += 1000
        val e0 = f.state.value.lights.key.el
        l.onPress("nudge:down", InputSource.DIRECT)
        for (t in 0 until 10) { now += 100; rep.tick() }
        l.onRelease()
        val held = f.state.value.lights.key.el
        assertTrue("held steps ${(e0 - held) / LabFlow.NUDGE_DEG}", e0 - held >= 4 * LabFlow.NUDGE_DEG - 1e-3f)
        l.onTap("nudge:down", InputSource.DIRECT, null) // the system's tap for that press
        assertEquals(held, f.state.value.lights.key.el, 1e-4f)
        now += 1000; rep.tick()
        assertEquals(held, f.state.value.lights.key.el, 1e-4f)
        // behind the subject (rim at az 120) ▶ still moves it to your right on screen
        l.onTap("orb:rim", InputSource.HAND_RAY, null)
        val r0 = f.state.value.lights.rim
        now += 1000; l.onTap("nudge:right", InputSource.HAND_RAY, null)
        assertTrue(Studio.orbPos(f.state.value.lights.rim.az, r0.el).x > Studio.orbPos(r0.az, r0.el).x)
        assertTrue(f.state.value.guideDone)
    }

    /** Step buttons: >= 4 cm, >= 2° from the eye, >= 1.5 cm clear of the orb's own hit sphere, above the floor (D17). */
    @Test fun nudgeSizes() {
        val eye = V3(0f, -Place.ROOT_DY, Place.AHEAD)
        assertTrue(Studio.NUDGE_S >= 0.04f)
        for (az in -135..135 step 15) for (el in 10..80 step 10) {
            val o = Studio.orbPos(az.toFloat(), el.toFloat())
            for (n in Nudge.entries) {
                val c = Studio.nudgePos(o, n)
                assertTrue(2 * kotlin.math.atan(Studio.NUDGE_S / 2 / c.dist(eye)) / Studio.D2R >= 2f)
                val gap = when (n) { Nudge.LEFT, Nudge.RIGHT -> kotlin.math.abs(c.x - o.x); else -> kotlin.math.abs(c.y - o.y) } - Studio.NUDGE_S / 2 - Studio.HIT_R
                assertTrue("$n gap $gap at $az/$el", gap >= 0.015f)
                assertTrue(c.y - Studio.NUDGE_S / 2 > Studio.FY)
            }
        }
        assertEquals(StudioNames.NudgeHit(Nudge.LEFT), StudioNames.hitOf("nudge:left"))
        assertEquals(StudioNames.NoHit, StudioNames.hitOf("nudge:sideways"))
        assertEquals("nudge:up", StudioTargets.resolvePath(listOf("nudgeFace:up", "nudge:up", "root")))
    }

    /** Controller: trigger on a light grabs it, the light follows the ray every frame, release drops it (studio space). */
    @Test fun controllerRayDrag() {
        val f = flow()
        val rep = NudgeRepeat(f) { now }
        val c = ControllerDrag(f, rep)
        val pose = io.embodit.renderlab.domain.Pose(V3.ZERO, Q4.ID) // studio = Stage for this test
        val k = f.state.value.lights.key.pos
        val o = V3(0.05f, 0.2f, 0.8f)
        fun aim(p: V3) = (p - o).norm()
        // press on the orb
        runCatching { c.step(ControllerSample(o, aim(k), 1f, true, true), pose, true) {} }
        // (SystemClock is not available on the JVM: owns() is not called here; dragTo is)
        assertEquals(LightId.KEY, f.state.value.dragging)
        val target = Studio.orbPos(-20f, 50f)
        for (i in 1..10) {
            val p = k + (target - k) * (i / 10f)
            runCatching { c.step(ControllerSample(o, aim(p), 1f, true, true), pose, true) {} }
        }
        val held = f.state.value.lights.key
        assertTrue("az ${held.az}", held.az < 0f)
        runCatching { c.step(ControllerSample(o, aim(target), 0f, false, true), pose, true) {} }
        assertEquals(null, f.state.value.dragging)
        assertEquals(held.az, f.state.value.lights.key.az, 1e-3f)
    }
}

class ReachTest {
    /** v0.1.3 self-test case: key at 22°/31°, rim off at 120°/50° behind: ▶ and ▲ must move the key. */
    @org.junit.Test fun nudgeNotStuckBehindRim() {
        val K = io.embodit.renderlab.domain.LightId.KEY
        val l = io.embodit.renderlab.domain.Lights.DEFAULT.let { it.with(K, it.key.copy(az = 22f, el = 31f)) }
        for (dir in io.embodit.renderlab.domain.Nudge.entries) {
            val ok = (1..10).any { k -> val (a, e) = io.embodit.renderlab.domain.LabFlow.nudgeTarget(22f, 31f, dir); l.canPlace(K, Studio.clampAz(22f + (a - 22f) * k), Studio.clampEl(31f + (e - 31f) * k)) }
            org.junit.Assert.assertTrue("$dir stuck", ok)
        }
    }

    /** Every shot's target lights can be reached by dragging (pairwise clear under the v0.1.3 eye rule). */
    @org.junit.Test fun targetsStayReachable() {
        for (shot in io.embodit.renderlab.domain.Shots.ALL) {
            val t = shot.solvedLights()
            for (a in io.embodit.renderlab.domain.LightId.entries) for (b in io.embodit.renderlab.domain.LightId.entries) if (a < b && t[a].on && t[b].on)
                org.junit.Assert.assertTrue("${shot.id} $a/$b", Studio.clearOf(t[a].az, t[a].el, t[b].az, t[b].el))
        }
    }
}
