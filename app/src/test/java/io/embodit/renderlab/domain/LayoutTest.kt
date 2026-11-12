package io.embodit.renderlab.domain

import org.junit.Assert.assertTrue
import org.junit.Test

/** design verification: every box inside the volume over the whole orb reach; D35 name plates; D17 hit sizes. */
class LayoutTest {
    private fun inside(p: V3, m: Float = 0f) = kotlin.math.abs(p.x) + m <= Studio.HX && kotlin.math.abs(p.y) + m <= Studio.HY && kotlin.math.abs(p.z) + m <= Studio.HZ

    private val reach = sequence { for (az in -135..135 step 5) for (el in 10..80 step 5) yield(az.toFloat() to el.toFloat()) }.toList()

    @Test fun orbsAndPlatesStayInVolume() {
        for ((az, el) in reach) {
            val p = Studio.orbPos(az, el)
            assertTrue("orb $az/$el", inside(p, Studio.HIT_R))
            assertTrue("orb clears wall $az/$el", p.z - Studio.HIT_R > Studio.WZ)
            assertTrue("orb above floor $az/$el", p.y - Studio.HIT_R > Studio.FY)
            val top = p + V3(0f, Studio.LABEL_DY + Studio.LABEL_H / 2, 0f)
            for (dx in listOf(-Studio.LABEL_W / 2, Studio.LABEL_W / 2)) assertTrue("plate $az/$el", inside(top + V3(dx, 0f, 0f)))
        }
    }

    @Test fun subjectsAndProps() {
        for (id in SubjectId.entries) for (m in Shots.subject(id).meshes) {
            val (lo, hi) = m.bounds()
            assertTrue("$id", inside(lo) && inside(hi))
            assertTrue("$id on floor", lo.y >= Studio.FY - 1e-3f)
        }
        for (x in Studio.SWATCH_X) assertTrue(inside(V3(x, Studio.SWATCH_Y, Studio.SWATCH_Z), Studio.SWATCH_R))
        assertTrue(inside(Studio.STRIP_C + V3(Studio.STRIP_W / 2, Studio.STRIP_H / 2, 0f)))
    }

    /** D35: name plate corners never enter the subject box nor cut the eye -> hint strip sight lines. */
    @Test fun platesClearSubjectAndStrip() {
        val sub = SubjectId.entries.map { Shots.subject(it).meshes.map { m -> m.bounds() } }.flatten()
        val lo = V3(sub.minOf { it.first.x }, sub.minOf { it.first.y }, sub.minOf { it.first.z })
        val hi = V3(sub.maxOf { it.second.x }, sub.maxOf { it.second.y }, sub.maxOf { it.second.z })
        fun inBox(p: V3) = p.x in lo.x..hi.x && p.y in lo.y..hi.y && p.z in lo.z..hi.z
        val stripBottom = Studio.STRIP_C.y - Studio.STRIP_H / 2
        for ((az, el) in reach) {
            val p = Studio.orbPos(az, el)
            for (dx in listOf(-1f, 1f)) for (dy in listOf(-1f, 1f)) {
                val c = p + V3(dx * Studio.LABEL_W / 2, Studio.LABEL_DY + dy * Studio.LABEL_H / 2, 0f)
                assertTrue("plate in subject $az/$el", !inBox(c))
                // the eye -> strip bottom line at this corner's depth
                val t = (Studio.EYE.z - c.z) / (Studio.EYE.z - Studio.STRIP_C.z)
                val lineY = Studio.EYE.y + (stripBottom - Studio.EYE.y) * t
                assertTrue("plate blocks strip $az/$el ${c.y} vs $lineY", c.y < lineY)
            }
        }
    }

    /** D17: orb hit >= 2° from the farthest orb position; swatch hit >= 2°; gaps >= 1.5 cm. */
    @Test fun hitTargetsBigEnough() {
        for ((az, el) in reach) {
            val d = Studio.orbPos(az, el).dist(Studio.EYE)
            val deg = 2 * kotlin.math.atan(Studio.HIT_R / d) / Studio.D2R
            assertTrue("orb $az/$el $deg°", deg >= 2f)
        }
        val ds = V3(Studio.SWATCH_X[0], Studio.SWATCH_Y, Studio.SWATCH_Z).dist(Studio.EYE)
        assertTrue(2 * kotlin.math.atan(Studio.SWATCH_HIT_R / ds) / Studio.D2R >= 2f)
        assertTrue(Studio.SWATCH_X[1] - Studio.SWATCH_X[0] - 2 * Studio.SWATCH_HIT_R >= 0.015f)
        // two orbs at the minimum 28° apart: chord minus both hit radii
        val chord = 2 * Studio.R * kotlin.math.sin(Studio.MIN_SEP / 2 * Studio.D2R)
        assertTrue(chord - 2 * Studio.HIT_R >= 0.015f)
    }

    @Test fun mixHeadroom() {
        assertTrue(io.embodit.renderlab.platform.MixHeadroom.gain(0.9f, 0.5f) * 0.9f + 0.5f <= 0.892f)
    }
}
