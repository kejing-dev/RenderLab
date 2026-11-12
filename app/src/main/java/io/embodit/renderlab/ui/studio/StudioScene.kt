package io.embodit.renderlab.ui.studio

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.PointLightComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Vector3
import io.embodit.renderlab.domain.Backdrop
import io.embodit.renderlab.domain.Hulls
import io.embodit.renderlab.domain.LabState
import io.embodit.renderlab.domain.Level
import io.embodit.renderlab.domain.LightId
import io.embodit.renderlab.domain.MaterialId
import io.embodit.renderlab.domain.MeshBuilder
import io.embodit.renderlab.domain.MeshData
import io.embodit.renderlab.domain.P2
import io.embodit.renderlab.domain.Phase
import io.embodit.renderlab.domain.Q4
import io.embodit.renderlab.domain.Shadow
import io.embodit.renderlab.domain.Shapes
import io.embodit.renderlab.domain.Shots
import io.embodit.renderlab.domain.Studio
import io.embodit.renderlab.domain.Studio.FY
import io.embodit.renderlab.domain.Studio.WZ
import io.embodit.renderlab.domain.SubjectId
import io.embodit.renderlab.domain.V3
import io.embodit.renderlab.domain.orbRgb
import io.embodit.renderlab.domain.shade
import io.embodit.renderlab.domain.wallShadow
import io.embodit.renderlab.platform.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/** Words the volume shows, already in the app language (resolved by StudioScreen). */
internal data class SceneTexts(val strip: String, val names: Map<LightId, String>, val zh: Boolean, val testF: Boolean = false)

/** Key light intensity per level and the point-light intensity per level (calibrated in the emulator, needsCheck N2). */
internal object LightLevels {
    /** Emissive is scaled like the lights: 1.6 x #7FFFD4 clipped to white on the emulator, 0.35 reads as glaze (v0.1.0 self-check). */
    @Volatile var glowK = 0.35f
    // N2 (2026-11-11, emulator-5560): the SDK light units are lux-like: 1.45 changed nothing, 10000 lights the plaster
    // clearly; point lights do work in the Shared Space volume at ~1000-3000 (see apps/render-lab/build/n2-*.png)
    // exposure on the white studio (L3, emulator): key 2800 -> subject mean 211, wall 177, no pixel > 245 (B24)
    @Volatile var keyLow = 800f
    @Volatile var keyMid = 1600f
    @Volatile var keyHigh = 2800f
    @Volatile var pointScale = 1f
    val FILL = floatArrayOf(0f, 300f, 700f, 1400f)
    val RIM = floatArrayOf(0f, 800f, 1600f, 2800f)
    const val ATTEN = 0.8f

    fun key(l: Level) = when (l) { Level.LOW, Level.OFF -> keyLow; Level.MID -> keyMid; Level.HIGH -> keyHigh }
    fun point(id: LightId, l: Level) = (if (id == LightId.FILL) FILL else RIM)[l.ordinal] * pointScale
}

/**
 * Every entity of the studio volume (design entities3d). Compose hands over [LabState] through [apply]; everything that
 * animates (orb pulse / grow, the green flash, the shutter flash, the shelf rising) is placed in [onFrame], driven by
 * StudioFrameSystem (ECS, never a Compose-driven per-frame loop). The shadow and the dashed fence are meshes rebuilt
 * off the main thread from the domain's hulls (<= 30 Hz).
 *
 * ```
 * root (volume centre)
 * ├ floor (walnut slab + matte top), wall (matte quad + walnut back), hint strip
 * ├ subjects: plaster / teapot / vase (one enabled), one PBR material each
 * ├ shadowFloor, shadowWall (unlit, 0.8 mm off the surface), fence (unlit, 1 cm upright dashes)
 * ├ orb:key|fill|rim (holder: sphere collider + Interactable + HoverEffect; ball, halo, name plate children)
 * ├ keyLight (directional), fillLight / rimLight (point), beam (drag), flash (shutter)
 * └ shelf + sw:0..4 (free light only), wall (free light only: tap collider on the upper half)
 * ```
 */
internal class StudioScene(private val scope: CoroutineScope) {
    /** Stage space (world). */
    val world: Entity = Entity().apply { setName("world") }
    /** The studio, placed in front of the user at desk height (Place.studio); every design coordinate is local to it. */
    val root: Entity = Entity().apply { setName("root"); enabled = false }
    init { world.addChild(root) }
    private val all = ArrayList<Resource>()
    private fun <T : Resource> keep(r: T): T { r.toGlobal(); synchronized(all) { all += r }; return r }

    private var built = false
    private var state = LabState()
    private var texts = SceneTexts("", emptyMap(), true)
    private var panelOpen = true

    // static
    private lateinit var floorTop: PhysicallyBasedMaterial
    private lateinit var wallMat: PhysicallyBasedMaterial
    private var duskTex: TextureResource? = null
    private var backdropShown: Backdrop? = null

    // subjects
    private val subjects = HashMap<SubjectId, Entity>()
    private val subjectMats = HashMap<SubjectId, PhysicallyBasedMaterial>()
    private var subjectShown: SubjectId? = null
    private var materialShown: String? = null

    // orbs
    private class Orb(val holder: Entity, val ball: ModelEntity, val halo: ModelEntity, val label: ModelEntity, val onMat: UnlitMaterial, val offMat: UnlitMaterial, val haloMat: UnlitMaterial, val labelMat: UnlitMaterial) {
        var labelKey: String? = null
        var labelBusy = false
        var labelTex: TextureResource? = null
        var on: Boolean? = null
    }
    private val orbs = HashMap<LightId, Orb>()
    private var keyLight: Entity? = null
    private var fillLight: Entity? = null
    private var rimLight: Entity? = null
    private var beam: ModelEntity? = null
    private var beamMat: UnlitMaterial? = null
    private var flash: ModelEntity? = null
    private var flashMat: UnlitMaterial? = null

    // strip
    private var stripMat: UnlitMaterial? = null
    private var stripTex: TextureResource? = null
    private var stripShown: String? = null
    private var stripBusy = false

    // shadow + fence
    private var shadowFloor: ModelEntity? = null
    private var shadowWall: ModelEntity? = null
    private var shadowFloorMat: UnlitMaterial? = null
    private var shadowWallMat: UnlitMaterial? = null
    private var shadowKey: Hulls? = null
    private var shadowBusy = false
    private var shadowMeshes = ArrayList<MeshResource>()
    private var fence: ModelEntity? = null
    private var fenceMat: UnlitMaterial? = null
    private var fenceKey: String? = null
    private var fenceBusy = false
    private var fenceMesh: MeshResource? = null

    // free light
    private var shelf: Entity? = null
    private val swatches = ArrayList<Entity>()
    private var wallTap: Entity? = null
    private var freeOn: Boolean? = null
    private var freeAt = 0L

    private lateinit var physics: PhysicsMaterialResource

    // Full Space: the app-drawn controller ray, the "look straight ahead" plate, the strip as the reopen entry
    private var ray: ModelEntity? = null
    private var rayDot: ModelEntity? = null
    private var rayMat: UnlitMaterial? = null
    private var levelPlate: ModelEntity? = null
    private var levelMat: UnlitMaterial? = null
    private var levelText: String? = null
    private var levelBusy = false
    private var levelTex: TextureResource? = null
    private var strip: Entity? = null
    private var stripShape: ShapeResource? = null
    private var stripCollider = false
    @Volatile var studioPose: io.embodit.renderlab.domain.Pose? = null
    @Volatile var rayView: Triple<V3, V3, Boolean>? = null
    @Volatile var levelHint: Pair<V3, Q4>? = null
    @Volatile var levelWords: String = ""

    @Volatile private var hover: String? = null

    fun setHover(name: String?) {
        if (name == hover) return
        hover = name
        if (name != null) Log.i(TAG, "HOVER $name")
    }

    val ready: Boolean get() = built

    /** Builds every mesh / material concurrently off the main thread, then wires the entity tree. */
    suspend fun install() {
        if (built) return
        val t0 = SystemClock.elapsedRealtime()
        val io = Dispatchers.IO
        class Made(
            val floorSlab: MeshResource, val floorTop: MeshResource, val wall: MeshResource, val wallBack: MeshResource,
            val subj: Map<SubjectId, List<MeshResource>>, val ball: MeshResource, val haloQ: MeshResource, val labelQ: MeshResource,
            val beam: MeshResource, val strip: MeshResource, val flash: MeshResource, val shelf: MeshResource, val swatch: MeshResource,
            val haloTex: TextureResource, val duskTex: TextureResource,
            val orbShape: ShapeResource, val swatchShape: ShapeResource, val wallShape: ShapeResource, val physics: PhysicsMaterialResource,
        )
        val made = coroutineScope {
            val subj = SubjectId.entries.associateWith { id -> async(io) { Shots.subject(id).meshes.mapIndexed { i, m -> keep(Kit.mesh(m, "subj_${id}_$i")) } } }
            val floorSlab = async(io) { keep(Kit.mesh(Shapes.box(V3(0f, FY - 0.0105f, -0.05f), V3(0.67f, 0.019f, 0.53f)), "floorSlab")) }
            val floorTopM = async(io) { keep(Kit.mesh(Kit.floorQuad(0.66f, 0.52f), "floorTop")) }
            val wall = async(io) { keep(Kit.mesh(Kit.quad(0.66f, Studio.WALL_H), "wall")) }
            val wallBack = async(io) { keep(Kit.mesh(Shapes.box(V3(0f, FY + Studio.WALL_H / 2, WZ - 0.0056f), V3(0.67f, Studio.WALL_H, 0.01f)), "wallBack")) }
            val ball = async(io) { keep(Kit.mesh(Shapes.sphere(V3.ZERO, Studio.ORB_R, 32, 16), "orbBall")) }
            val haloQ = async(io) { keep(Kit.mesh(Kit.quad(Studio.HALO, Studio.HALO), "halo")) }
            val labelQ = async(io) { keep(Kit.mesh(Kit.quad(Studio.LABEL_W, Studio.LABEL_H, 8, 2), "label")) }
            val beamM = async(io) { keep(Kit.mesh(Shapes.lathe(V3.ZERO, listOf(0f to 0f, 0.001f to 0f, 0.001f to 1f, 0f to 1f), 16), "beam")) }
            val strip = async(io) { keep(Kit.mesh(Kit.quad(Studio.STRIP_W, Studio.STRIP_H, 32, 2), "strip")) }
            val flashM = async(io) { keep(Kit.mesh(Kit.quad(0.66f, Studio.WALL_H), "flash")) }
            val shelfM = async(io) { keep(Kit.mesh(Shapes.box(V3.ZERO, V3(0.50f, 0.02f, 0.07f)), "shelf")) }
            val swatchM = async(io) { keep(Kit.mesh(Shapes.sphere(V3.ZERO, Studio.SWATCH_R, 32, 16), "swatch")) }
            val haloTex = async(io) { keep(TextureResource.create(Tex.halo())) }
            val dusk = async(io) { keep(TextureResource.create(Tex.gradient(Backdrop.DUSK.gradTop, Backdrop.DUSK.gradBottom))) }
            val orbShape = async(io) { keep(ShapeResource.createSphere(Studio.HIT_R)) }
            val swShape = async(io) { keep(ShapeResource.createSphere(Studio.SWATCH_HIT_R)) }
            val wallShape = async(io) { keep(ShapeResource.createBox(Vector3(0.66f, Studio.WALL_TOP - Studio.WALL_TAP_MIN_Y, 0.01f))) }
            val phys = async(io) { keep(PhysicsMaterialResource()) }
            listOf(floorSlab, floorTopM, wall, wallBack, ball, haloQ, labelQ, beamM, strip, flashM, shelfM, swatchM, haloTex, dusk, orbShape, swShape, wallShape, phys).awaitAll()
            Made(floorSlab.await(), floorTopM.await(), wall.await(), wallBack.await(), subj.mapValues { it.value.await() }, ball.await(), haloQ.await(), labelQ.await(),
                beamM.await(), strip.await(), flashM.await(), shelfM.await(), swatchM.await(), haloTex.await(), dusk.await(), orbShape.await(), swShape.await(), wallShape.await(), phys.await())
        }
        physics = made.physics
        duskTex = made.duskTex
        val walnut = keep(Kit.pbr(0x6B4A33, 0.55f))
        floorTop = keep(Kit.pbr(Backdrop.WHITE.floor, 0.95f))
        wallMat = keep(Kit.pbr(Backdrop.WHITE.wall, 0.95f))
        root.addChild(ModelEntity(made.floorSlab, walnut).apply { setName("floorSlab") })
        root.addChild(ModelEntity(made.floorTop, floorTop).apply { setName("floorTop"); at(V3(0f, FY, -0.05f)) })
        root.addChild(ModelEntity(made.wall, wallMat).apply { setName("wallFace"); at(V3(0f, FY + Studio.WALL_H / 2, WZ)) })
        root.addChild(ModelEntity(made.wallBack, walnut).apply { setName("wallBack") })

        // subjects
        for ((id, meshes) in made.subj) {
            val m = keep(PhysicallyBasedMaterial.create()).apply { setRoughness(0.9f); setMetallic(0f); setCullingMode(MaterialCullingMode.NONE) }
            subjectMats[id] = m
            val e = Entity().apply { setName("subject:${id.name.lowercase()}"); enabled = false }
            meshes.forEachIndexed { i, mr -> e.addChild(ModelEntity(mr, m).apply { setName("subjectPart:$i") }) }
            root.addChild(e)
            subjects[id] = e
        }

        // shadow + fence (meshes come later)
        shadowFloorMat = keep(Kit.unlit(shade(Backdrop.WHITE.floor)))
        shadowWallMat = keep(Kit.unlit(shade(Backdrop.WHITE.wall)))
        fenceMat = keep(Kit.unlit(Backdrop.WHITE.fence))

        // orbs
        for (id in LightId.entries) {
            val holder = Entity().apply { setName(StudioNames.orb(id)) }
            holder.components.set(CollisionComponent(collisionShape = listOf(made.orbShape), physicsMaterial = physics))
            holder.components.set(InteractableComponent())
            holder.components.set(HoverEffectComponent())
            val onMat = keep(Kit.unlit(orbRgb(id)))
            val offMat = keep(Kit.unlit(0x777777, opacity = 0.35f))
            val haloMat = keep(Kit.unlit(orbRgb(id), blend = BlendingMode.ADD)).apply { setBaseColorTexture(made.haloTex); setDepthWrite(false) }
            val labelMat = keep(Kit.unlit(0xFFFFFF, blend = BlendingMode.TRANSPARENT)).apply { setDepthWrite(false) }
            val ball = ModelEntity(made.ball, onMat).apply { setName("orbBall:${id.name.lowercase()}") }
            val halo = ModelEntity(made.haloQ, haloMat).apply { setName("orbHalo:${id.name.lowercase()}") }
            val label = ModelEntity(made.labelQ, labelMat).apply { setName("orbLabel:${id.name.lowercase()}"); at(V3(0f, Studio.LABEL_DY, 0f)) }
            holder.addChild(ball); holder.addChild(halo); holder.addChild(label)
            root.addChild(holder)
            orbs[id] = Orb(holder, ball, halo, label, onMat, offMat, haloMat, labelMat)
        }
        // lights (N2: point lights for fill / rim; see LightLevels)
        runCatching {
            keyLight = Entity().apply { setName("keyLight"); components.set(DirectionalLightComponent(rgb4(0xFFF4E5), LightLevels.keyHigh, false)) }
            root.addChild(keyLight!!)
        }.onFailure { Log.w(TAG, "LIGHT key unavailable: $it") }
        runCatching {
            fillLight = Entity().apply { setName("fillLight"); components.set(PointLightComponent(rgb4(0xFFF4E5), 0f, LightLevels.ATTEN)) }
            rimLight = Entity().apply { setName("rimLight"); components.set(PointLightComponent(rgb4(0xFFF4E5), 0f, LightLevels.ATTEN)) }
            root.addChild(fillLight!!); root.addChild(rimLight!!)
            Log.i(TAG, "LIGHT point lights added (N2)")
        }.onFailure { Log.w(TAG, "LIGHT point unavailable (N2): $it") }

        beamMat = keep(Kit.unlit(orbRgb(LightId.KEY), opacity = 0.6f))
        beam = ModelEntity(made.beam, beamMat!!).apply { setName("beam"); enabled = false }.also { root.addChild(it) }
        flashMat = keep(Kit.unlit(0xFFFFFF, opacity = 0.01f))
        flash = ModelEntity(made.flash, flashMat!!).apply { setName("flash"); enabled = false; at(V3(0f, FY + Studio.WALL_H / 2, WZ + 0.012f)) }.also { root.addChild(it) }

        // hint strip
        stripMat = keep(Kit.unlit(0xFFFFFF))
        strip = Entity().apply { setName(StudioNames.STRIP); at(Studio.STRIP_C) }
        strip!!.addChild(ModelEntity(made.strip, stripMat!!).apply { setName("hintStripFace") })
        root.addChild(strip!!)
        stripShape = keep(ShapeResource.createBox(Vector3(Studio.STRIP_W, Studio.STRIP_H + 0.02f, 0.01f)))
        // controller ray (app-drawn, D41) + its hit dot, in Stage space
        rayMat = keep(Kit.unlit(0xFFFFFF, opacity = 0.7f))
        ray = ModelEntity(made.beam, rayMat!!).apply { setName("ctrlRay"); enabled = false }.also { world.addChild(it) }
        rayDot = ModelEntity(made.ball, keep(Kit.unlit(0xFFFFFF))).apply { setName("ctrlDot"); enabled = false }.also { world.addChild(it) }
        levelMat = keep(Kit.unlit(0xFFFFFF))
        levelPlate = ModelEntity(made.strip, levelMat!!).apply { setName("levelPlate"); enabled = false }.also { world.addChild(it) }

        // free light: shelf + swatches + wall tap
        val sh = Entity().apply { setName("shelfRoot"); enabled = false }
        sh.addChild(ModelEntity(made.shelf, walnut).apply { setName("shelf"); at(V3(0f, -0.31f, Studio.SWATCH_Z)) })
        MaterialId.entries.forEachIndexed { i, mid ->
            val mat = keep(PhysicallyBasedMaterial.create()).also { applyMaterial(it, mid, Backdrop.WHITE) }
            val h = Entity().apply { setName(StudioNames.swatch(i)); at(V3(Studio.SWATCH_X[i], Studio.SWATCH_Y, Studio.SWATCH_Z)) }
            h.components.set(CollisionComponent(collisionShape = listOf(made.swatchShape), physicsMaterial = physics))
            h.components.set(InteractableComponent())
            h.components.set(HoverEffectComponent())
            h.addChild(ModelEntity(made.swatch, mat).apply { setName("swatchBall:$i") })
            sh.addChild(h); swatches += h
        }
        root.addChild(sh); shelf = sh
        wallTap = Entity().apply {
            setName(StudioNames.WALL)
            at(V3(0f, (Studio.WALL_TOP + Studio.WALL_TAP_MIN_Y) / 2, WZ + 0.006f))
            components.set(CollisionComponent(collisionShape = listOf(made.wallShape), physicsMaterial = physics))
            components.set(InteractableComponent())
            enabled = false
        }.also { root.addChild(it) }

        built = true
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms tris=${SubjectId.entries.associateWith { Shots.subject(it).triangles }}")
        onFrame(0f)
    }

    fun apply(next: LabState, panelOpen: Boolean, texts: SceneTexts) {
        state = next
        this.panelOpen = panelOpen
        this.texts = texts
        if (built) onFrame(0f)
    }

    // ------------------------------------------------------------------ materials

    private fun applyMaterial(m: PhysicallyBasedMaterial, id: MaterialId, bd: Backdrop) {
        m.setBaseColor(rgb4(id.base))
        m.setRoughness(id.roughness)
        m.setMetallic(id.metallic)
        // design lightingModel.emissive: glow glaze #7FFFD4 x 1.6; the rest 3 % of the base (none on the black studio)
        m.setEmissiveColor(
            when {
                id.emissive > 0f -> rgb4(id.emissiveRgb, k = id.emissive * LightLevels.glowK)
                bd == Backdrop.BLACK -> rgb4(0, k = 0f)
                else -> rgb4(id.base, k = 0.03f)
            },
        )
    }

    private fun refreshBackdrop(st: LabState) {
        val bd = st.backdrop
        if (bd == backdropShown) return
        backdropShown = bd
        floorTop.setBaseColor(rgb4(bd.floor))
        if (bd.isGradient) { wallMat.setBaseColor(rgb4(0xFFFFFF)); duskTex?.let { wallMat.setBaseColorTexture(it) } }
        else { wallMat.setBaseColor(rgb4(bd.wall)); runCatching { wallMat.setBaseColorTexture(blankTex()) } }
        shadowFloorMat?.setBaseColor(rgb4(shade(bd.floor)))
        shadowWallMat?.setBaseColor(rgb4(bd.wallShadow()))
        fenceKey = null
        materialShown = null
        Log.i(TAG, "BACKDROP $bd")
    }

    private var blank: TextureResource? = null
    private fun blankTex(): TextureResource = blank ?: keep(TextureResource.create(android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) })).also { blank = it }

    private fun refreshSubject(st: LabState) {
        if (st.subject != subjectShown) {
            subjects.forEach { (id, e) -> e.enabled = id == st.subject }
            subjectShown = st.subject
            Log.i(TAG, "SUBJECT ${st.subject}")
        }
        val key = "${st.subject}:${st.material}:${st.backdrop}"
        if (key != materialShown) {
            materialShown = key
            subjectMats[st.subject]?.let { applyMaterial(it, st.material, st.backdrop) }
        }
    }

    // ------------------------------------------------------------------ texture refreshers (async, keyed)

    private fun refreshStrip() {
        val text = texts.strip + if (texts.testF) "#F" else ""
        if (text == stripShown || stripBusy || texts.strip.isEmpty()) return
        stripBusy = true
        val words = texts.strip; val f = texts.testF
        scope.launch {
            val tex = withContext(Dispatchers.IO) { runCatching { TextureResource.create(Tex.strip(words, f)) }.getOrNull() }
            if (tex != null) {
                stripMat?.setBaseColorTexture(tex)
                stripTex?.let { runCatching { it.close() } }
                stripTex = tex
                stripShown = text
                Log.i(TAG, "STRIP \"$words\"")
            }
            stripBusy = false
        }
    }

    private fun refreshLabel(id: LightId, o: Orb, st: LabState) {
        val l = st.lights[id]
        val words = if (st.dragging == id) Studio.readout(l.az, l.el, texts.zh) else texts.names[id].orEmpty()
        val key = words + if (texts.testF) "#F" else ""
        if (key == o.labelKey || o.labelBusy || words.isEmpty()) return
        o.labelBusy = true
        val f = texts.testF
        scope.launch {
            val tex = withContext(Dispatchers.IO) { runCatching { TextureResource.create(Tex.label(words, f)) }.getOrNull() }
            if (tex != null) {
                o.labelMat.setBaseColorTexture(tex)
                o.labelTex?.let { runCatching { it.close() } }
                o.labelTex = tex
                o.labelKey = key
            }
            o.labelBusy = false
        }
    }

    private fun refreshLevel() {
        val words = levelWords
        if (words == levelText || levelBusy || words.isEmpty()) return
        levelBusy = true
        scope.launch {
            val tex = withContext(Dispatchers.IO) { runCatching { TextureResource.create(Tex.strip(words)) }.getOrNull() }
            if (tex != null) { levelMat?.setBaseColorTexture(tex); levelTex?.let { runCatching { it.close() } }; levelTex = tex; levelText = words }
            levelBusy = false
        }
    }

    // ------------------------------------------------------------------ shadow + fence meshes

    private fun polyMesh(list: List<List<P2>>, floor: Boolean): MeshData {
        val parts = list.mapIndexed { hi, h ->
            val lift = 0.0008f + hi * 0.0001f
            if (floor) Shapes.fan(h.map { V3(it.a, FY + lift, it.b) }, V3.UP)
            else Shapes.fan(h.map { V3(it.a, it.b, WZ + lift) }, V3(0f, 0f, 1f))
        }
        return Shapes.merge(parts)
    }

    private fun refreshShadow(st: LabState) {
        val h = st.hulls
        if (h == shadowKey || shadowBusy) return
        shadowBusy = true
        scope.launch {
            val made = withContext(Dispatchers.IO) {
                runCatching {
                    val f = if (h.floor.isEmpty()) null else Kit.mesh(polyMesh(h.floor, true), "shadowFloor")
                    val w = if (h.wall.isEmpty()) null else Kit.mesh(polyMesh(h.wall, false), "shadowWall")
                    f to w
                }.getOrNull()
            }
            if (made != null) {
                val (f, w) = made
                val old = ArrayList(shadowMeshes); shadowMeshes.clear()
                fun put(e: ModelEntity?, mesh: MeshResource?, mat: Material, name: String): ModelEntity? {
                    if (mesh == null) { e?.enabled = false; return e }
                    shadowMeshes += mesh
                    return if (e == null) ModelEntity(mesh, mat).apply { setName(name) }.also { root.addChild(it) }
                    else { e.components[ModelComponent::class.java]?.mesh = mesh; e.enabled = true; e }
                }
                shadowFloor = put(shadowFloor, f, shadowFloorMat!!, "shadowFloor")
                shadowWall = put(shadowWall, w, shadowWallMat!!, "shadowWall")
                old.forEach { runCatching { it.close() } }
                shadowKey = h
            }
            shadowBusy = false
            if (state.hulls != shadowKey) refreshShadow(state)
        }
    }

    /** The dashed fence (design entities3d.targetOutline): 1 cm upright 2 mm strips, 1.2 cm on / 0.8 cm off; solid 1.4 cm and green when matched. */
    private fun fenceMesh(target: Hulls, matched: Boolean): MeshData {
        val b = MeshBuilder()
        val hgt = if (matched) 0.014f else 0.01f
        fun dashOn(acc: Float) = matched || (acc % 0.02f) <= 0.012f
        var acc = 0f
        for ((p0, p1) in Shadow.outline(target.floor, 0.004f)) {
            val dx = p1.a - p0.a; val dz = p1.b - p0.b
            val len = kotlin.math.hypot(dx, dz)
            acc += len
            if (!dashOn(acc)) continue
            val nx = -dz / len * 0.001f; val nz = dx / len * 0.001f
            for (s in listOf(1f, -1f)) {
                val n = V3(nx * s, 0f, nz * s)
                val a = b.v(V3(p0.a + nx * s, FY, p0.b + nz * s), n); val c = b.v(V3(p1.a + nx * s, FY, p1.b + nz * s), n)
                val d = b.v(V3(p1.a + nx * s, FY + hgt, p1.b + nz * s), n); val e = b.v(V3(p0.a + nx * s, FY + hgt, p0.b + nz * s), n)
                b.quad(a, c, d, e)
            }
            val t0 = b.v(V3(p0.a + nx, FY + hgt, p0.b + nz), V3.UP); val t1 = b.v(V3(p1.a + nx, FY + hgt, p1.b + nz), V3.UP)
            val t2 = b.v(V3(p1.a - nx, FY + hgt, p1.b - nz), V3.UP); val t3 = b.v(V3(p0.a - nx, FY + hgt, p0.b - nz), V3.UP)
            b.quad(t0, t1, t2, t3)
        }
        acc = 0f
        for ((p0, p1) in Shadow.outline(target.wall, 0.004f)) {
            val dx = p1.a - p0.a; val dy = p1.b - p0.b
            val len = kotlin.math.hypot(dx, dy)
            acc += len
            if (!dashOn(acc)) continue
            val nx = -dy / len * 0.005f; val ny = dx / len * 0.005f
            val n = V3(0f, 0f, 1f); val z = WZ + 0.0016f
            b.quad(b.v(V3(p0.a + nx, p0.b + ny, z), n), b.v(V3(p0.a - nx, p0.b - ny, z), n), b.v(V3(p1.a - nx, p1.b - ny, z), n), b.v(V3(p1.a + nx, p1.b + ny, z), n))
        }
        return b.build()
    }

    private fun refreshFence(st: LabState) {
        val ev = st.eval
        val show = ev != null && (st.phase == Phase.LIGHTING || st.phase == Phase.WELCOME || st.phase == Phase.RESULT)
        if (!show) { fence?.enabled = false; fenceKey = null; return }
        val matched = st.matched && st.phase != Phase.WELCOME
        val key = "${ev!!.shot.id}:${ev.shot.seed}:$matched"
        val colour = if (matched) 0x34C77B else st.backdrop.fence
        fenceMat?.setBaseColor(rgb4(colour))
        if (key == fenceKey || fenceBusy) { fence?.enabled = fenceKey == key; return }
        fenceBusy = true
        val target = ev.target
        scope.launch {
            val mesh = withContext(Dispatchers.IO) { runCatching { Kit.mesh(fenceMesh(target, matched), "fence") }.getOrNull() }
            if (mesh != null) {
                val f = fence
                if (f == null) fence = ModelEntity(mesh, fenceMat!!).apply { setName("fence") }.also { root.addChild(it) }
                else { f.components[ModelComponent::class.java]?.mesh = mesh; f.enabled = true }
                fenceMesh?.let { runCatching { it.close() } }
                fenceMesh = mesh
                fenceKey = key
                Log.i(TAG, "FENCE ${ev.shot.id} matched=$matched")
            }
            fenceBusy = false
        }
    }

    // ------------------------------------------------------------------ per frame

    private var poseShown: io.embodit.renderlab.domain.Pose? = null

    fun onFrame(@Suppress("UNUSED_PARAMETER") dt: Float) {
        if (!built) return
        val st = state
        val now = SystemClock.uptimeMillis()
        studioPose?.let { p ->
            if (p != poseShown) { poseShown = p; root.at(p.p, p.q); root.enabled = true; Log.i(TAG, "STUDIO placed at ${p.p}") }
        }
        // controller ray: origin -> hit (or 1.2 m), thin unlit cylinder + a 6 mm dot on a hit
        val rv = rayView
        if (rv == null) { ray?.enabled = false; rayDot?.enabled = false } else {
            val (o, e, hit) = rv
            val d = e - o
            ray?.let { r -> r.at(o, Q4.fromTo(V3.UP, d)); r.scale(1.5f, d.length, 1.5f); r.enabled = true }
            rayMat?.setBaseColor(rgb4(if (hit) 0xFFD27A else 0xFFFFFF))
            rayDot?.let { r -> r.at(e, s = 0.15f); r.enabled = hit }
        }
        // "look straight ahead": on the line of sight while the window waits for a level head (native-window-move §7.9)
        val lh = levelHint
        levelPlate?.let { lp ->
            if (lh == null) lp.enabled = false else { lp.at(lh.first, lh.second); lp.enabled = true; refreshLevel() }
        }
        // the hint strip takes a tap only while the control window is closed (it says how to reopen it)
        if (stripCollider != !panelOpen) {
            stripCollider = !panelOpen
            strip?.let { e ->
                if (stripCollider) { e.components.set(CollisionComponent(collisionShape = listOf(stripShape!!), physicsMaterial = physics)); e.components.set(InteractableComponent()); e.components.set(HoverEffectComponent()) }
                else { e.components.remove(CollisionComponent::class.java); e.components.remove(InteractableComponent::class.java); e.components.remove(HoverEffectComponent::class.java) }
            }
        }
        refreshBackdrop(st)
        refreshSubject(st)
        refreshStrip()
        refreshShadow(st)
        refreshFence(st)

        // orbs
        for ((id, o) in orbs) {
            val l = st.lights[id]
            o.holder.at(l.pos)
            refreshLabel(id, o, st)
            val on = l.on
            val colour = mulRgb(orbRgb(id), l.kelvin.rgb)
            // matched: the key orb turns green for 0.4 s (design S06); all three stars: each orb blinks in turn (S10)
            val green = id == LightId.KEY && st.matchedAt > 0 && now - st.matchedAt in 0..400
            val blink = st.allStarsAt > 0 && (now - st.allStarsAt - id.ordinal * 300L) in 0..250
            if (o.on != on) { o.on = on; o.ball.components[ModelComponent::class.java]?.materials?.set(0, if (on) o.onMat else o.offMat) }
            if (on) o.onMat.setBaseColor(rgb4(if (green) 0x34C77B else colour, k = if (blink) 1.3f else 1f))
            o.halo.enabled = on
            o.haloMat.setBaseColor(rgb4(if (green) 0x34C77B else colour))
            var s = 1f
            if (st.dragging == id) s = 1.15f else if (st.selected == id && (st.phase == Phase.LIGHTING || st.phase == Phase.FREE)) s = 1.08f
            // S01: the key orb breathes every 1.5 s so the first move is obvious (design fiveSeconds)
            if (id == LightId.KEY && st.phase == Phase.WELCOME) { val ph = (now % 1500) / 1500f; if (ph < 0.35f) s *= 1f + 0.25f * sin(PI.toFloat() * ph / 0.35f) }
            if (hover == StudioNames.orb(id) && st.dragging == null) s *= 1.05f
            o.ball.at(V3.ZERO, s = s)
            o.halo.at(V3.ZERO, s = s)
        }

        // lights
        val k = st.lights.key
        keyLight?.let { e ->
            e.at(Studio.C, Q4.fromTo(V3(0f, 0f, 1f), k.dir))
            e.components[DirectionalLightComponent::class.java]?.let { c -> c.color = rgb4(k.kelvin.rgb); c.intensity = LightLevels.key(k.level) }
        }
        for ((id, e) in listOf(LightId.FILL to fillLight, LightId.RIM to rimLight)) {
            val l = st.lights[id]
            e ?: continue
            e.at(l.pos)
            e.components[PointLightComponent::class.java]?.let { c -> c.color = rgb4(l.kelvin.rgb); c.intensity = if (l.on) LightLevels.point(id, l.level) else 0f }
        }

        // beam while dragging (design entities3d.beam)
        val dragId = st.dragging
        beam?.let { b ->
            if (dragId == null) b.enabled = false else {
                val p = st.lights[dragId].pos
                val d = p - Studio.C
                b.at(Studio.C, Q4.fromTo(V3.UP, d))
                b.scale(1f, d.length, 1f)
                beamMat?.setBaseColor(rgb4(orbRgb(dragId)))
                b.enabled = true
            }
        }

        // shutter flash 0 -> 0.6 -> 0 over 120 ms (design S07)
        flash?.let { f ->
            val e = now - st.shutterAt
            if (st.shutterAt < 0 || e > 120) f.enabled = false else {
                val a = if (e < 60) e / 60f else 1f - (e - 60) / 60f
                flashMat?.setOpacity((0.6f * a).coerceIn(0.01f, 0.6f)); f.enabled = true
            }
        }

        // free light: shelf rises 0.3 s, wall takes taps on its upper half (design S09)
        val free = st.phase == Phase.FREE
        if (freeOn != free) {
            freeOn = free
            freeAt = now
            shelf?.enabled = free
            wallTap?.enabled = free
            Log.i(TAG, "FREE props $free")
        }
        if (free) {
            val e = ((now - freeAt) / 300f).coerceIn(0f, 1f)
            shelf?.at(V3(0f, -0.04f * (1f - e) * (1f - e), 0f))
            swatches.forEachIndexed { i, s ->
                val sel = MaterialId.entries[i] == st.material
                s.getChildren().firstOrNull()?.at(V3(0f, if (sel) 0.006f else 0f, 0f), s = if (hover == StudioNames.swatch(i)) 1.08f else 1f)
            }
        }
    }

    /** Debug hit replay: the holder and its deepest child. */
    fun replayEntities(target: String): List<Entity> {
        val out = ArrayList<Entity>()
        fun deepest(e: Entity): Entity { var x = e; while (x.getChildren().isNotEmpty()) x = x.getChildren().first(); return x }
        fun visit(e: Entity) {
            if (runCatching { e.getName() }.getOrNull() == target && e.enabled) { out += e; deepest(e).takeIf { it !== e }?.let { out += it } }
            e.getChildren().forEach { visit(it) }
        }
        visit(root)
        return out
    }

    fun evidence(): String {
        val st = state
        return "state=${st.stateId} subject=$subjectShown backdrop=$backdropShown fence=$fenceKey strip=\"$stripShown\" " +
            "orbs=${orbs.map { (id, o) -> "$id@${o.holder.transform().position}" }} floorPolys=${st.hulls.floor.size} wallPolys=${st.hulls.wall.size} " +
            "key=${LightLevels.key(st.lights.key.level)} fill=${LightLevels.point(LightId.FILL, st.lights.fill.level)} rim=${LightLevels.point(LightId.RIM, st.lights.rim.level)}"
    }

    fun dispose() {
        stripTex?.let { runCatching { it.close() } }
        orbs.values.forEach { o -> o.labelTex?.let { runCatching { it.close() } } }
        shadowMeshes.forEach { runCatching { it.close() } }
        fenceMesh?.let { runCatching { it.close() } }
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }
}

/** Every holder name (one place; the hit-replay unit test resolves the same names). */
object StudioNames {
    const val WALL = "wall"
    const val STRIP = "strip"
    fun orb(id: LightId) = "orb:${id.name.lowercase()}"
    fun swatch(i: Int) = "sw:$i"

    sealed interface Hit
    data class OrbHit(val id: LightId) : Hit
    data class SwatchHit(val index: Int) : Hit
    data object WallHit : Hit
    data object StripHit : Hit
    data object NoHit : Hit

    fun hitOf(name: String?): Hit {
        if (name == null) return NoHit
        val f = name.split(":")
        return when (f[0]) {
            "orb" -> if (f.size == 2) LightId.entries.firstOrNull { it.name.lowercase() == f[1] }?.let { OrbHit(it) } ?: NoHit else NoHit
            "sw" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { f.size == 2 && it in 0..4 }?.let { SwatchHit(it) } ?: NoHit
            WALL -> if (f.size == 1) WallHit else NoHit
            STRIP -> if (f.size == 1) StripHit else NoHit
            else -> NoHit
        }
    }
}
