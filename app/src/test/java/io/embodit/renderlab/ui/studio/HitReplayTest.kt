package io.embodit.renderlab.ui.studio

import io.embodit.renderlab.domain.LabFlow
import io.embodit.renderlab.domain.LightId
import io.embodit.renderlab.domain.MaterialId
import io.embodit.renderlab.domain.Mode
import io.embodit.renderlab.domain.SaveStore
import io.embodit.renderlab.domain.Saved
import io.embodit.renderlab.ui.studio.input.InputSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/** build.md 命中回放: real hit paths (mesh child first, then parents) resolve to a holder and reach the flow. */
class HitReplayTest {
    private class Mem : SaveStore { override fun load(): Saved? = null; override fun save(s: Saved) {}; override fun clear() {} }

    @Test fun pathsResolve() {
        assertEquals("orb:key", StudioTargets.resolvePath(listOf("orbBall:key", "orb:key", "root")))
        assertEquals("orb:fill", StudioTargets.resolvePath(listOf("orbHalo:fill", "orb:fill", "root")))
        assertEquals("orb:rim", StudioTargets.resolvePath(listOf("orbLabel:rim", "orb:rim", "root")))
        assertEquals("sw:4", StudioTargets.resolvePath(listOf("swatchBall:4", "sw:4", "shelfRoot", "root")))
        assertEquals("wall", StudioTargets.resolvePath(listOf("wall", "root")))
        assertEquals(null, StudioTargets.resolvePath(listOf("subjectPart:0", "subject:plaster", "root")))
        assertEquals(null, StudioTargets.resolvePath(listOf("floorTop", "root")))
        assertEquals(null, StudioTargets.resolvePath(listOf("orb:sun", "root")))
    }

    @Test fun replayReachesFlow() {
        val flow = LabFlow(CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, { 0L }, Mem())
        flow.start(); flow.startCard(Mode.FREE)
        val l = StudioListener(flow, null)
        for (src in listOf(InputSource.CONTROLLER, InputSource.HAND_RAY, InputSource.EYE_HAND)) {
            l.onTap(StudioTargets.resolvePath(listOf("orbBall:rim", "orb:rim")), src, null)
            assertEquals(LightId.RIM, flow.state.value.selected)
            l.onTap(StudioTargets.resolvePath(listOf("swatchBall:2", "sw:2")), src, null)
            assertEquals(MaterialId.GOLD, flow.state.value.material)
            val b = flow.state.value.backdrop
            l.onTap(StudioTargets.resolvePath(listOf("wall")), src, null)
            assertEquals(b.next(), flow.state.value.backdrop)
            l.onTap(StudioTargets.resolvePath(listOf("orbBall:key", "orb:key")), src, null)
            assertEquals(LightId.KEY, flow.state.value.selected)
        }
        // a drag on an orb moves it through the same flow call
        val before = flow.state.value.lights.key
        l.onDragStart("orb:key", InputSource.CONTROLLER)
        l.onDragMove(io.embodit.renderlab.ui.studio.input.Offset3M(-0.1f, 0f, 0f))
        l.onDragEnd()
        assert(flow.state.value.lights.key != before)
    }
}
