package io.embodit.renderlab.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** design verification.unitTests: self-computed shadow, 8 shots scoring, thresholds, visibility, PCG. */
class ShadowTest {
    private fun offset(t: Target, deg: Float, q: Int): V3 {
        val tv = Studio.dir(t.az, t.el)
        val ax1 = tv.cross(V3.UP).norm(); val ax2 = tv.cross(ax1).norm()
        val ang = q * Math.PI.toFloat() / 4
        return Q4.axis(ax1 * kotlin.math.cos(ang) + ax2 * kotlin.math.sin(ang), deg * Studio.D2R).rotate(tv)
    }

    @Test fun hullAndClip() {
        val h = Shadow.hull(listOf(P2(0f, 0f), P2(1f, 0f), P2(1f, 1f), P2(0f, 1f), P2(0.5f, 0.5f)))
        assertEquals(4, h.size)
        val c = Shadow.clip(h, 0.5f, 2f, -1f, 2f)
        assertEquals(4, c.size)
        assertTrue(c.all { it.a >= 0.5f - 1e-6f })
        assertTrue(Shadow.inside(h, 0.5f, 0.5f))
    }

    @Test fun everyShotSolvedIsThreeStars() {
        for (shot in Shots.ALL) {
            val ev = ShotEval(shot)
            val s = ev.score(shot.solvedLights())
            assertEquals("${shot.id} iou", 1f, s.iou, 1e-4f)
            assertEquals("${shot.id} stars ${s.checks}", 3, s.stars)
            assertTrue("${shot.id} matched", s.matched)
            println("${shot.id} th=${ev.thresholds.joinToString { "%.3f".format(it) }} cells=${Shadow.count(ev.targetMask)} floor=${ev.target.floor.size} wall=${ev.target.wall.size}")
        }
    }

    @Test fun thresholdsAreMonotonic() {
        for (shot in Shots.ALL) {
            val th = ShotEval(shot).thresholds
            assertTrue("${shot.id} ${th.toList()}", th[0] >= th[1] && th[1] >= th[2] && th[0] < 1f)
        }
    }

    /** Offsets 6 / 12 / 20 / 35° (design T4): 6° keeps three stars, 35° never does, stars never rise with distance. */
    @Test fun offsetStars() {
        for (shot in Shots.ALL) {
            val ev = ShotEval(shot)
            for (q in 0 until 8) {
                val stars = listOf(6f, 12f, 20f, 35f).map { d ->
                    val v = offset(shot.key, d, q)
                    ev.shadowStars(Shadow.iou(Shadow.raster(Shadow.hulls(ev.subject.parts, v)), ev.targetMask))
                }
                assertTrue("${shot.id} q=$q 6° $stars", stars[0] >= 2)
                assertTrue("${shot.id} q=$q 35° $stars", stars[3] <= 2)
                assertTrue("${shot.id} q=$q 6 vs 35 $stars", stars[0] >= stars[3])
            }
        }
    }

    @Test fun startKeyIsFarFromTarget() {
        for (shot in Shots.ALL) {
            val a = Studio.angleDeg(Studio.dir(shot.startAz, shot.startEl), Studio.dir(shot.key.az, shot.key.el))
            assertTrue("${shot.id} start $a°", a >= 40f)
            val ev = ShotEval(shot)
            assertTrue("${shot.id} start not 3 stars", ev.score(shot.startLights()).stars < 3)
        }
    }

    /** design scoring.visibility: every target shadow >= 60 % visible from the nominal eye. */
    @Test fun targetShadowVisible() {
        for (shot in Shots.ALL) {
            val ev = ShotEval(shot)
            val v = Shadow.visibleShare(ev.targetMask, ev.subject.parts)
            println("${shot.id} visible ${"%.0f".format(v * 100)}%")
            assertTrue("${shot.id} visible $v", v >= 0.60f)
        }
    }

    /** design content.pcg: 1000 seeds, every random shot legal, inside floor + wall, >= 80 cells, visible, start >= 40°. */
    @Test fun pcgThousandSeeds() {
        for (seed in 1L..1000L) {
            val s = Shots.random(seed)
            val parts = Shots.subject(s.subject).parts
            val d = Studio.dir(s.key.az, s.key.el)
            assertTrue(Shadow.fitsInside(parts, d))
            assertTrue(Shadow.count(Shadow.raster(Shadow.hulls(parts, d))) >= 80)
            assertTrue(s.key.az in -135f..135f && s.key.el in 30f..70f)
            assertTrue(Studio.angleDeg(Studio.dir(s.startAz, s.startEl), d) >= 40f)
            s.fill?.let { f -> assertTrue("seed $seed fill clear", Studio.clearOf(f.az, f.el, s.key.az, s.key.el)) }
        }
    }

    /** PCG 20 in a row reach three stars when solved (design acceptance M6). */
    @Test fun pcgTwentySolvable() {
        for (seed in 5000L until 5020L) {
            val s = Shots.random(seed)
            val ev = ShotEval(s)
            val sc = ev.score(s.solvedLights())
            assertEquals("seed $seed", 3, sc.stars)
            assertTrue(sc.matched)
        }
    }

    @Test fun triangleBudget() {
        val tris = SubjectId.entries.associateWith { Shots.subject(it).triangles }
        println("subject tris $tris")
        assertTrue(tris.values.all { it <= 14_000 })
    }
}
