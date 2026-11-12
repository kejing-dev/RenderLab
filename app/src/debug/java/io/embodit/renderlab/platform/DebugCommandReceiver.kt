package io.embodit.renderlab.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.renderlab.domain.Kelvin
import io.embodit.renderlab.domain.Language
import io.embodit.renderlab.domain.Level
import io.embodit.renderlab.domain.LightId
import io.embodit.renderlab.domain.MaterialId
import io.embodit.renderlab.domain.Mode
import io.embodit.renderlab.domain.Phase
import io.embodit.renderlab.domain.Shots
import io.embodit.renderlab.domain.Studio
import io.embodit.renderlab.domain.V3
import io.embodit.renderlab.ui.Page
import io.embodit.renderlab.ui.Ui
import io.embodit.renderlab.ui.studio.LightLevels
import io.embodit.renderlab.ui.studio.SceneHolder
import io.embodit.renderlab.ui.studio.StudioListener
import io.embodit.renderlab.ui.studio.StudioTargets
import io.embodit.renderlab.ui.studio.input.InputSource
import io.embodit.renderlab.ui.studio.input.resolveUp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only. Every command goes through the same LabFlow calls the volume and the window use (D29).
 *
 *   adb shell am broadcast -a io.embodit.renderlab.DEBUG -p io.embodit.renderlab --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S01|S02|S03|S06|S07|S07ONE|S08|S09|S09GLOW|S10|S11|S12|CLEAR · shot L1..L8|random|free · solve · shoot · next · again
 *   orb "<key|fill|rim> <az> <el>" (the drag path: dragStart / dragMove / dragEnd) · hold "<key> <az> <el>" (drag held, S03)
 *   tap <orb:key|sw:3|wall> · replay "<holder> [hand-ray|eye-hand|controller]" · level <off|low|mid|high> · kelvin <warm|day|cool>
 *   select <key|fill|rim> · subject · material <0-4> · backdrop · allstars · page light|shots|settings · card <0-9>
 *   recall (studio + window back in front) · lang zh|en|system · volume 0-3 · mute · exitask · clearask · no · closepanel · reopen · s00hold on|off · testf on|off
 *   keylevels "<low> <mid> <high>" · pointscale <k> · n2 "<on|off>" · dump · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        val f = a.split(" ").filter { it.isNotBlank() }
        Log.i(TAG, "CMD $cmd $a")
        fun later(ms: Long, block: () -> Unit) { graph.scope.launch { delay(ms); block() } }
        fun st() = flow.state.value
        fun light(s: String) = LightId.entries.first { it.name.equals(s, true) }
        fun drag(id: LightId, az: Float, el: Float, hold: Boolean) {
            flow.dragStart(id)
            val from = st().lights[id].pos
            val to = Studio.orbPos(az, el)
            val steps = 12
            graph.scope.launch {
                for (i in 1..steps) { delay(16); flow.dragMove((to - from) * (i / steps.toFloat())) }
                if (!hold) flow.dragEnd()
            }
        }
        fun shot(id: String) = when (id.lowercase()) {
            "random" -> flow.startCard(Mode.RANDOM)
            "free" -> flow.startCard(Mode.FREE)
            else -> flow.startCard(Shots.ALL.indexOfFirst { it.id.equals(id, true) }.coerceAtLeast(0))
        }
        when (cmd) {
            "goto" -> {
                Ui.page = Page.LIGHT
                when (f.getOrElse(0) { "" }.uppercase()) {
                    "S01" -> flow.clearProgress()
                    "S02" -> shot(f.getOrElse(1) { "L3" })
                    "S03" -> { shot("L1"); later(600) { drag(LightId.KEY, -35f, 45f, hold = true) } }
                    "S06" -> { shot("L3"); later(600) { flow.solve() } }
                    "S07" -> { shot("L3"); later(600) { flow.solve(); later(200) { flow.shoot() } } }
                    "S07ONE" -> { shot("L3"); later(600) { flow.place(LightId.KEY, -48f, 30f); later(200) { flow.shoot() } } }
                    "S08" -> Ui.page = Page.SHOTS
                    "S09" -> { flow.startCard(Mode.FREE); later(300) { flow.tapOrb(LightId.FILL); flow.tapOrb(LightId.RIM); flow.tapOrb(LightId.KEY) } }
                    "S09GLOW" -> { flow.startCard(Mode.FREE); later(300) { while (st().subject.name != "VASE") flow.cycleSubject(); flow.setMaterial(MaterialId.GLOW); while (st().backdrop.name != "BLACK") flow.cycleBackdrop() } }
                    "S10" -> { flow.setAllThree(); shot("L8"); later(600) { flow.solve(); later(200) { flow.shoot() } } }
                    "S11" -> Ui.page = Page.SETTINGS
                    "S12" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
                    "CLEAR" -> { Ui.page = Page.SETTINGS; Ui.clearConfirm = true }
                    else -> Log.w(TAG, "goto: unknown state $a")
                }
            }
            "shot" -> { shot(a); Ui.page = Page.LIGHT }
            "solve" -> flow.solve()
            "shoot" -> flow.shoot()
            "next" -> flow.next()
            "again" -> flow.again()
            "orb" -> drag(light(f[0]), f[1].toFloat(), f[2].toFloat(), hold = false)
            "hold" -> drag(light(f[0]), f[1].toFloat(), f[2].toFloat(), hold = true)
            "release" -> flow.dragEnd()
            "place" -> flow.place(light(f[0]), f[1].toFloat(), f[2].toFloat())
            "select" -> flow.tapOrb(light(a))
            "level" -> flow.setLevel(Level.valueOf(a.uppercase()))
            "kelvin" -> flow.setKelvin(Kelvin.valueOf(a.uppercase()))
            "subject" -> flow.cycleSubject()
            "material" -> flow.setMaterial(MaterialId.entries[a.toInt()])
            "backdrop" -> flow.cycleBackdrop()
            "allstars" -> flow.setAllThree()
            "tap" -> {
                val l = StudioListener(flow, SceneHolder.scene)
                l.onTap(StudioTargets.targetOf(a), InputSource.UNKNOWN, null)
            }
            "replay" -> {
                // board-input 必带单测 4: resolve the real entities by the same resolver, then go through the listener
                val target = f.getOrNull(0).orEmpty()
                val src = InputSource.entries.firstOrNull { it.label == f.getOrNull(1) } ?: InputSource.CONTROLLER
                val ents = SceneHolder.scene?.replayEntities(target).orEmpty()
                val l = StudioListener(flow, SceneHolder.scene)
                for (e in ents) {
                    val (hit, path) = resolveUp(e, StudioTargets())
                    Log.i(TAG, "REPLAY src=${src.label} hit=$hit path=$path")
                    l.onTap(hit, src, null)
                }
                if (ents.isEmpty()) Log.w(TAG, "REPLAY no entity named $target")
            }
            "page" -> Ui.page = when (a) { "shots" -> Page.SHOTS; "settings" -> Page.SETTINGS; else -> Page.LIGHT }
            "card" -> { flow.setCard(a.toInt()); Ui.page = Page.SHOTS }
            "lang" -> graph.settings.update { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "volume" -> graph.settings.setVolume(a.toInt())
            "mute" -> graph.settings.toggleMute()
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
            "clearask" -> { Ui.page = Page.SETTINGS; Ui.clearConfirm = true }
            "no" -> { Ui.exitConfirm = false; Ui.clearConfirm = false }
            "closepanel" -> graph.window.closeControlOnly()
            "reopen" -> graph.window.reopen()
            "recall" -> graph.recall()
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "testf" -> SceneHolder.testF = on
            "keylevels" -> { LightLevels.keyLow = f[0].toFloat(); LightLevels.keyMid = f[1].toFloat(); LightLevels.keyHigh = f[2].toFloat(); flow.setLevel(st().lights.key.level) }
            "glowk" -> { LightLevels.glowK = a.toFloat(); flow.setLevel(st().lights[st().selected].level) }
            "pointscale" -> { LightLevels.pointScale = a.toFloat(); flow.setLevel(st().lights[st().selected].level) }
            // needsCheck N2: key dimmed to its floor, a fill orb right in front at high -> compare the subject's brightness with n2 off
            "n2" -> {
                flow.startCard(Mode.FREE)
                later(400) {
                    while (st().backdrop.name != "BLACK") flow.cycleBackdrop()
                    while (st().subject.name != "PLASTER") flow.cycleSubject()
                    flow.setMaterial(MaterialId.PLASTER)
                    LightLevels.keyLow = 0.0001f
                    flow.tapOrb(LightId.KEY); flow.setLevel(Level.LOW)
                    flow.place(LightId.RIM, 130f, 70f)
                    flow.tapOrb(LightId.FILL); flow.place(LightId.FILL, 0f, 20f)
                    flow.setLevel(if (on) Level.HIGH else Level.OFF)
                    Log.i(TAG, "N2 fill=${if (on) "HIGH" else "OFF"} point=${LightLevels.point(LightId.FILL, Level.HIGH)} ${SceneHolder.scene?.evidence()}")
                }
            }
            // writes the hint-strip texture for <arg> to files/striptex.png (v0.1.1: check the "italic" English strip)
            "striptex" -> runCatching {
                val f = java.io.File(context.getExternalFilesDir(null), "striptex.png")
                f.outputStream().use { io.embodit.renderlab.ui.studio.Tex.strip(a).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                Log.i(TAG, "STRIPTEX ${f.absolutePath}")
            }.onFailure { Log.w(TAG, "STRIPTEX failed", it) }
            "dump" -> Log.i(TAG, "DUMP state=${st().stateId} phase=${st().phase} mode=${st().mode} shot=${st().shot?.id} lights=${st().lights} score=${st().score} best=${st().best} page=${Ui.page} panel=${graph.window.panelState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence()}")
            else -> Log.w(TAG, "unknown cmd $cmd")
        }
        if (st().phase == Phase.LOADING) Log.i(TAG, "CMD note: still loading")
        @Suppress("UNUSED_VARIABLE") val unused = V3.ZERO
    }
}
