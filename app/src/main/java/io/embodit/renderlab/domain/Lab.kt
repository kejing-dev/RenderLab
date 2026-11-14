package io.embodit.renderlab.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** S00 loading · S01 welcome · S02/S03/S06 lighting (dragging / matched are derived) · S07/S10 result · S09 free. */
enum class Phase { LOADING, WELCOME, LIGHTING, RESULT, FREE }

enum class Sound { GRAB, DROP, MATCH, SHUTTER, STAR }

/** The 3D step buttons beside the selected light (D53). */
enum class Nudge { UP, DOWN, LEFT, RIGHT }

enum class Language { SYSTEM, ZH, EN }

/** volume 0 = mute, 1..3 = low / mid / high (design: three snap stops, far left = mute). */
data class Settings(val language: Language = Language.SYSTEM, val volume: Int = 2, val lastVolume: Int = 2)

/** What [拍下] produced (design S07). */
data class Result(val stars: Int, val iou: Float, val checks: List<Pair<Check, Boolean>>, val allDone: Boolean, val lights: Lights)

/** Which card / mode: 0..7 the scene shots, [RANDOM], [FREE]. */
object Mode { const val RANDOM = 8; const val FREE = 9; const val CARDS = 10 }

data class LabState(
    val phase: Phase = Phase.LOADING,
    val mode: Int = 0,
    val shot: Shot? = null,
    val eval: ShotEval? = null,
    val lights: Lights = Lights.DEFAULT,
    val selected: LightId = LightId.KEY,
    val dragging: LightId? = null,
    val subject: SubjectId = SubjectId.PLASTER,
    val material: MaterialId = MaterialId.PLASTER,
    val backdrop: Backdrop = Backdrop.WHITE,
    val hulls: Hulls = Hulls(emptyList(), emptyList()),
    val score: Score? = null,
    val result: Result? = null,
    val best: Map<String, Int> = emptyMap(),
    val freeSubject: SubjectId = SubjectId.TEAPOT,
    val freeMaterial: MaterialId = MaterialId.GOLD,
    val freeBackdrop: Backdrop = Backdrop.DUSK,
    val matchedAt: Long = -1,
    val shutterAt: Long = -1,
    val resultAt: Long = -1,
    val allStarsAt: Long = -1,
    val swapAt: Long = -1,
    val card: Int = 0,
    val randomSeed: Long = 4821,
    val welcomeDone: Boolean = false,
    /** First-entry tip (B19 how-to): "pinch a light and drag it" beside the subject until the first real move. */
    val guideDone: Boolean = false,
) {
    val free: Boolean get() = phase == Phase.FREE
    val matched: Boolean get() = score?.matched == true
    val allThree: Boolean get() = Shots.ALL.all { (best[it.id] ?: 0) >= 3 }

    /** The design state id the window / volume shows now (for logs, QA and the debug `dump`). */
    val stateId: String get() = when (phase) {
        Phase.LOADING -> "S00"
        Phase.WELCOME -> "S01"
        Phase.LIGHTING -> if (dragging != null) "S03" else if (matched) "S06" else "S02"
        Phase.RESULT -> if (result?.allDone == true) "S10" else "S07"
        Phase.FREE -> if (dragging != null) "S03" else "S09"
    }
}

/** Saved progress (design offline.save). */
data class Saved(
    val best: Map<String, Int>,
    val mode: Int,
    val randomSeed: Long,
    val lights: Lights,
    val freeSubject: SubjectId,
    val freeMaterial: MaterialId,
    val freeBackdrop: Backdrop,
    val welcomeDone: Boolean,
    val guideDone: Boolean = false,
)

interface SaveStore { fun load(): Saved?; fun save(s: Saved); fun clear() }

object SaveCodec {
    private fun l(s: LightSet) = "${s.az},${s.el},${s.kelvin.name},${s.level.name}"
    private fun pl(t: String): LightSet { val f = t.split(","); return LightSet(f[0].toFloat(), f[1].toFloat(), Kelvin.valueOf(f[2]), Level.valueOf(f[3])) }

    fun encode(s: Saved): String = listOf(
        "v=1",
        "best=" + s.best.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value}" },
        "mode=${s.mode}", "seed=${s.randomSeed}",
        "key=${l(s.lights.key)}", "fill=${l(s.lights.fill)}", "rim=${l(s.lights.rim)}",
        "free=${s.freeSubject.name},${s.freeMaterial.name},${s.freeBackdrop.name}",
        "welcome=${if (s.welcomeDone) 1 else 0}",
        "guide=${if (s.guideDone) 1 else 0}",
    ).joinToString("\n")

    fun decode(text: String): Saved {
        val m = text.lines().filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=") }
        val best = m["best"].orEmpty().split(";").filter { ":" in it }.associate { it.substringBefore(":") to it.substringAfter(":").toInt() }
        val fr = m.getValue("free").split(",")
        return Saved(
            best, m.getValue("mode").toInt(), m.getValue("seed").toLong(),
            Lights(pl(m.getValue("key")), pl(m.getValue("fill")), pl(m.getValue("rim"))),
            SubjectId.valueOf(fr[0]), MaterialId.valueOf(fr[1]), Backdrop.valueOf(fr[2]), m["welcome"] == "1", m["guide"] == "1",
        )
    }
}

/**
 * The whole app state in one place (Compose and the ECS scene both read [state]; every input path, the panel, the
 * volume and the debug broadcast, calls the same methods here, D29).
 */
class LabFlow(
    private val scope: CoroutineScope,
    private val engine: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: SaveStore,
    private val onSound: (Sound, Float) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(LabState())
    val state: StateFlow<LabState> = _state.asStateFlow()
    private val st get() = _state.value

    private var dragFrom: V3 = V3.ZERO
    private var lastScoreAt = 0L
    private var loadToken = 0

    /** Cold start: restore the save (S02 / S09 where you left off) or open the first-run welcome on L1 (S01). */
    fun start() {
        val saved = runCatching { store.load() }.getOrNull()
        if (saved == null) {
            _state.value = LabState(phase = Phase.LOADING)
            loadShot(0, Phase.WELCOME)
            return
        }
        _state.value = LabState(
            phase = Phase.LOADING, best = saved.best, randomSeed = saved.randomSeed, freeSubject = saved.freeSubject,
            freeMaterial = saved.freeMaterial, freeBackdrop = saved.freeBackdrop, welcomeDone = saved.welcomeDone, guideDone = saved.guideDone,
        )
        when {
            !saved.welcomeDone -> loadShot(0, Phase.WELCOME)
            saved.mode == Mode.FREE -> startFree(saved.lights)
            else -> loadShot(saved.mode, Phase.LIGHTING, keep = saved.lights)
        }
    }

    // ------------------------------------------------------------------ shots

    private fun shotFor(mode: Int): Shot = if (mode == Mode.RANDOM) Shots.random(st.randomSeed) else Shots.ALL[mode.coerceIn(0, 7)]

    /** Loads a shot: subject / material / backdrop from the shot, key at its start (or [keep]), eval off the main thread. */
    fun loadShot(mode: Int, phase: Phase = Phase.LIGHTING, keep: Lights? = null) {
        val token = ++loadToken
        val shot = shotFor(mode)
        val lights = keep ?: shot.startLights()
        _state.value = st.copy(
            phase = if (st.phase == Phase.LOADING && phase != Phase.WELCOME) Phase.LOADING else phase,
            mode = mode, shot = shot, eval = null, lights = lights, selected = LightId.KEY, dragging = null,
            subject = shot.subject, material = shot.material, backdrop = shot.backdrop, result = null, score = null,
            matchedAt = -1, card = mode,
        )
        recomputeHulls()
        log("SHOT load ${shot.id} seed=${shot.seed} phase=$phase")
        scope.launch {
            val ev = withContext(engine) { ShotEval(shot) }
            if (token != loadToken) return@launch
            _state.value = st.copy(eval = ev, phase = phase)
            log("SHOT ready ${shot.id} th=${ev.thresholds.joinToString { "%.2f".format(it) }} -> ${st.stateId}")
            rescore(force = true)
            save()
        }
    }

    fun startFree(keep: Lights? = null) {
        loadToken++
        val l = keep ?: st.lights.let { it.copy(key = it.key.copy(level = if (it.key.level == Level.OFF) Level.HIGH else it.key.level)) }
        _state.value = st.copy(
            phase = Phase.FREE, mode = Mode.FREE, shot = null, eval = null, lights = l, dragging = null, result = null,
            score = null, subject = st.freeSubject, material = st.freeMaterial, backdrop = st.freeBackdrop, card = Mode.FREE,
        )
        recomputeHulls()
        log("FREE start ${st.subject} ${st.material} ${st.backdrop}")
        save()
    }

    /** Shots page [开始这题] (T6 / T7): 0..7, random (new seed every time), free. */
    fun startCard(card: Int) {
        when (card) {
            Mode.FREE -> startFree()
            Mode.RANDOM -> { _state.value = st.copy(randomSeed = newSeed()); loadShot(Mode.RANDOM) }
            else -> loadShot(card)
        }
        welcomeDone()
    }

    fun setCard(card: Int) { _state.value = st.copy(card = (card + Mode.CARDS) % Mode.CARDS) }

    private fun newSeed(): Long = (clock() * 2654435761L xor 0x5DEECE66DL).let { (it % 9000 + 9000) % 9000 + 1000 }

    /** S01 [开始] (or the first drag of the key light). */
    fun beginFromWelcome() {
        if (st.phase != Phase.WELCOME) return
        welcomeDone()
        _state.value = st.copy(phase = Phase.LIGHTING)
        log("WELCOME done -> ${st.stateId}")
        rescore(force = true)
    }

    private fun welcomeDone() { if (!st.welcomeDone) { _state.value = st.copy(welcomeDone = true); save() } }

    // ------------------------------------------------------------------ orbs

    private val canTouch: Boolean get() = st.phase == Phase.LIGHTING || st.phase == Phase.FREE || st.phase == Phase.WELCOME

    /** Tap on an orb (T2): select it (D52: tapping another orb switches straight over); an off fill / rim turns on low. */
    fun tapOrb(id: LightId) {
        if (!canTouch) return
        var l = st.lights
        if (!l[id].on) l = l.with(id, l[id].copy(level = Level.LOW))
        _state.value = st.copy(selected = id, lights = l)
        log("ORB tap $id on=${l[id].level}")
        changed(force = true)
    }

    /** A pinch / trigger held on an orb moved past the drag threshold (T1). */
    fun dragStart(id: LightId): Boolean {
        if (!canTouch) return false
        if (st.phase == Phase.WELCOME) beginFromWelcome()
        var l = st.lights
        if (!l[id].on) l = l.with(id, l[id].copy(level = Level.LOW))
        dragFrom = l[id].pos
        _state.value = st.copy(dragging = id, selected = id, lights = l)
        onSound(Sound.GRAB, 1f)
        log("DRAG start $id at ${Studio.readout(l[id].az, l[id].el, false)}")
        return true
    }

    /** [total] = hand travel since the press (metres, volume axes) added to the orb, put back on the sphere, clamped. */
    fun dragMove(total: V3) {
        val id = st.dragging ?: return
        val (az, el) = Studio.azEl(dragFrom + total - Studio.C)
        place(id, az, el)
    }

    /** Controller ray drag: the orb goes toward a studio-local point (the ray at its grab distance), on the sphere. */
    fun dragTo(local: V3) {
        val id = st.dragging ?: return
        val (az, el) = Studio.azEl(local - Studio.C)
        place(id, az, el)
    }

    fun dragEnd() {
        val id = st.dragging ?: return
        val moved = Studio.angleDeg(st.lights[id].pos - Studio.C, dragFrom - Studio.C)
        _state.value = st.copy(dragging = null, guideDone = st.guideDone || moved >= GUIDE_MOVE_DEG)
        onSound(Sound.DROP, 0.84f)
        log("DRAG end $id at ${Studio.readout(st.lights[id].az, st.lights[id].el, false)} moved=${"%.1f".format(moved)}°")
        changed(force = true)
        save()
    }

    fun dragCancel() = dragEnd()

    /**
     * D53 fallback: the 3D ▲▼◀▶ beside the selected light. ◀ / ▶ move it left / right as you see it (round the
     * subject), ▲ / ▼ up / down, [NUDGE_DEG] a step (held: repeated by the caller). Same clamp / no-push rule as a drag.
     */
    fun nudge(dir: Nudge) {
        if (!canTouch || st.dragging != null) return
        if (st.phase == Phase.WELCOME) beginFromWelcome()
        val id = st.selected
        var l = st.lights
        if (!l[id].on) { l = l.with(id, l[id].copy(level = Level.LOW)); _state.value = st.copy(lights = l) }
        val cur = l[id]
        val (az, el) = nudgeTarget(cur.az, cur.el, dir)
        place(id, az, el)
        var after = st.lights[id]
        var moved = kotlin.math.abs(after.az - cur.az) + kotlin.math.abs(after.el - cur.el) > 0.5f
        // blocked by another light right next to it: hop over it (2–10 steps) instead of doing nothing (D43)
        if (!moved) for (k in 2..10) {
            val ta = Studio.clampAz(cur.az + (az - cur.az) * k); val te = Studio.clampEl(cur.el + (el - cur.el) * k)
            if ((kotlin.math.abs(ta - cur.az) + kotlin.math.abs(te - cur.el)) > 0.5f && st.lights.canPlace(id, ta, te)) {
                _state.value = st.copy(lights = st.lights.with(id, cur.copy(az = ta, el = te)))
                after = st.lights[id]; moved = true
                break
            }
        }
        if (moved && !st.guideDone) _state.value = st.copy(guideDone = true)
        log("NUDGE $id $dir -> ${Studio.readout(after.az, after.el, false)} moved=$moved")
        changed(force = true)
        save()
    }

    /** Settings > How to use [重看引导]: the first-entry tip comes back. */
    fun showGuideAgain() {
        _state.value = st.copy(guideDone = false)
        log("GUIDE again")
        save()
    }

    /**
     * Moves orb [id] toward (az, el) clamped to the allowed band; if that crowds another orb it stops at the last clear
     * point on the way (design: 碰到就停在边界，不推开别人). Never moves on release (D1).
     */
    fun place(id: LightId, az: Float, el: Float) {
        val l = st.lights
        val cur = l[id]
        val ta = Studio.clampAz(az); val te = Studio.clampEl(el)
        var best: Pair<Float, Float>? = null
        if (l.canPlace(id, ta, te)) best = ta to te else {
            var lo = 0f; var hi = 1f
            repeat(12) {
                val m = (lo + hi) / 2
                if (l.canPlace(id, cur.az + (ta - cur.az) * m, cur.el + (te - cur.el) * m)) lo = m else hi = m
            }
            if (lo > 0f) best = (cur.az + (ta - cur.az) * lo) to (cur.el + (te - cur.el) * lo)
        }
        val (na, ne) = best ?: return
        _state.value = st.copy(lights = l.with(id, cur.copy(az = na, el = ne)))
        changed(force = false)
    }

    /** Brightness slider (T3) for the selected light: key low / mid / high; fill / rim off / low / mid / high. */
    fun setLevel(level: Level) {
        val id = st.selected
        val lv = if (id == LightId.KEY && level == Level.OFF) Level.LOW else level
        _state.value = st.copy(lights = st.lights.with(id, st.lights[id].copy(level = lv)))
        log("LEVEL $id $lv")
        changed(force = true); save()
    }

    fun setKelvin(k: Kelvin) {
        val id = st.selected
        _state.value = st.copy(lights = st.lights.with(id, st.lights[id].copy(kelvin = k)))
        log("KELVIN $id $k")
        changed(force = true); save()
    }

    // ------------------------------------------------------------------ free light

    fun cycleSubject() {
        if (st.phase != Phase.FREE) return
        val s = SubjectId.entries[(st.subject.ordinal + 1) % 3]
        _state.value = st.copy(subject = s, freeSubject = s, swapAt = clock())
        recomputeHulls(); log("FREE subject $s"); save()
    }

    fun setMaterial(m: MaterialId) {
        if (st.phase != Phase.FREE) return
        _state.value = st.copy(material = m, freeMaterial = m, swapAt = clock())
        log("FREE material $m"); save()
    }

    fun cycleBackdrop() {
        if (st.phase != Phase.FREE) return
        val b = st.backdrop.next()
        _state.value = st.copy(backdrop = b, freeBackdrop = b, swapAt = clock())
        log("FREE backdrop $b"); save()
    }

    // ------------------------------------------------------------------ scoring / result

    private fun recomputeHulls() {
        val parts = Shots.subject(st.subject).parts
        _state.value = st.copy(hulls = Shadow.hulls(parts, st.lights.key.dir))
    }

    private fun changed(force: Boolean) {
        recomputeHulls()
        rescore(force)
    }

    private fun rescore(force: Boolean) {
        val ev = st.eval ?: return
        if (st.phase != Phase.LIGHTING && st.phase != Phase.WELCOME) return
        val now = clock()
        if (!force && now - lastScoreAt < 33) return
        lastScoreAt = now
        val was = st.matched
        val sc = ev.score(st.lights, st.hulls)
        _state.value = st.copy(score = sc, matchedAt = if (sc.matched && !was) now else st.matchedAt)
        if (sc.matched && !was && st.phase == Phase.LIGHTING) { onSound(Sound.MATCH, 1f); log("MATCH ${ev.shot.id} iou=${"%.2f".format(sc.iou)} -> S06") }
    }

    /** [拍下] (T5 -> S07): stars + checks frozen, best kept, shutter + flash; S10 when this makes all eight three-star. */
    fun shoot() {
        if (st.phase != Phase.LIGHTING) return
        val ev = st.eval ?: return
        val sc = ev.score(st.lights, st.hulls)
        val shot = ev.shot
        val wasAll = st.allThree
        val best = if (shot.isRandom) st.best else st.best + (shot.id to maxOf(st.best[shot.id] ?: 0, sc.stars))
        val allNow = Shots.ALL.all { (best[it.id] ?: 0) >= 3 }
        val now = clock()
        _state.value = st.copy(
            phase = Phase.RESULT, best = best, score = sc, dragging = null,
            result = Result(sc.stars, sc.iou, sc.checks, allDone = allNow && (!wasAll || shot.id == "L8"), lights = st.lights),
            shutterAt = now, resultAt = now, allStarsAt = if (allNow && !wasAll) now else st.allStarsAt,
        )
        onSound(Sound.SHUTTER, 1f)
        scope.launch {
            for (i in 0 until sc.stars) {
                kotlinx.coroutines.delay(if (i == 0) 450 else 300)
                onSound(Sound.STAR, 1f + 0.12f * i)
            }
        }
        log("SHOOT ${shot.id} stars=${sc.stars} iou=${"%.2f".format(sc.iou)} checks=${sc.checks} -> ${st.stateId}")
        save()
    }

    /** [下一题] (T7): next scene shot; after L8 a random one; S10's [随机一题] too. */
    fun next() {
        val m = st.mode
        when {
            st.result?.allDone == true || m == Mode.RANDOM || m >= 7 -> { _state.value = st.copy(randomSeed = newSeed()); loadShot(Mode.RANDOM) }
            else -> loadShot(m + 1)
        }
    }

    /** [再摆一次]: back to S02, same shot, the lights stay where they were (design content.startRule). */
    fun again() {
        if (st.phase != Phase.RESULT) return
        _state.value = st.copy(phase = Phase.LIGHTING, result = null)
        log("AGAIN -> S02")
        rescore(force = true)
    }

    companion object {
        /** A drag that moved the light this far (on the orbit) counts as "done it once" for the first-entry tip. */
        const val GUIDE_MOVE_DEG = 5f
        const val NUDGE_DEG = 6f

        /** ◀ / ▶ follow the screen: +az moves +x where cos(az) >= 0 (front half), -x behind the subject. */
        fun nudgeTarget(az: Float, el: Float, dir: Nudge): Pair<Float, Float> {
            val front = kotlin.math.cos(az * Studio.D2R) >= 0f
            return when (dir) {
                Nudge.UP -> az to el + NUDGE_DEG
                Nudge.DOWN -> az to el - NUDGE_DEG
                Nudge.RIGHT -> (az + if (front) NUDGE_DEG else -NUDGE_DEG) to el
                Nudge.LEFT -> (az - if (front) NUDGE_DEG else -NUDGE_DEG) to el
            }
        }
    }

    fun clearProgress() {
        store.clear()
        _state.value = LabState(phase = Phase.LOADING)
        log("CLEAR progress -> S01")
        loadShot(0, Phase.WELCOME)
    }

    // ------------------------------------------------------------------ debug (same paths as the user's)

    /** debug `solve`: every light set to the shot's target through the same setters. */
    fun solve() {
        val shot = st.shot ?: return
        if (st.phase == Phase.WELCOME) beginFromWelcome()
        if (st.phase == Phase.RESULT) again()
        val t = shot.solvedLights()
        // fill / rim first (they may sit near the key's target), then the key
        _state.value = st.copy(lights = Lights(st.lights.key, Lights.FILL_HOME, Lights.RIM_HOME))
        for (id in listOf(LightId.FILL, LightId.RIM, LightId.KEY)) {
            val tl = t[id]
            _state.value = st.copy(lights = st.lights.with(id, tl))
        }
        log("SOLVE ${shot.id}")
        changed(force = true)
    }

    fun setAllThree() {
        _state.value = st.copy(best = Shots.ALL.associate { it.id to 3 })
        save()
    }

    fun onBackground() = save()

    private fun save() {
        val s = st
        if (s.phase == Phase.LOADING) return
        runCatching {
            store.save(Saved(s.best, s.mode, s.randomSeed, s.lights, s.freeSubject, s.freeMaterial, s.freeBackdrop, s.welcomeDone, s.guideDone))
        }
    }
}
