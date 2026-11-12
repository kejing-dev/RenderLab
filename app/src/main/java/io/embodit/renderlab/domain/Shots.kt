package io.embodit.renderlab.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The other checks of a shot (design scoring.otherChecks). */
enum class Check { KEY_WARM, KEY_LOW, FILL_LOW, FILL_COOL, RIM }

/** A target light: where it should be and how it should be set. */
data class Target(val az: Float, val el: Float, val kelvin: Kelvin, val level: Level)

/**
 * One shot (design content.shots[], or a PCG one). Subject, material and backdrop are part of the shot; scoring only
 * looks at the key light's shadow plus [checks].
 */
data class Shot(
    val id: String,
    val zh: String, val en: String,
    val tagZh: String, val tagEn: String,
    val whyZh: String, val whyEn: String,
    val subject: SubjectId, val backdrop: Backdrop, val material: MaterialId,
    val key: Target, val fill: Target?, val rim: Target?,
    val checks: List<Check>,
    val startAz: Float, val startEl: Float,
    val seed: Long? = null,
) {
    val isRandom: Boolean get() = seed != null

    /** The lights of this shot when it is solved (debug solve, unit tests). */
    fun solvedLights(): Lights = Lights(
        LightSet(key.az, key.el, key.kelvin, key.level),
        fill?.let { LightSet(it.az, it.el, it.kelvin, it.level) } ?: Lights.FILL_HOME,
        rim?.let { LightSet(it.az, it.el, it.kelvin, it.level) } ?: Lights.RIM_HOME,
    )

    /** Opening lights (design content.startRule): key at startKey, high, daylight; fill / rim off at home. */
    fun startLights(): Lights = Lights(LightSet(startAz, startEl, Kelvin.DAY, Level.HIGH), Lights.FILL_HOME, Lights.RIM_HOME)
}

object Shots {
    private fun t(az: Int, el: Int, k: Kelvin = Kelvin.DAY, l: Level = Level.HIGH) = Target(az.toFloat(), el.toFloat(), k, l)

    /** The 8 scene shots, copied from the design package (content.shots[]). */
    val ALL: List<Shot> = listOf(
        Shot("L1", "侧光拉影", "Long side shadow", "光从右边低低地来，影子往左拉得老长。", "Low light from the right. The shadow stretches out to the left.",
            "影子永远在光的对面，光越低影子越长。灯往哪边挪，影子就往反方向跑。", "Shadows fall away from the light, and the lower the light the longer they get.",
            SubjectId.PLASTER, Backdrop.WHITE, MaterialId.PLASTER, t(70, 30), null, null, emptyList(), -20f, 55f),
        Shot("L2", "侧光切一半", "Split light", "一半亮一半暗，像半个月亮。", "Half bright, half dark. Like a half moon.",
            "光正好从侧面来，明暗交界线就落在球的正中间。", "Light from straight to the side puts the edge right down the middle of the ball.",
            SubjectId.PLASTER, Backdrop.BLACK, MaterialId.PLASTER, t(-90, 35), null, null, emptyList(), 25f, 45f),
        Shot("L3", "三大面", "Three faces", "亮、灰、暗，三块分清楚。", "Light, mid, dark. Keep the three apart.",
            "主光斜着从侧面来、不太高，立方体正好亮一面、灰一面、暗一面。补光开低档，暗面还能看清。", "Key from the side and not too high gives the cube one bright, one mid and one dark face. A low fill keeps the dark side readable.",
            SubjectId.PLASTER, Backdrop.WHITE, MaterialId.PLASTER, t(-70, 30), t(60, 20, Kelvin.DAY, Level.LOW), null, listOf(Check.FILL_LOW), 50f, 25f),
        Shot("L4", "高侧光", "High side light", "光又高又偏，影子缩在脚边，顶面最亮。", "High and to the side. A short shadow, a bright top.",
            "光抬高，影子变短；顶面朝着光，最亮。正午的街景就是这个样子。", "Raise the light and shadows shrink. The top faces the light, so it is brightest. Think midday street.",
            SubjectId.PLASTER, Backdrop.WHITE, MaterialId.PLASTER, t(-70, 60), null, null, emptyList(), 40f, 25f),
        Shot("L5", "逆光描边", "Rim light", "从背后打，花瓶边上亮出一圈。", "Light from behind. The vase gets a glowing edge.",
            "黑背景前，轮廓光把物体从背景里抠出来。", "On black, a rim light cuts the object out of the background.",
            SubjectId.VASE, Backdrop.BLACK, MaterialId.CERAMIC, t(50, 35, Kelvin.DAY, Level.LOW), null, t(-135, 35, Kelvin.DAY, Level.HIGH), listOf(Check.KEY_LOW, Check.RIM), -60f, 30f),
        Shot("L6", "冷暖对打", "Warm meets cool", "一边暖，一边冷，立刻有电影感。", "Warm on one side, cool on the other. Instant movie look.",
            "暖主光加冷补光，暗面不会死黑，还多了层次。", "Warm key plus cool fill keeps the shadows alive and adds depth.",
            SubjectId.TEAPOT, Backdrop.DUSK, MaterialId.CERAMIC, t(-60, 35, Kelvin.WARM, Level.HIGH), t(70, 20, Kelvin.COOL, Level.MID), null, listOf(Check.KEY_WARM, Check.FILL_COOL), 40f, 50f),
        Shot("L7", "金属一道光", "One streak on metal", "金茶壶上只留一道干净的高光。", "One clean highlight on the gold teapot.",
            "金属不怎么吃光，全靠反光。轮廓光那一道亮边，形状就出来了。", "Metal shows reflections, not light. One rim streak and the shape reads.",
            SubjectId.TEAPOT, Backdrop.WHITE, MaterialId.GOLD, t(70, 45, Kelvin.DAY, Level.MID), null, t(-120, 40, Kelvin.DAY, Level.MID), listOf(Check.RIM), -60f, 25f),
        Shot("L8", "关灯夜光", "Lights out, glow on", "把灯调暗，让釉自己亮。", "Dim the light. Let the glaze glow.",
            "自发光不靠灯，周围越暗它越显眼。", "Glow doesn't need light. The darker it gets, the more it pops.",
            SubjectId.VASE, Backdrop.BLACK, MaterialId.GLOW, t(-40, 40, Kelvin.COOL, Level.LOW), null, null, listOf(Check.KEY_LOW), 60f, 25f),
    )

    fun byId(id: String): Shot? = ALL.firstOrNull { it.id == id }

    private val subjects = HashMap<SubjectId, Subject>()

    /** Subject geometry is pure and the same every time: built once per process. */
    fun subject(id: SubjectId): Subject = synchronized(subjects) { subjects.getOrPut(id) { Subject.build(id) } }

    /**
     * 随机一题 (design content.pcg): key az in [-135, 135], el in [30, 70]; rejected unless the target shadow lies whole
     * inside floor + wall with >= 80 cells, is >= 60 % visible from the nominal eye, and the target orb positions are
     * legal. Half the seeds add a fill >= 70° away (low / mid). Key start >= 40° from the target. Always solvable.
     */
    fun random(seed: Long): Shot {
        var s = seed
        while (true) {
            candidate(s)?.let { return it }
            s = s * 6364136223846793005L + 1442695040888963407L
        }
    }

    private fun candidate(seed: Long): Shot? {
        val r = Random(seed)
        val subject = SubjectId.entries[r.nextInt(3)]
        val backdrop = Backdrop.entries[r.nextInt(3)]
        val material = listOf(MaterialId.PLASTER, MaterialId.CERAMIC, MaterialId.GOLD, MaterialId.RUBBER)[r.nextInt(4)]
        val az = (r.nextInt(271) - 135).toFloat()
        val el = (30 + r.nextInt(41)).toFloat()
        val kelvin = Kelvin.entries[r.nextInt(3)]
        val key = Target(az, el, kelvin, Level.HIGH)
        var fill: Target? = null
        if (r.nextBoolean()) {
            var fz = 0f; var tries = 0
            val fe = (15 + r.nextInt(16)).toFloat()
            do { fz = (r.nextInt(271) - 135).toFloat(); tries++ } while ((abs(fz - az) < 70 || !Studio.clearOf(fz, fe, az, el)) && tries < 40)
            if (tries >= 40) return null
            fill = Target(fz, fe, Kelvin.entries[r.nextInt(3)], if (r.nextBoolean()) Level.LOW else Level.MID)
        }
        val parts = subject(subject).parts
        val dir = Studio.dir(az, el)
        if (!Shadow.fitsInside(parts, dir)) return null
        val mask = Shadow.raster(Shadow.hulls(parts, dir))
        if (Shadow.count(mask) < 80) return null
        if (Shadow.visibleShare(mask, parts) < 0.60f) return null
        // start: a legal key position >= 40° from the target
        var sa = 0f; var se = 0f; var ok = false
        for (i in 0 until 60) {
            sa = (r.nextInt(271) - 135).toFloat(); se = (15 + r.nextInt(56)).toFloat()
            if (Studio.angleDeg(Studio.dir(sa, se), dir) >= 40f && (fill == null || Studio.clearOf(sa, se, Lights.FILL_HOME.az, Lights.FILL_HOME.el)) &&
                Studio.clearOf(sa, se, Lights.FILL_HOME.az, Lights.FILL_HOME.el) && Studio.clearOf(sa, se, Lights.RIM_HOME.az, Lights.RIM_HOME.el)) { ok = true; break }
        }
        if (!ok) return null
        val checks = buildList {
            if (kelvin == Kelvin.WARM) add(Check.KEY_WARM)
            if (fill?.level == Level.LOW) add(Check.FILL_LOW)
            if (fill != null && fill.kelvin == Kelvin.COOL) add(Check.FILL_COOL)
        }
        val n = (seed % 10000 + 10000) % 10000
        return Shot(
            "R", "随机一题", "Random shot", "种子 $n，一定有解。", "Seed $n. Always solvable.",
            "这题是随机摆的：影子落进框，再把色温和补光对上就行。", "A random setup: get the shadow in the fence, then match the colour and fill.",
            subject, backdrop, material, key, fill, null, checks, sa, se, seed = n,
        )
    }
}

/** The human hint of the first check line (design scoring.hints). */
enum class Hint { IN, LEFT, RIGHT, HIGHER, LOWER, TOWARD, BACK }

data class Score(
    val iou: Float,
    val stars: Int,
    val shadowStars: Int,
    val checks: List<Pair<Check, Boolean>>,
    val hint: Hint,
) {
    val in3: Boolean get() = shadowStars == 3
    val matched: Boolean get() = stars == 3 && checks.all { it.second }
}

/**
 * A shot prepared for play: the target shadow (same algorithm, so the fence and the score always agree) and the
 * per-shot star thresholds = the smallest IoU when the target light is pushed 7° / 14° / 24° in 8 directions
 * (design scoring.shadowOverlap).
 */
class ShotEval(val shot: Shot) {
    val subject: Subject = Shots.subject(shot.subject)
    val target: Hulls = Shadow.hulls(subject.parts, Studio.dir(shot.key.az, shot.key.el))
    val targetMask: BooleanArray = Shadow.raster(target)
    val thresholds: FloatArray = FloatArray(3).also { th ->
        val tv = Studio.dir(shot.key.az, shot.key.el)
        val ax1 = tv.cross(V3.UP).norm()
        val ax2 = tv.cross(ax1).norm()
        listOf(7f, 14f, 24f).forEachIndexed { k, deg ->
            var mn = 1f
            for (q in 0 until 8) {
                val ang = q * Math.PI.toFloat() / 4
                val ax = ax1 * cos(ang) + ax2 * sin(ang)
                val v = Q4.axis(ax, deg * Studio.D2R).rotate(tv)
                mn = minOf(mn, Shadow.iou(Shadow.raster(Shadow.hulls(subject.parts, v)), targetMask))
            }
            th[k] = mn
        }
    }

    fun shadowStars(iou: Float): Int = when {
        iou >= thresholds[0] -> 3
        iou >= thresholds[1] -> 2
        iou >= thresholds[2] -> 1
        else -> 0
    }

    fun check(c: Check, l: Lights): Boolean = when (c) {
        Check.KEY_WARM -> l.key.kelvin == Kelvin.WARM
        Check.KEY_LOW -> l.key.level == Level.LOW
        Check.FILL_LOW -> l.fill.on && l.fill.level == Level.LOW
        Check.FILL_COOL -> l.fill.on && l.fill.kelvin == Kelvin.COOL
        Check.RIM -> shot.rim != null && l.rim.on && Studio.angleDeg(l.rim.dir, Studio.dir(shot.rim.az, shot.rim.el)) <= 15f
    }

    fun score(l: Lights, hulls: Hulls = Shadow.hulls(subject.parts, l.key.dir)): Score {
        val iou = Shadow.iou(Shadow.raster(hulls), targetMask)
        val ss = shadowStars(iou)
        val checks = shot.checks.map { it to check(it, l) }
        val stars = (ss - checks.count { !it.second }).coerceAtLeast(0)
        return Score(iou, stars, ss, checks, hint(l, ss == 3))
    }

    /** "再往左一点" etc.: the biggest component of (target orb - current orb). */
    fun hint(l: Lights, in3: Boolean): Hint {
        if (in3) return Hint.IN
        val d = Studio.orbPos(shot.key.az, shot.key.el) - l.key.pos
        val ax = abs(d.x); val ay = abs(d.y); val az = abs(d.z)
        return when {
            ax >= ay && ax >= az -> if (d.x > 0) Hint.RIGHT else Hint.LEFT
            ay >= az -> if (d.y > 0) Hint.HIGHER else Hint.LOWER
            else -> if (d.z > 0) Hint.TOWARD else Hint.BACK
        }
    }
}
