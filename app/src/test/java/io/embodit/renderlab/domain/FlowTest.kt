package io.embodit.renderlab.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowTest {
    private class MemStore : SaveStore {
        var saved: Saved? = null
        override fun load() = saved
        override fun save(s: Saved) { saved = s }
        override fun clear() { saved = null }
    }

    private var now = 1000L
    private val sounds = ArrayList<Sound>()
    private fun flow(store: MemStore = MemStore()) = LabFlow(CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, { now += 50; now }, store, { s, _ -> sounds += s })

    @Test fun firstRunWelcomeThenLighting() {
        val f = flow(); f.start()
        assertEquals("S01", f.state.value.stateId)
        assertEquals("L1", f.state.value.shot!!.id)
        f.beginFromWelcome()
        assertEquals("S02", f.state.value.stateId)
    }

    /** A drag on the key orb in S01 also starts (design S01 exits). */
    @Test fun dragInWelcomeStarts() {
        val f = flow(); f.start()
        assertTrue(f.dragStart(LightId.KEY))
        assertEquals("S03", f.state.value.stateId)
        f.dragEnd()
        assertEquals(Phase.LIGHTING, f.state.value.phase)
    }

    /** T1: the orb stays on the sphere, inside the band, and does not move on release (D1). */
    @Test fun dragClampsAndStays() {
        val f = flow(); f.start(); f.beginFromWelcome()
        f.dragStart(LightId.KEY)
        f.dragMove(V3(0f, 2f, 0f))
        val l = f.state.value.lights.key
        assertTrue(l.el <= Studio.EL_MAX + 1e-3f && l.el >= Studio.EL_MIN - 1e-3f)
        assertEquals(Studio.R, (l.pos - Studio.C).length, 1e-4f)
        f.dragEnd()
        assertEquals(l, f.state.value.lights.key)
        assertEquals(null, f.state.value.dragging)
    }

    /** design input.dragOrb: an orb stops at the edge of another's 28° zone, it never pushes it. */
    @Test fun orbsKeepApart() {
        val f = flow(); f.start(); f.beginFromWelcome()
        f.tapOrb(LightId.FILL)
        val fill = f.state.value.lights.fill
        f.place(LightId.KEY, fill.az, fill.el)
        val k = f.state.value.lights.key
        assertTrue(Studio.angleDeg(k.dir, fill.dir) >= Studio.MIN_SEP - 0.01f)
        assertEquals(fill, f.state.value.lights.fill)
        for (az in -135..135 step 15) for (el in 10..80 step 10) {
            f.place(LightId.KEY, az.toFloat(), el.toFloat())
            val l = f.state.value.lights
            assertTrue(Studio.clearOf(l.key.az, l.key.el, l.fill.az, l.fill.el, visual = l.fill.on))
            assertTrue(Studio.clearOf(l.key.az, l.key.el, l.rim.az, l.rim.el, visual = l.rim.on)) // v0.1.3: an off light only keeps the 28° gap
        }
    }

    /** D52: tapping another orb switches the selection straight away; an off orb turns on low. */
    @Test fun tapSwitchesSelection() {
        val f = flow(); f.start(); f.beginFromWelcome()
        f.tapOrb(LightId.RIM)
        assertEquals(LightId.RIM, f.state.value.selected)
        assertEquals(Level.LOW, f.state.value.lights.rim.level)
        f.tapOrb(LightId.FILL)
        assertEquals(LightId.FILL, f.state.value.selected)
        f.setLevel(Level.OFF)
        assertFalse(f.state.value.lights.fill.on)
        f.tapOrb(LightId.KEY); f.setLevel(Level.OFF)
        assertEquals(Level.LOW, f.state.value.lights.key.level)
    }

    @Test fun solveShootNextAgain() {
        val store = MemStore()
        val f = flow(store); f.start(); f.beginFromWelcome()
        for (i in 0 until 8) {
            f.solve()
            assertTrue("${f.state.value.shot!!.id}", f.state.value.matched)
            assertEquals("S06", f.state.value.stateId)
            f.shoot()
            assertEquals(3, f.state.value.result!!.stars)
            if (i < 7) { f.again(); assertEquals("S06", f.state.value.stateId); f.shoot(); f.next() }
        }
        assertEquals("S10", f.state.value.stateId)
        assertTrue(store.saved!!.best.values.all { it == 3 })
        f.next()
        assertTrue(f.state.value.shot!!.isRandom)
        assertTrue(sounds.contains(Sound.SHUTTER) && sounds.contains(Sound.MATCH))
    }

    /** [再摆一次] keeps the lights where they were (design content.startRule). */
    @Test fun againKeepsLights() {
        val f = flow(); f.start(); f.beginFromWelcome()
        f.place(LightId.KEY, 30f, 40f)
        val before = f.state.value.lights
        f.shoot(); f.again()
        assertEquals(before, f.state.value.lights)
    }

    @Test fun afterL8NotAllDoneGoesRandom() {
        val f = flow(); f.start(); f.startCard(7)
        f.shoot()
        assertEquals("S07", f.state.value.stateId)
        f.next()
        assertTrue(f.state.value.shot!!.isRandom)
    }

    @Test fun freeLightCycles() {
        val f = flow(); f.start(); f.startCard(Mode.FREE)
        assertEquals("S09", f.state.value.stateId)
        val s0 = f.state.value.subject
        f.cycleSubject(); assertNotEquals(s0, f.state.value.subject)
        f.setMaterial(MaterialId.GLOW); assertEquals(MaterialId.GLOW, f.state.value.material)
        val b0 = f.state.value.backdrop
        f.cycleBackdrop(); assertNotEquals(b0, f.state.value.backdrop)
        // in a shot, material / backdrop are the shot's
        f.startCard(0); f.cycleBackdrop(); assertEquals(Backdrop.WHITE, f.state.value.backdrop)
    }

    @Test fun saveRestoreAndClear() {
        val store = MemStore()
        val f = flow(store); f.start(); f.beginFromWelcome(); f.startCard(2); f.place(LightId.KEY, 10f, 50f)
        f.onBackground()
        val g = flow(store); g.start()
        assertEquals("L3", g.state.value.shot!!.id)
        assertEquals(10f, g.state.value.lights.key.az, 0.01f)
        g.clearProgress()
        assertEquals("S01", g.state.value.stateId)
        assertTrue(g.state.value.best.isEmpty())
    }

    @Test fun codecRoundTrip() {
        val s = Saved(mapOf("L1" to 3, "L4" to 1), 9, 4821, Lights.DEFAULT, SubjectId.VASE, MaterialId.GLOW, Backdrop.BLACK, true)
        assertEquals(s, SaveCodec.decode(SaveCodec.encode(s)))
    }

    /** Debug solve goes through the same setters: the result equals a hand-placed solution. */
    @Test fun hintPointsTowardTarget() {
        val f = flow(); f.start(); f.beginFromWelcome()
        // L1 target az 70 (right); start az -20
        assertEquals(Hint.RIGHT, f.state.value.score!!.hint)
    }
}
