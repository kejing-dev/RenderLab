package io.embodit.renderlab.ui.studio

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.renderlab.R
import io.embodit.renderlab.domain.Check
import io.embodit.renderlab.domain.Hint
import io.embodit.renderlab.domain.LabFlow
import io.embodit.renderlab.domain.LabState
import io.embodit.renderlab.domain.LightId
import io.embodit.renderlab.domain.MaterialId
import io.embodit.renderlab.domain.Phase
import io.embodit.renderlab.domain.V3
import io.embodit.renderlab.domain.Place
import io.embodit.renderlab.domain.Q4
import io.embodit.renderlab.domain.Studio
import io.embodit.renderlab.domain.raySphere
import io.embodit.renderlab.platform.Graph
import io.embodit.renderlab.platform.TAG
import io.embodit.renderlab.ui.studio.input.BoardInputConfig
import io.embodit.renderlab.ui.studio.input.BoardInputListener
import io.embodit.renderlab.ui.studio.input.BoardInputLog
import io.embodit.renderlab.ui.studio.input.InputSource
import io.embodit.renderlab.ui.studio.input.Offset3M
import io.embodit.renderlab.ui.studio.input.TargetResolver
import io.embodit.renderlab.ui.studio.input.boardInput
import io.embodit.renderlab.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Runs the volume's per-frame work inside the ECS (never in Compose). */
class StudioFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.driver?.frame()
        SceneHolder.scene?.onFrame(context.deltaTime)
    }
}

internal object SceneHolder {
    @Volatile var scene: StudioScene? = null
    @Volatile var driver: StudioDriver? = null
    @Volatile var testF = false
    @Volatile var levelWordsNow = ""
}

/**
 * Per Stage frame (ECS): head pose -> studio placement (first valid pose and [叫回面前], yaw only) and the window's level
 * gate; controller -> system-drawn ray, the app's own orb pick and trigger drag (studio-local maths, see [ControllerDrag]).
 */
internal class StudioDriver(private val graph: Graph, private val scene: StudioScene, private val levelWords: () -> String) {
    val ctrl = ControllerDrag(graph.flow)
    private var lastHeadLog = 0L

    fun frame() {
        val now = android.os.SystemClock.elapsedRealtime()
        val head = graph.tracking.head.pose()
        if (head != null && graph.recallStudio) {
            graph.recallStudio = false
            graph.studioPose = Place.studio(head.first, head.second)
            Log.i(TAG, "PLACE studio head=${head.first} pitch=${"%.1f".format(Place.pitchDeg(head.second))} -> ${graph.studioPose!!.p}")
        }
        if (head != null && now - lastHeadLog > 5000) { lastHeadLog = now; Log.i(TAG, "HEAD ${head.first} pitch=${"%.1f".format(Place.pitchDeg(head.second))}") }
        graph.window.stepGate(now, head?.let { Place.pitchDeg(it.second) })
        scene.studioPose = graph.studioPose
        // the level plate: on the line of sight (pitch clamped to -12°..40°, above the studio), 0.75 m, upright (D9)
        scene.levelHint = if (graph.window.levelHint && head != null) Place.levelPlate(head.first, head.second).let { it.p to it.q } else null
        scene.levelWords = levelWords()
        val pose = graph.studioPose
        val sample = graph.tracking.controller.sample()
        scene.rayView = if (pose == null || sample == null) { ctrl.lost(); null } else ctrl.step(sample, pose, graph.window.panelOpen) { graph.window.open("strip") }
    }
}

/**
 * The studio in a Mixed Stage (Full Space). Asks for the control window, builds the studio, and routes every pinch /
 * trigger / drag on an orb, a material ball or the wall to [LabFlow] through the factory board-input template (D50:
 * hand ray, controller ray and eye-hand alike; never the head pose, D1).
 */
@Composable
fun StudioScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    remember(context) { Tex.typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.noto_sans_semibold) }.getOrNull(); 0 }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val scene = remember { StudioScene(scope) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.open("start")
        onDispose { graph.window.detach() }
    }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if (graph.window.panelState == "ON_DESTROY") graph.window.open("resume")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val levelWords = remember(context, settings.language) { context.localized(settings.language).getString(R.string.look_level) }
    val driver = remember(scene) { StudioDriver(graph, scene) { SceneHolder.levelWordsNow } }
    SceneHolder.levelWordsNow = levelWords
    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.driver = driver
        graph.tracking.start()
        registerSystem<StudioFrameSystem>()
        onDispose {
            unregisterSystem<StudioFrameSystem>()
            graph.tracking.stop()
            if (SceneHolder.driver === driver) SceneHolder.driver = null
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            graph.sceneReady.value = false
            scene.dispose()
        }
    }
    // 1 / 3 / 5 s placement log (build.md 2026-10-29: what is where after start)
    LaunchedEffect(scene) {
        for (t in listOf(1000L, 2000L, 2000L)) { delay(t); Log.i(TAG, "PLACE ${scene.evidence()}") }
    }

    val loc = remember(context, settings.language) { context.localized(settings.language) }
    val texts = sceneTexts(state, graph.window.panelOpen) { id, args -> loc.getString(id, *args) }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .boardInput(remember { StudioTargets() }, remember(graph, scene) { StudioListener(graph.flow, scene, { v -> graph.studioPose?.let { Place.dirToLocal(it, v) } ?: v }, { driver.ctrl.owns() }, { graph.window.open("strip") }) }, BoardInputConfig(dragStartM = 0.012f, logTag = TAG)),
        initial = { content, _ ->
            content.addEntity(scene.world)
            val t0 = android.os.SystemClock.elapsedRealtime()
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); delay(10_000) }
            withContext(NonCancellable) { runCatching { scene.install() }.onFailure { Log.e(TAG, "SCENE failed", it) } }
            graph.sceneReady.value = true
            Log.i(TAG, "SCENE ready in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        },
        update = { _, _ ->
            scene.apply(state, graph.window.panelOpen, texts)
        },
    )
}

private var regionLogged = -1f
private fun regionLog(halfY: Float) {
    if (abs(halfY - regionLogged) < 1e-4f) return
    regionLogged = halfY
    Log.i(TAG, "SCENE region halfY=$halfY (design 0.32)")
}

/** The hint strip and orb names in the app language (D38: the strip says the same as the window's first check line). */
internal fun sceneTexts(st: LabState, panelOpen: Boolean, s: (Int, Array<Any>) -> String): SceneTexts {
    val zh = s(R.string.lang_code, emptyArray()) == "zh"
    val names = mapOf(LightId.KEY to s(R.string.light_key, emptyArray()), LightId.FILL to s(R.string.light_fill, emptyArray()), LightId.RIM to s(R.string.light_rim, emptyArray()))
    val strip = when {
        !panelOpen -> s(R.string.strip_panel_closed, emptyArray())
        st.phase == Phase.LOADING -> s(R.string.loading_title, emptyArray())
        st.phase == Phase.WELCOME -> s(R.string.strip_welcome, emptyArray())
        st.phase == Phase.FREE -> s(R.string.free_title, emptyArray())
        st.phase == Phase.RESULT -> s(R.string.strip_result, arrayOf(st.result?.stars ?: 0))
        st.matched -> s(R.string.strip_matched, emptyArray())
        else -> stripHint(st, s)
    }
    return SceneTexts(strip, names, zh, SceneHolder.testF)
}

/** First hint: the key light's direction while the shadow is out, then the first failed check. */
internal fun stripHint(st: LabState, s: (Int, Array<Any>) -> String): String {
    val sc = st.score ?: return s(R.string.strip_welcome, emptyArray())
    if (sc.hint != Hint.IN) return s(R.string.strip_move, arrayOf(s(hintRes(sc.hint), emptyArray())))
    val bad = sc.checks.firstOrNull { !it.second }?.first ?: return s(R.string.strip_matched, emptyArray())
    return s(checkFixRes(bad), emptyArray())
}

fun hintRes(h: Hint): Int = when (h) {
    Hint.IN -> R.string.hint_in
    Hint.LEFT -> R.string.hint_left
    Hint.RIGHT -> R.string.hint_right
    Hint.HIGHER -> R.string.hint_higher
    Hint.LOWER -> R.string.hint_lower
    Hint.TOWARD -> R.string.hint_toward
    Hint.BACK -> R.string.hint_back
}

fun checkRes(c: Check): Int = when (c) {
    Check.KEY_WARM -> R.string.check_key_warm
    Check.KEY_LOW -> R.string.check_key_low
    Check.FILL_LOW -> R.string.check_fill_low
    Check.FILL_COOL -> R.string.check_fill_cool
    Check.RIM -> R.string.check_rim
}

fun checkFixRes(c: Check): Int = when (c) {
    Check.KEY_WARM -> R.string.fix_key_warm
    Check.KEY_LOW -> R.string.fix_key_low
    Check.FILL_LOW -> R.string.fix_fill_low
    Check.FILL_COOL -> R.string.fix_fill_cool
    Check.RIM -> R.string.fix_rim
}

/** Hit entity (or an ancestor) -> the holder name the flow knows; floor, wall face, subject and labels take no hits. */
internal class StudioTargets : TargetResolver<String> by TargetResolver.byName(::targetOf) {
    companion object {
        fun targetOf(name: String): String? = name.takeIf { StudioNames.hitOf(it) != StudioNames.NoHit }
        fun resolvePath(path: List<String>): String? = io.embodit.renderlab.ui.studio.input.resolvePath(path, ::targetOf)
    }
}

/** Every resolved tap / drag goes through [routeTap] / [LabFlow] (the same calls the window and the debug broadcast make). */
internal class StudioListener(
    private val flow: LabFlow,
    private val scene: StudioScene?,
    /** World (Stage) hand travel -> studio-local travel (the studio is turned to face you). */
    private val toLocal: (V3) -> V3 = { it },
    /** The app's own controller ray is handling this trigger press: ignore the system's copy of it. */
    private val controllerOwned: () -> Boolean = { false },
    private val openWindow: () -> Unit = {},
) : BoardInputListener<String> {
    private var dragging = false

    private fun skip(source: InputSource) = source == InputSource.CONTROLLER && controllerOwned()

    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        if (skip(source)) return
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target -> ${StudioNames.hitOf(target)}")
        routeTap(flow, target, openWindow)
    }

    override fun onDragStart(target: String, source: InputSource): Boolean {
        if (skip(source)) return false
        BoardInputLog.write(TAG, "INPUT drag src=${source.label} target=$target -> ${StudioNames.hitOf(target)}")
        val h = StudioNames.hitOf(target) as? StudioNames.OrbHit ?: run { routeTap(flow, target, openWindow); return true }
        dragging = flow.dragStart(h.id)
        return true
    }

    override fun onDragMove(totalM: Offset3M) { if (dragging) flow.dragMove(toLocal(V3(totalM.x, totalM.y, totalM.z))) }

    override fun onDragEnd() { if (dragging) flow.dragEnd(); dragging = false }

    override fun onDragCancel() { if (dragging) flow.dragCancel(); dragging = false }

    /** D50 hover: a hint only, never a state change. */
    override fun onHover(target: String?, source: InputSource) { scene?.setHover(target) }
}

/** Holder name -> the one flow call. */
internal fun routeTap(flow: LabFlow, name: String?, openWindow: () -> Unit = {}) {
    when (val h = StudioNames.hitOf(name)) {
        is StudioNames.OrbHit -> flow.tapOrb(h.id)
        is StudioNames.SwatchHit -> flow.setMaterial(MaterialId.entries[h.index])
        StudioNames.WallHit -> flow.cycleBackdrop()
        StudioNames.StripHit -> { Log.i(TAG, "STRIP tap -> open window"); openWindow() }
        StudioNames.NoHit -> Unit
    }
}

/**
 * The app's own controller ray (D41): pick in studio-local space (orbs, and in free light the material balls and the
 * upper wall; the strip while the window is closed); trigger press on an orb grabs it, the orb follows the ray at its
 * grab distance (put back on the orb sphere, clamped), release drops it where it is (D1).
 */
internal class ControllerDrag(private val flow: LabFlow) {
    private var wasPressed = false
    private var grabbing: LightId? = null
    private var grabT = 0f
    private var ownUntil = 0L
    private var pressOwned = false

    /** True while this press (or the 300 ms after it) was handled here. */
    fun owns(): Boolean = pressOwned || android.os.SystemClock.uptimeMillis() < ownUntil

    fun lost() { if (grabbing != null) { flow.dragEnd(); grabbing = null }; wasPressed = false; pressOwned = false }

    /** Returns the pick (Stage space origin, end, whether it hits something of ours); not drawn, the system draws the ray. */
    fun step(sample: io.embodit.renderlab.platform.ControllerSample, pose: io.embodit.renderlab.domain.Pose, panelOpen: Boolean, openWindow: () -> Unit): Triple<V3, V3, Boolean> {
        val o = Place.toLocal(pose, sample.origin)
        val d = Place.dirToLocal(pose, sample.dir).norm()
        val st = flow.state.value
        var best: Pair<String, Float>? = null
        fun consider(name: String, t: Float?) { if (t != null && (best == null || t < best!!.second)) best = name to t }
        val touch = st.phase == Phase.LIGHTING || st.phase == Phase.FREE || st.phase == Phase.WELCOME
        if (touch) for (id in LightId.entries) consider(StudioNames.orb(id), raySphere(o, d, st.lights[id].pos, Studio.HIT_R))
        if (st.phase == Phase.FREE) {
            for (i in 0 until 5) consider(StudioNames.swatch(i), raySphere(o, d, V3(Studio.SWATCH_X[i], Studio.SWATCH_Y, Studio.SWATCH_Z), Studio.SWATCH_HIT_R))
            if (d.z < -1e-4f) { val t = (Studio.WZ - o.z) / d.z; val p = o + d * t; if (t > 0 && p.y > Studio.WALL_TAP_MIN_Y && p.y < Studio.WALL_TOP && kotlin.math.abs(p.x) < 0.33f) consider(StudioNames.WALL, t) }
        }
        if (!panelOpen && d.z < -1e-4f) { val t = (Studio.STRIP_C.z - o.z) / d.z; val p = o + d * t; if (t > 0 && kotlin.math.abs(p.x) < Studio.STRIP_W / 2 && kotlin.math.abs(p.y - Studio.STRIP_C.y) < Studio.STRIP_H) consider(StudioNames.STRIP, t) }
        val pressed = sample.pressed || sample.trigger > 0.6f
        val hit = best
        if (pressed && !wasPressed) {
            if (hit != null) {
                pressOwned = true
                BoardInputLog.write(TAG, "INPUT ctrl-ray press target=${hit.first} -> ${StudioNames.hitOf(hit.first)}")
                val h = StudioNames.hitOf(hit.first)
                if (h is StudioNames.OrbHit && flow.dragStart(h.id)) { grabbing = h.id; grabT = hit.second } else routeTap(flow, hit.first, openWindow)
            }
        }
        if (pressed && grabbing != null) flow.dragTo(o + d * grabT)
        if (!pressed && wasPressed) {
            if (grabbing != null) { flow.dragEnd(); grabbing = null }
            if (pressOwned) ownUntil = android.os.SystemClock.uptimeMillis() + 300
            pressOwned = false
        }
        wasPressed = pressed
        val t = if (grabbing != null) grabT else hit?.second ?: 1.2f
        return Triple(sample.origin, sample.origin + sample.dir * t, hit != null || grabbing != null)
    }
}
