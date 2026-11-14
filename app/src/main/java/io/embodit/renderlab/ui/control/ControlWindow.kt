package io.embodit.renderlab.ui.control

import android.app.ActivityManager
import android.content.Context
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.LocalContentColor
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.design.menu.rememberMenuPositionProvider
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.design.windows.popup.HorizontalPlacement
import com.pico.spatial.ui.design.windows.popup.VerticalPlacement
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.renderlab.BuildConfig
import io.embodit.renderlab.R
import io.embodit.renderlab.domain.Backdrop
import io.embodit.renderlab.domain.Kelvin
import io.embodit.renderlab.domain.LabState
import io.embodit.renderlab.domain.Language
import io.embodit.renderlab.domain.Level
import io.embodit.renderlab.domain.LightId
import io.embodit.renderlab.domain.Lights
import io.embodit.renderlab.domain.MaterialId
import io.embodit.renderlab.domain.Mode
import io.embodit.renderlab.domain.Phase
import io.embodit.renderlab.domain.Settings
import io.embodit.renderlab.domain.Shot
import io.embodit.renderlab.domain.Shots
import io.embodit.renderlab.domain.Studio
import io.embodit.renderlab.domain.SubjectId
import io.embodit.renderlab.domain.orbRgb
import io.embodit.renderlab.platform.Graph
import io.embodit.renderlab.platform.TAG
import io.embodit.renderlab.platform.VOLUME_PERCENT
import io.embodit.renderlab.ui.Page
import io.embodit.renderlab.ui.Ui
import io.embodit.renderlab.ui.studio.checkRes
import io.embodit.renderlab.ui.studio.hintRes
import io.embodit.renderlab.ui.theme.LabColors
import kotlin.system.exitProcess
import kotlinx.coroutines.launch

/**
 * The control window (Planar WindowContainer beside the studio, 600 x 572 dp): all 2D UI, three pages switched by the
 * native SpatialUI TabBar (D8): 打光 / 题卡 / 设置. No page scrolls (D44); every page spreads its content between a
 * header and a last row so the blank outside the content stays inside the window padding.
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val sceneReady by graph.sceneReady.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val nav = LocalSpatialNavigator.current
    DisposableEffect(owner, nav) {
        graph.window.attachPanel(nav)
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(state.phase) { if (state.phase == Phase.RESULT) Ui.page = Page.LIGHT }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${if (Ui.page == Page.LIGHT) state.stateId else Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) { Ui.exitConfirm = false; Ui.clearConfirm = false }
    }
    LaunchedEffect(state.stateId) { Log.i(TAG, "STATE ${state.stateId}") }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        when (Ui.page) {
            Page.LIGHT -> LightPage(state, settings, graph, sceneReady)
            Page.SHOTS -> ShotsPage(state, settings, graph)
            Page.SETTINGS -> SettingsPage(settings, graph)
        }
    }

    // The native TabBar is its own system window: resolve the labels here, in the app-language context, and pass plain
    // strings in (ui_gates tabbar-lang; an English cold start shows three English tabs).
    val tabs = listOf(
        Triple(Page.LIGHT, R.drawable.ic_tab_light, stringResource(R.string.tab_light)),
        Triple(Page.SHOTS, R.drawable.ic_tab_shots, stringResource(R.string.tab_shots)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    )
    NativeTabs(tabs)
}

/** Gap between side-by-side buttons (B31, QA q1-1: >= 16 dp). */
internal val BUTTON_GAP = 16.dp

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, content = content)
}

/** Title (+ one or two lines under it) on the left, the always-visible [声音] on the right (B31 exception, D15). */
@Composable
private fun Header(title: String, tag: String, sub: String?, settings: Settings?, graph: Graph?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(title, "$tag/title", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            if (sub != null) Txt(sub, "$tag/slogan", style = PicoTheme.typography.bodyMedium, color = LabColors.InkSecondary, maxLines = 2)
        }
        if (settings != null && graph != null) {
            Spacer(Modifier.width(12.dp))
            val muted = settings.volume == 0
            SecondaryButton(
                stringResource(if (muted) R.string.btn_muted else R.string.btn_sound), "header/sound",
                onClick = { graph.settings.toggleMute(); Log.i(TAG, "SOUND muted=${graph.settings.value.volume == 0}") },
                icon = if (muted) R.drawable.ic_mute else R.drawable.ic_sound, modifier = Modifier.width(128.dp),
            )
        }
    }
}

@Composable
internal fun isZh(): Boolean = stringResource(R.string.lang_code) == "zh"

@Composable
internal fun shotName(s: Shot): String = if (s.isRandom) stringResource(R.string.shots_random_title) else stringResource(R.string.shot_title, s.id, if (isZh()) s.zh else s.en)

@Composable
internal fun shotTag(s: Shot): String = if (isZh()) s.tagZh else s.tagEn

@Composable
internal fun lightName(id: LightId) = stringResource(when (id) { LightId.KEY -> R.string.light_key; LightId.FILL -> R.string.light_fill; LightId.RIM -> R.string.light_rim })

@Composable
internal fun levelName(l: Level) = stringResource(when (l) { Level.OFF -> R.string.level_off; Level.LOW -> R.string.level_low; Level.MID -> R.string.level_mid; Level.HIGH -> R.string.level_high })

@Composable
internal fun kelvinName(k: Kelvin) = stringResource(when (k) { Kelvin.WARM -> R.string.kelvin_warm; Kelvin.DAY -> R.string.kelvin_day; Kelvin.COOL -> R.string.kelvin_cool })

@Composable
internal fun subjectName(s: SubjectId) = stringResource(when (s) { SubjectId.PLASTER -> R.string.subject_plaster; SubjectId.TEAPOT -> R.string.subject_teapot; SubjectId.VASE -> R.string.subject_vase })

@Composable
internal fun materialName(m: MaterialId) = stringResource(when (m) {
    MaterialId.PLASTER -> R.string.material_plaster; MaterialId.CERAMIC -> R.string.material_ceramic; MaterialId.GOLD -> R.string.material_gold
    MaterialId.RUBBER -> R.string.material_rubber; MaterialId.GLOW -> R.string.material_glow
})

@Composable
internal fun backdropName(b: Backdrop) = stringResource(when (b) { Backdrop.WHITE -> R.string.backdrop_white; Backdrop.BLACK -> R.string.backdrop_black; Backdrop.DUSK -> R.string.backdrop_dusk })

@Composable
private fun Card(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(LabColors.CardFace).padding(16.dp)) { content() }
}

@Composable
private fun Stars(n: Int, size: Int = 26, tag: String) {
    Row {
        for (i in 1..3) Txt("★", "$tag/star$i", style = PicoTheme.typography.titleLarge.copy(fontSize = size.sp), color = if (i <= n) LabColors.Star else LabColors.StarOff)
    }
}

/** A check line: green tick or an empty ring, then the words (design S02 三行检查). */
@Composable
private fun CheckLine(ok: Boolean, text: String, tag: String) {
    Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(if (ok) LabColors.Good else Color.Transparent), contentAlignment = Alignment.Center) {
            if (ok) Txt("✓", "$tag/tick", style = PicoTheme.typography.labelMedium, color = Color.White)
            else Canvas(Modifier.size(22.dp)) { drawCircle(LabColors.InkSecondary, radius = size.minDimension / 2 - 2f, style = Stroke(3f)) }
        }
        Spacer(Modifier.width(10.dp))
        Txt(text, "$tag/line", style = PicoTheme.typography.bodyMedium, maxLines = 1)
    }
}

// ------------------------------------------------------------------ Light page

@Composable
private fun LightPage(st: LabState, settings: Settings, graph: Graph, sceneReady: Boolean) {
    val flow = graph.flow
    when {
        st.phase == Phase.LOADING || !sceneReady -> PageColumn {
            Header(stringResource(R.string.app_name), "loading", null, settings, graph)
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(20.dp))
                Txt(stringResource(R.string.loading_title), "loading/title", style = PicoTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Txt(stringResource(R.string.loading_line), "loading/line", style = PicoTheme.typography.bodyLarge, color = LabColors.InkSecondary)
            }
            PrimaryButton(stringResource(R.string.btn_start), "loading/start", onClick = {}, modifier = Modifier.fillMaxWidth().height(64.dp), enabled = false, icon = R.drawable.ic_play)
        }
        st.phase == Phase.WELCOME -> PageColumn {
            Header(stringResource(R.string.welcome_title), "welcome", null, settings, graph)
            Card(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp)) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    TopDiagram(st, showTarget = true, showMine = true, modifier = Modifier.size(244.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Txt(stringResource(R.string.welcome_l1), "welcome/l1", style = PicoTheme.typography.bodyLarge, maxLines = 4)
                        Txt(stringResource(R.string.welcome_l2), "welcome/l2", style = PicoTheme.typography.bodyLarge, maxLines = 4)
                    }
                }
            }
            PrimaryButton(stringResource(R.string.btn_start), "welcome/start", onClick = { flow.beginFromWelcome() }, modifier = Modifier.fillMaxWidth().height(64.dp), icon = R.drawable.ic_play)
        }
        st.phase == Phase.RESULT -> ResultView(st, settings, graph)
        st.phase == Phase.FREE -> PageColumn {
            Header(stringResource(R.string.free_title), "free", stringResource(R.string.free_line), settings, graph)
            Card(Modifier.fillMaxWidth().height(176.dp)) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    TopDiagram(st, showTarget = false, showMine = true, modifier = Modifier.size(144.dp))
                    Spacer(Modifier.width(16.dp))
                    // gate:allow button-gap text-only column (subject / look / selected light lines), no buttons side by side
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt(subjectName(st.subject), "free/subject", style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Txt(materialName(st.material) + " · " + backdropName(st.backdrop), "free/look", style = PicoTheme.typography.bodyMedium, maxLines = 2)
                        Txt(stringResource(R.string.selected, lightName(st.selected)), "free/selected", style = PicoTheme.typography.bodyMedium)
                    }
                }
            }
            LightSliders(st, graph)
            PrimaryButton(stringResource(R.string.btn_subject), "free/subject-btn", onClick = { flow.cycleSubject() }, modifier = Modifier.fillMaxWidth().height(64.dp), icon = R.drawable.ic_swap)
        }
        else -> PageColumn {
            val shot = st.shot ?: return@PageColumn
            Header(shotName(shot), "light", shotTag(shot), settings, graph)
            AlignCard(st)
            LightSliders(st, graph)
            ShootButton(st.matched, enabled = st.eval != null) { flow.shoot() }
        }
    }
}

@Composable
private fun ShootButton(matched: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val pulse = if (matched) rememberInfiniteTransition(label = "shoot").animateFloat(1f, 1.03f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s").value else 1f
    PrimaryButton(stringResource(R.string.btn_shoot), "light/shoot", onClick = onClick, enabled = enabled, icon = R.drawable.ic_shoot,
        modifier = Modifier.fillMaxWidth().height(64.dp).scale(pulse), container = if (matched) LabColors.Good else null)
}

/** 对准度卡: the top-down light diagram + overlap / hint, the shot's other checks, live stars (design S02). */
@Composable
private fun AlignCard(st: LabState) {
    val sc = st.score
    Card(Modifier.fillMaxWidth().height(176.dp)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            TopDiagram(st, showTarget = true, showMine = true, modifier = Modifier.size(144.dp))
            Spacer(Modifier.width(16.dp))
            // QA q1-2: the stars share the overlap line, so up to three check lines (L5 / L6) fit the 144 dp column
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                val pct = ((sc?.iou ?: 0f) * 100).toInt()
                Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(stringResource(R.string.overlap, pct), "light/overlap", style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Stars(sc?.stars ?: 0, size = 24, tag = "light/stars")
                }
                CheckLine(sc?.in3 == true, stringResource(hintRes(sc?.hint ?: io.embodit.renderlab.domain.Hint.LEFT)), "light/hint")
                val checks = sc?.checks.orEmpty()
                if (checks.isEmpty()) CheckLine(true, stringResource(R.string.only_key), "light/onlykey")
                else checks.forEach { (c, ok) -> CheckLine(ok, stringResource(checkRes(c)), "light/check-${c.name}") }
            }
        }
    }
}

/** The selected light's brightness and colour temperature, both snapping to their stops (design T3). */
@Composable
private fun LightSliders(st: LabState, graph: Graph) {
    val id = st.selected
    val l = st.lights[id]
    val levels = if (id == LightId.KEY) listOf(Level.LOW, Level.MID, Level.HIGH) else Level.entries
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SnapSlider(
            label = stringResource(R.string.slider_brightness, lightName(id)), value = levelName(l.level), tag = "light/brightness",
            stops = levels.size, index = levels.indexOf(l.level).coerceAtLeast(0),
        ) { graph.flow.setLevel(levels[it]) }
        SnapSlider(
            label = stringResource(R.string.slider_color, lightName(id)), value = stringResource(R.string.kelvin_value, kelvinName(l.kelvin), l.kelvin.k), tag = "light/kelvin",
            stops = 3, index = l.kelvin.ordinal,
        ) { graph.flow.setKelvin(Kelvin.entries[it]) }
    }
}

/** Label row + a native Slider that snaps to [stops] positions (B19: 48 dp tall, >= 100 dp wide). */
@Composable
private fun SnapSlider(label: String, value: String, tag: String, stops: Int, index: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        // one line "主光 · 亮度：高" (two Texts in a Row drifted apart vertically in the window, v0.1.0 self-check)
        Txt("$label：$value".replace("：", if (isZh()) "：" else ": "), "$tag/label", style = PicoTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth().height(26.dp))
        Slider(
            value = index.toFloat(),
            onValueChange = { v -> val s = kotlin.math.round(v).toInt().coerceIn(0, stops - 1); if (s != index) onPick(s) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            valueRange = 0f..(stops - 1).toFloat(),
        )
    }
}

/** S07 / S10: the result card (target vs yours, stars lighting up one by one, why) and [再摆一次] [下一题]. */
@Composable
private fun ResultView(st: LabState, settings: Settings, graph: Graph) {
    val r = st.result ?: return
    val shot = st.shot ?: return
    var shown by remember(st.resultAt) { mutableStateOf(0) }
    LaunchedEffect(st.resultAt) { for (i in 1..r.stars) { kotlinx.coroutines.delay(if (i == 1) 450 else 300); shown = i } }
    PageColumn {
        Header(shotName(shot), "result", null, settings, graph)
        Card(Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp)) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                TopDiagram(st.copy(lights = r.lights), showTarget = true, showMine = true, modifier = Modifier.size(236.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stars(shown, size = 34, tag = "result/stars")
                    Txt(if (isZh()) shot.whyZh else shot.whyEn, "result/why", style = PicoTheme.typography.bodyMedium, maxLines = 5)
                    val small = buildString {
                        append(stringResource(R.string.overlap, (r.iou * 100).toInt()))
                        r.checks.forEach { (c, ok) -> append(" · "); append(stringResource(checkRes(c))); append(if (ok) " ✓" else " ·") }
                    }
                    Txt(small, "result/line", style = PicoTheme.typography.bodySmall, color = LabColors.InkSecondary, maxLines = 3)
                    if (r.allDone) Txt(stringResource(R.string.all_done), "result/alldone", style = PicoTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = LabColors.Good, maxLines = 3)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            SecondaryButton(stringResource(R.string.btn_again), "result/again", onClick = { graph.flow.again() }, icon = R.drawable.ic_replay, modifier = Modifier.weight(1f).height(64.dp))
            PrimaryButton(stringResource(if (r.allDone) R.string.btn_random else R.string.btn_next), "result/next", onClick = { graph.flow.next() },
                icon = if (r.allDone) R.drawable.ic_swap else R.drawable.ic_next, modifier = Modifier.weight(1f).height(64.dp))
        }
    }
}

// ------------------------------------------------------------------ Top-down diagram

/**
 * 俯视布光图 (design panels.control: Compose Canvas, zero assets): the wall line, the orbit, the subject in the middle,
 * the shot's target lights as dashed rings with their height, your lights as dots. +Z (toward you) is down.
 */
@Composable
internal fun TopDiagram(st: LabState, showTarget: Boolean, showMine: Boolean, modifier: Modifier, shot: Shot? = st.shot, mine: Lights = st.lights) {
    val you = stringResource(R.string.diag_you)
    val upFmt = stringResource(R.string.diag_up)
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(LabColors.Diagram)) {
        val w = size.width; val h = size.height
        val s = minOf(w, h) / 0.62f
        val cx = w / 2; val cy = h / 2 - h * 0.04f
        fun p(x: Float, z: Float) = Offset(cx + x * s, cy + (z - Studio.C.z) * s)
        val small = w < 200 * density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = (if (small) 10f else 12f) * density }
        // wall
        drawLine(LabColors.DiagramLine, p(Studio.X0, Studio.WZ), p(Studio.X1, Studio.WZ), strokeWidth = 2f * density)
        // orbit
        drawCircle(LabColors.DiagramLine.copy(alpha = 0.5f), radius = Studio.R * s, center = Offset(cx, cy), style = Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * density, 4f * density))))
        // subject
        drawCircle(Color(0xFFEDEAE4), radius = 0.07f * s, center = Offset(cx, cy))
        paint.color = LabColors.DiagramLine.toArgb()
        drawContext.canvas.nativeCanvas.drawText(you, cx, h - 6f * density, paint)
        if (showTarget && shot != null) {
            for (id in LightId.entries) {
                val t = when (id) { LightId.KEY -> shot.key; LightId.FILL -> shot.fill; LightId.RIM -> shot.rim } ?: continue
                val o = Studio.orbPos(t.az, t.el)
                val c = Color(0xFF000000 or orbRgb(id).toLong())
                val at = p(o.x, o.z)
                val r = (if (small) 8f else 11f) * density
                drawCircle(c, radius = r, center = at, style = Stroke(2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * density, 3f * density))))
                paint.color = c.toArgb()
                drawContext.canvas.nativeCanvas.drawText(upFmt.format(t.el.toInt()), at.x, at.y - r - 4f * density, paint)
            }
        }
        if (showMine) {
            for (id in LightId.entries) {
                val l = mine[id]
                if (!l.on) continue
                val o = l.pos
                drawCircle(Color(0xFF000000 or orbRgb(id).toLong()), radius = (if (small) 5f else 6f) * density, center = p(o.x, o.z))
            }
        }
    }
}

// ------------------------------------------------------------------ Shots page

@Composable
private fun ShotsPage(st: LabState, settings: Settings, graph: Graph) {
    val card = st.card.coerceIn(0, Mode.CARDS - 1)
    val shot = if (card < 8) Shots.ALL[card] else null
    PageColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(stringResource(R.string.tab_shots), "shots/title", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Txt(stringResource(R.string.shots_count, card + 1, Mode.CARDS), "shots/count", style = PicoTheme.typography.bodyMedium, color = LabColors.InkSecondary)
            }
            // clearing the stars lives next to them (settings keeps <= 7 controls, B31); its confirm is the settings-page card
            SecondaryButton(stringResource(R.string.btn_clear_ask), "shots/clear", onClick = { Ui.page = Page.SETTINGS; Ui.clearConfirm = true; Log.i(TAG, "CONFIRM CLEAR") }, icon = R.drawable.ic_restart, modifier = Modifier.width(150.dp))
        }
        Card(Modifier.fillMaxWidth().weight(1f).padding(vertical = 14.dp)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                if (shot != null) TopDiagram(st, showTarget = true, showMine = false, modifier = Modifier.width(320.dp).height(180.dp), shot = shot)
                else Box(Modifier.width(320.dp).height(180.dp).clip(RoundedCornerShape(16.dp)).background(LabColors.Diagram), contentAlignment = Alignment.Center) {
                    Txt(if (card == Mode.RANDOM) "?" else "∞", "shots/glyph", style = PicoTheme.typography.titleLarge.copy(fontSize = 56.sp), color = Color(0xFFE6E6E6))
                }
                val title = when (card) { Mode.RANDOM -> stringResource(R.string.shots_random_title); Mode.FREE -> stringResource(R.string.shots_free_title); else -> shotName(shot!!) }
                val tag = when (card) { Mode.RANDOM -> stringResource(R.string.shots_random_tag); Mode.FREE -> stringResource(R.string.shots_free_tag); else -> shotTag(shot!!) }
                Txt(title, "shots/title", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Txt(tag, "shots/tag", style = PicoTheme.typography.bodyMedium, color = LabColors.InkSecondary, maxLines = 2, align = TextAlign.Center)
                if (shot != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(stringResource(R.string.stars_best), "shots/best", style = PicoTheme.typography.labelLarge, color = LabColors.InkSecondary)
                    Spacer(Modifier.width(8.dp))
                    Stars(st.best[shot.id] ?: 0, tag = "shots/stars")
                } else Txt(stringResource(if (card == Mode.RANDOM) R.string.shots_new_seed else R.string.shots_no_score), "shots/info", style = PicoTheme.typography.labelLarge, color = LabColors.InkSecondary)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.btn_prev), "shots/prev", onClick = { graph.flow.setCard(card - 1) }, icon = R.drawable.ic_prev, modifier = Modifier.width(132.dp).height(64.dp))
            PrimaryButton(stringResource(R.string.btn_start_shot), "shots/start", onClick = { graph.flow.startCard(card); Ui.page = Page.LIGHT }, icon = R.drawable.ic_play, modifier = Modifier.weight(1f).height(64.dp))
            SecondaryButton(stringResource(R.string.btn_next_card), "shots/next", onClick = { graph.flow.setCard(card + 1) }, icon = R.drawable.ic_next_card, modifier = Modifier.width(132.dp).height(64.dp))
        }
    }
}

// ------------------------------------------------------------------ Settings page

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    val context = LocalContext.current
    if (Ui.exitConfirm) {
        ConfirmPage(stringResource(R.string.confirm_exit), stringResource(R.string.confirm_exit_line), stringResource(R.string.btn_exit_yes),
            R.drawable.ic_exit, "exit", onYes = { exitApp(context, graph) }, onNo = { Ui.exitConfirm = false })
        return
    }
    if (Ui.clearConfirm) {
        ConfirmPage(stringResource(R.string.confirm_clear), stringResource(R.string.confirm_clear_line), stringResource(R.string.btn_clear_yes),
            R.drawable.ic_restart, "clear", onYes = { graph.flow.clearProgress(); Ui.clearConfirm = false; Ui.page = Page.LIGHT }, onNo = { Ui.clearConfirm = false })
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Txt(stringResource(R.string.settings_title), "settings/title", style = PicoTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(34.dp)) {
            SettingRow(stringResource(R.string.settings_volume), "settings/volume") {
                Slider(
                    value = settings.volume.toFloat(),
                    onValueChange = { v -> val s = kotlin.math.round(v).toInt().coerceIn(0, 3); if (s != settings.volume) graph.settings.setVolume(s) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    valueRange = 0f..3f,
                )
                Spacer(Modifier.width(12.dp))
                Txt(if (settings.volume == 0) stringResource(R.string.volume_mute) else "${VOLUME_PERCENT[settings.volume]}%", "settings/volume-pct",
                    style = PicoTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(64.dp))
            }
            SettingRow(stringResource(R.string.settings_language), "settings/lang") { LanguagePicker(settings, graph) }
            // Full Space (D44): bring the studio and the window back in front of you; [清除进度] moved to the Shots page (B31 <= 7)
            SettingRow(stringResource(R.string.settings_recall), "settings/recall") {
                Spacer(Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_recall), "settings/recall-btn", onClick = { graph.recall() }, icon = R.drawable.ic_restart, modifier = Modifier.width(200.dp).height(64.dp))
            }
            SettingRow(stringResource(R.string.settings_exit), "settings/exit") {
                Spacer(Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_exit_ask), "settings/exit-btn", onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT") }, icon = R.drawable.ic_exit, danger = true, modifier = Modifier.width(200.dp).height(64.dp))
            }
        }
        Spacer(Modifier.height(32.dp))
        Txt(stringResource(R.string.settings_note1, BuildConfig.VERSION_NAME), "settings/note1", style = PicoTheme.typography.bodySmall, color = LabColors.InkSecondary)
        Txt(stringResource(R.string.settings_note2), "settings/note2", style = PicoTheme.typography.bodySmall, color = LabColors.InkSecondary, maxLines = 2)
    }
}

@Composable
private fun SettingRow(label: String, tag: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(150.dp))
        content()
    }
}

/** 语言: one native dropdown (design: 跟随系统 / 中文 / English), so the settings page stays at 7 controls (B31). */
@Composable
private fun androidx.compose.foundation.layout.RowScope.LanguagePicker(settings: Settings, graph: Graph) {
    var open by remember { mutableStateOf(false) }
    val names = mapOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en))
    Spacer(Modifier.weight(1f))
    Box {
        SecondaryButton(names.getValue(settings.language) + "  ▾", "settings/lang-btn", onClick = { open = !open }, modifier = Modifier.width(200.dp).height(64.dp))
        if (open) {
            Menu(
                positionProvider = rememberMenuPositionProvider(horizontalPlacement = HorizontalPlacement.alignStart(), verticalPlacement = VerticalPlacement.below()),
                onDismissRequest = { open = false },
            ) {
                for ((lang, name) in names) {
                    MenuItem(title = { Txt(name + if (lang == settings.language) "  ✓" else "", "settings/lang-item", style = PicoTheme.typography.bodyLarge) }, onClick = {
                        graph.settings.update { it.copy(language = lang) }; open = false; Log.i(TAG, "LANG $lang")
                    })
                }
            }
        }
    }
}

@Composable
private fun ConfirmPage(q: String, line: String, yes: String, icon: Int, tag: String, onYes: () -> Unit, onNo: () -> Unit) {
    // QA q1-3 (D44 blank <= 25 %): the card fills the page and spreads icon / question / line / buttons over its height,
    // so the blank outside the content is the window + card padding only (~17 %) and no inner band passes 20 %.
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(LabColors.CardFace).padding(horizontal = 32.dp, vertical = 38.dp),
        verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(104.dp), tint = LabColors.Ink)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Txt(q, "confirm/$tag", style = PicoTheme.typography.titleLarge.copy(fontSize = 34.sp, lineHeight = 44.sp), color = LabColors.Ink, maxLines = 2, align = TextAlign.Center)
            Txt(line, "confirm/line", style = PicoTheme.typography.bodyLarge.copy(fontSize = 22.sp, lineHeight = 30.sp), color = LabColors.InkSecondary, maxLines = 3, align = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            SecondaryButton(stringResource(R.string.btn_cancel), "confirm/cancel", onClick = onNo, modifier = Modifier.weight(1f).height(80.dp))
            SecondaryButton(yes, "confirm/yes", danger = true, onClick = onYes, icon = icon, modifier = Modifier.weight(1f).height(80.dp))
        }
    }
}

/** D26 / D44 exit: save, release, close the window and the Stage, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close window + closeStage + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.tracking.stop() }
    graph.scope.launch {
        runCatching { graph.window.closeAll() }
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        }
        Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 400)
    }
}

/** The native SpatialUI TabBar (D8); labels arrive already resolved in the app language. */
@Composable
private fun NativeTabs(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                // QA K6 / B19: the capsule only draws mainContent (supportContent never shows), so the word goes under the icon
                mainContent = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
                        // the word follows the item's content colour like the icon does (white on the selected capsule)
                        val fg = LocalContentColor.current
                        LaunchedEffect(fg) { Log.i(TAG, "TABCOLOR ${page.name} selected=${Ui.page == page} fg=#%08X".format(fg.toArgb())) }
                        Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelSmall, color = fg, maxLines = 1)
                    }
                },
            )
        }
    }
}

@Suppress("unused")
private val unusedDp: Dp = 0.dp
