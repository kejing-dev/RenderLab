package io.embodit.renderlab.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every metre of the studio volume (design container.volume.math). Origin = volume centre, +X right, +Y up,
 * +Z toward the user. Volume box x ±0.35, y ±0.32, z ±0.33.
 */
object Studio {
    const val HX = 0.35f
    const val HY = 0.32f
    const val HZ = 0.33f

    /** Floor top (台面) and back wall plane. */
    const val FY = -0.30f
    const val WZ = -0.31f
    const val X0 = -0.33f
    const val X1 = 0.33f
    /** Floor front edge. */
    const val ZF = 0.21f
    const val WALL_H = 0.54f
    const val WALL_TOP = FY + WALL_H

    /** Orbit centre C and radius R of the light orbs. */
    val C = V3(0f, -0.24f, -0.10f)
    const val R = 0.24f
    /** Nominal eye (volume 1.75 m ahead, 6 cm above its centre; tangram-wood measurement). */
    val EYE = V3(0f, 0.06f, 1.75f)

    const val ORB_R = 0.02f
    const val HIT_R = 0.0375f
    const val HALO = 0.09f
    const val EL_MIN = 10f
    const val EL_MAX = 80f
    const val AZ_MAX = 135f
    /** Minimum angle on the orbit sphere between two orbs (design input.dragOrb). */
    const val MIN_SEP = 28f

    /** Hint strip on the wall's top edge (design panels.hintStrip). */
    const val STRIP_W = 0.60f
    const val STRIP_H = 0.04f
    val STRIP_C = V3(0f, 0.22f, WZ + 0.005f)

    /** Name plate above each orb (design spatialLayout ③). */
    // v0.1.1 (QA #1 "名牌和拖动读数偏小"): 12 x 2.25 cm -> 16 x 3.2 cm, ~2.6° tall at the 0.7 m working distance
    const val LABEL_W = 0.16f
    const val LABEL_H = 0.032f
    const val LABEL_DY = 0.05f

    /**
     * D53 step buttons beside the selected light: upright 4.4 cm squares (>= 4 cm, ~3.6° at 0.7 m), ◀ ▶ 8 cm to the
     * sides (1.9 cm clear of the orb's hit sphere), ▲ above the name plate, ▼ below (kept above the floor).
     */
    const val NUDGE_S = 0.044f
    const val NUDGE_HIT = 0.050f
    const val NUDGE_SIDE = 0.08f
    const val NUDGE_UP = LABEL_DY + LABEL_H / 2 + 0.016f + NUDGE_S / 2
    const val NUDGE_DOWN = 0.08f

    fun nudgePos(orb: V3, d: Nudge): V3 = when (d) {
        Nudge.LEFT -> orb + V3(-NUDGE_SIDE, 0f, 0f)
        Nudge.RIGHT -> orb + V3(NUDGE_SIDE, 0f, 0f)
        Nudge.UP -> orb + V3(0f, NUDGE_UP, 0f)
        Nudge.DOWN -> V3(orb.x, maxOf(orb.y - NUDGE_DOWN, FY + NUDGE_S / 2 + 0.005f), orb.z)
    }

    /** Material shelf (free light only). */
    const val SWATCH_R = 0.0225f
    const val SWATCH_HIT_R = 0.0325f
    const val SWATCH_Y = -0.2775f
    const val SWATCH_Z = 0.275f
    val SWATCH_X = floatArrayOf(-0.21f, -0.105f, 0f, 0.105f, 0.21f)

    /** The wall only takes taps above this height (free light, design tapBackdrop). */
    const val WALL_TAP_MIN_Y = -0.05f

    const val D2R = (PI / 180).toFloat()

    fun orbPos(az: Float, el: Float): V3 = V3(
        C.x + R * cos(el * D2R) * sin(az * D2R),
        C.y + R * sin(el * D2R),
        C.z + R * cos(el * D2R) * cos(az * D2R),
    )

    /** Direction C -> orb (unit). */
    fun dir(az: Float, el: Float): V3 = (orbPos(az, el) - C).norm()

    fun angleDeg(a: V3, b: V3): Float = acos(a.norm().dot(b.norm()).coerceIn(-1f, 1f)) / D2R

    /** (az, el) of a direction from C. */
    fun azEl(v: V3): Pair<Float, Float> {
        val n = v.norm()
        return atan2(n.x, n.z) / D2R to asin(n.y.coerceIn(-1f, 1f)) / D2R
    }

    fun clampAz(az: Float) = az.coerceIn(-AZ_MAX, AZ_MAX)
    fun clampEl(el: Float) = el.coerceIn(EL_MIN, EL_MAX)

    /**
     * Whether an orb at (az, el) keeps clear of another orb at (az2, el2): >= 28° apart on the sphere, and from the
     * nominal eye the two centres differ by more than the two hit half-angles + 0.5° (design input.dragOrb).
     */
    fun clearOf(az: Float, el: Float, az2: Float, el2: Float): Boolean {
        val p = orbPos(az, el)
        val q = orbPos(az2, el2)
        if (angleDeg(p - C, q - C) < MIN_SEP) return false
        val need = (atan(HIT_R / p.dist(EYE)) + atan(HIT_R / q.dist(EYE))) / D2R + 0.5f
        return angleDeg(p - EYE, q - EYE) >= need
    }

    /** A spoken readout of an orb position: "右前 40° · 高 35°" / "front-right 40° · up 35°". */
    fun readout(az: Float, el: Float, zh: Boolean): String {
        val a = abs(Math.round(az))
        val side = if (az >= 0) (if (zh) "右" else "right") else (if (zh) "左" else "left")
        val fb = if (abs(az) <= 90) (if (zh) "前" else "front") else (if (zh) "后" else "back")
        return if (zh) "$side$fb $a° · 高 ${Math.round(el)}°" else "$fb-$side $a° · up ${Math.round(el)}°"
    }
}

enum class LightId { KEY, FILL, RIM }

enum class Level { OFF, LOW, MID, HIGH }

enum class Kelvin(val k: Int, val rgb: Int) { WARM(3200, 0xFFB46B), DAY(5600, 0xFFF4E5), COOL(7500, 0xCFE0FF) }

/** One light: where its orb sits and how bright / warm it is. The key light has no OFF level. */
data class LightSet(val az: Float, val el: Float, val kelvin: Kelvin = Kelvin.DAY, val level: Level = Level.OFF) {
    val on: Boolean get() = level != Level.OFF
    val pos: V3 get() = Studio.orbPos(az, el)
    val dir: V3 get() = Studio.dir(az, el)
}

data class Lights(val key: LightSet, val fill: LightSet, val rim: LightSet) {
    operator fun get(id: LightId) = when (id) { LightId.KEY -> key; LightId.FILL -> fill; LightId.RIM -> rim }
    fun with(id: LightId, l: LightSet) = when (id) { LightId.KEY -> copy(key = l); LightId.FILL -> copy(fill = l); LightId.RIM -> copy(rim = l) }

    /** Can [id] move to (az, el) without crowding the other two orbs? */
    fun canPlace(id: LightId, az: Float, el: Float): Boolean =
        LightId.entries.filter { it != id }.all { o -> this[o].let { Studio.clearOf(az, el, it.az, it.el) } }

    companion object {
        val FILL_HOME = LightSet(-100f, 15f, Kelvin.DAY, Level.OFF)
        val RIM_HOME = LightSet(120f, 50f, Kelvin.DAY, Level.OFF)
        val DEFAULT = Lights(LightSet(40f, 35f, Kelvin.DAY, Level.HIGH), FILL_HOME, RIM_HOME)
    }
}

/** Orb colours (design lightingModel.lights[].orbColor). */
fun orbRgb(id: LightId): Int = when (id) { LightId.KEY -> 0xFFD27A; LightId.FILL -> 0x9CC7FF; LightId.RIM -> 0xE7A6FF }

enum class SubjectId { PLASTER, TEAPOT, VASE }

/** PBR presets (design materials[]). */
enum class MaterialId(val base: Int, val roughness: Float, val metallic: Float, val emissive: Float = 0f, val emissiveRgb: Int = 0) {
    PLASTER(0xEDEAE4, 0.9f, 0f),
    CERAMIC(0x8FB9A8, 0.25f, 0f),
    GOLD(0xD4A84F, 0.3f, 1f),
    RUBBER(0x2B2B2E, 0.75f, 0f),
    GLOW(0x3E6E66, 0.4f, 0f, 1.6f, 0x7FFFD4),
}

/** Backdrops (design backdrops[]); dusk's wall is a vertical gradient texture. */
enum class Backdrop(val wall: Int, val floor: Int, val gradTop: Int = 0, val gradBottom: Int = 0, val fence: Int) {
    WHITE(0xD9D7D2, 0xB5B2AC, fence = 0x15546A),
    BLACK(0x1A1A1D, 0x3A3A3E, fence = 0xFFFFFF),
    DUSK(0xFFFFFF, 0x8C7F78, 0x2E3A5C, 0xD98C5F, fence = 0x15546A);

    val isGradient: Boolean get() = this == DUSK
    fun next(): Backdrop = entries[(ordinal + 1) % entries.size]
}

/** Shadow colour = the receiving surface colour x 0.45 (design backdrops[].shadowColor). */
fun shade(rgb: Int, k: Float = 0.45f): Int {
    val r = ((rgb shr 16 and 255) * k).toInt(); val g = ((rgb shr 8 and 255) * k).toInt(); val b = ((rgb and 255) * k).toInt()
    return (r shl 16) or (g shl 8) or b
}

/** Dusk wall shadow: the gradient's mean x 0.45. */
fun Backdrop.wallShadow(): Int = if (isGradient) shade(mix(gradTop, gradBottom, 0.5f)) else shade(wall)

fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a shr s and 255) * (1 - t)) + ((b shr s and 255) * t)).toInt().coerceIn(0, 255)
    return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}
