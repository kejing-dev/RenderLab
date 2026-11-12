package io.embodit.renderlab.ui.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import io.embodit.renderlab.domain.Studio
import android.util.Log
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.renderlab.domain.MeshBuilder
import io.embodit.renderlab.domain.MeshData
import io.embodit.renderlab.domain.Q4
import io.embodit.renderlab.domain.V3
import io.embodit.renderlab.platform.TAG

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Entity.at(p: V3, q: Q4? = null, s: Float? = null) {
    val t = transform()
    t.setPosition(Vector3(p.x, p.y, p.z))
    q?.let { t.setQuaternion(Quat(it.x, it.y, it.z, it.w)) }
    s?.let { t.setScaleVector(Vector3(it, it, it)) }
}

internal fun Entity.scale(x: Float, y: Float, z: Float) { transform().setScaleVector(Vector3(x, y, z)) }

/** 0xRRGGBB -> Color4 (sRGB values as given; [k] scales the colour, e.g. emissive intensity). */
internal fun rgb4(rgb: Int, a: Float = 1f, k: Float = 1f) = Color4((rgb shr 16 and 255) / 255f * k, (rgb shr 8 and 255) / 255f * k, (rgb and 255) / 255f * k, a)

/** Multiply two 0xRRGGBB colours (orb colour x colour temperature). */
internal fun mulRgb(a: Int, b: Int): Int {
    fun ch(s: Int) = ((a shr s and 255) * (b shr s and 255) / 255)
    return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

internal object Kit {
    /** A [MeshData] as an SDK mesh (creation waits on the renderer: call off the main thread). */
    fun mesh(m: MeshData, name: String): MeshResource {
        val pos = ArrayList<Vector3>(m.vertexCount); val nor = ArrayList<Vector3>(m.vertexCount); val uv = ArrayList<Vector2>(m.vertexCount)
        for (i in 0 until m.vertexCount) {
            pos += Vector3(m.pos[3 * i], m.pos[3 * i + 1], m.pos[3 * i + 2])
            nor += Vector3(m.nor[3 * i], m.nor[3 * i + 1], m.nor[3 * i + 2])
            uv += Vector2(m.uv[2 * i], m.uv[2 * i + 1])
        }
        val (lo, hi) = if (m.vertexCount > 0) m.bounds() else V3.ZERO to V3.ZERO
        val c = (lo + hi) * 0.5f
        val h = (hi - lo) * 0.5f
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = pos, triangleIndices = m.idx.toList(), normals = nor, uv0 = uv),
            bounds = BoundingBox(center = Vector3(c.x, c.y, c.z), halfExtent = Vector3(maxOf(h.x, 0.001f), maxOf(h.y, 0.001f), maxOf(h.z, 0.001f))),
            name = name,
        )
    }

    /**
     * A quad [w] x [h] centred on the origin in the XY plane facing +Z, uv (0,0) bottom-left, split into [nx] x [ny]
     * cells. Text quads (hint strip, level plate, name plates) use a grid: as one long two-triangle quad the words on the
     * far half rendered visibly slanted under a steep view (QA #1 "英文字条像斜体"; the texture itself is upright).
     */
    fun quad(w: Float, h: Float, nx: Int = 1, ny: Int = 1): MeshData {
        val b = MeshBuilder()
        val n = V3(0f, 0f, 1f)
        val idx = Array(ny + 1) { j -> IntArray(nx + 1) { i ->
            val u = i / nx.toFloat(); val v = j / ny.toFloat()
            b.v(V3(-w / 2 + w * u, -h / 2 + h * v, 0f), n, u, v)
        } }
        for (j in 0 until ny) for (i in 0 until nx) b.quad(idx[j][i], idx[j][i + 1], idx[j + 1][i + 1], idx[j + 1][i])
        return b.build()
    }

    /** A flat quad [w] x [d] lying in XZ, facing +Y, centred on the origin; uv v grows toward -Z (the far edge up). */
    fun floorQuad(w: Float, d: Float): MeshData {
        val b = MeshBuilder()
        val n = V3(0f, 1f, 0f)
        val a = b.v(V3(-w / 2, 0f, d / 2), n, 0f, 0f)
        val bb = b.v(V3(w / 2, 0f, d / 2), n, 1f, 0f)
        val c = b.v(V3(w / 2, 0f, -d / 2), n, 1f, 1f)
        val e = b.v(V3(-w / 2, 0f, -d / 2), n, 0f, 1f)
        b.quad(a, bb, c, e)
        return b.build()
    }

    fun pbr(rgb: Int, rough: Float, metal: Float = 0f): PhysicallyBasedMaterial =
        PhysicallyBasedMaterial.create().apply { setBaseColor(rgb4(rgb)); setRoughness(rough); setMetallic(metal) }

    fun unlit(rgb: Int, opacity: Float = 1f, blend: BlendingMode? = null): UnlitMaterial =
        UnlitMaterial.create(blend ?: if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
            setBaseColor(rgb4(rgb))
            if (opacity < 1f) setOpacity(opacity)
            setCullingMode(MaterialCullingMode.NONE)
            setApplyToneMapping(false)
        }
}

/**
 * Canvas textures of the volume: the hint strip (dark plate, white words), the orb name plates (white words with a dark
 * outline), the orb halo (radial gradient) and the dusk wall (vertical gradient). Image up = +Y on upright quads (D10,
 * checked with the F↑ test via debug `testf`).
 */
internal object Tex {
    /**
     * The app's own Noto Sans SemiBold (set once from [StudioScreen]); CJK glyphs fall back to the system font. The
     * system SANS_SERIF + BOLD drew part of the English strip with a slanted / synthetic face on the emulator (QA #1).
     */
    @Volatile var typeface: Typeface? = null
    private fun font(): Typeface = typeface ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    fun strip(text: String, testF: Boolean = false): Bitmap {
        // power-of-two width (v0.1.1): a 1536-wide texture came out with its right half sheared ("italic") in the Stage
        val w = 2048; val h = 128
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(android.graphics.Color.rgb(0x22, 0x26, 0x2B))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 74f; typeface = font(); textAlign = Paint.Align.CENTER }
        val t = if (testF) "F↑ $text" else text
        while (p.measureText(t) > w - 60 && p.textSize > 40f) p.textSize -= 2f
        if (p.measureText(t) > w - 60) Log.w(TAG, "OVERFLOW strip \"$t\"")
        c.drawText(t, w / 2f, h / 2f - (p.ascent() + p.descent()) / 2, p)
        return b
    }

    /**
     * Orb name plate / drag readout: white words on a dark rounded pill (QA #1: the outlined words on the white studio
     * looked fuzzy and small). Aspect = LABEL_W : LABEL_H.
     */
    fun label(text: String, testF: Boolean = false): Bitmap {
        val h = 128; val w = Math.round(h * Studio.LABEL_W / Studio.LABEL_H)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val t = if (testF) "F↑ $text" else text
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 84f; typeface = font(); textAlign = Paint.Align.CENTER }
        while (fill.measureText(t) > w - 56 && fill.textSize > 30f) fill.textSize -= 2f
        if (fill.measureText(t) > w - 56) Log.w(TAG, "OVERFLOW orbLabel \"$t\"")
        val pillW = minOf(w.toFloat(), fill.measureText(t) + 56f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(225, 0x22, 0x26, 0x2B) }
        c.drawRoundRect((w - pillW) / 2f, 0f, (w + pillW) / 2f, h.toFloat(), h / 2f, h / 2f, bg)
        val y = h / 2f - (fill.ascent() + fill.descent()) / 2
        c.drawText(t, w / 2f, y, fill)
        return b
    }

    fun halo(): Bitmap {
        val s = 128
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(s / 2f, s / 2f, s / 2f, intArrayOf(android.graphics.Color.argb(255, 255, 255, 255), android.graphics.Color.argb(140, 255, 255, 255), android.graphics.Color.argb(0, 255, 255, 255)), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        return b
    }

    fun gradient(top: Int, bottom: Int): Bitmap {
        val w = 256; val h = 512
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF000000.toInt() or top, 0xFF000000.toInt() or bottom, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return b
    }
}
