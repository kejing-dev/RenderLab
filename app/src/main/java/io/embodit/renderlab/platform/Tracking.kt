package io.embodit.renderlab.platform

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.renderlab.domain.Place
import io.embodit.renderlab.domain.Q4
import io.embodit.renderlab.domain.V3
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Head pose in Stage space (HMDTrackingProvider), read once per frame. Used for two things only (D1 / ui-audit
 * headpose table): placing the studio at start / [叫回面前] (yaw only), and the window's level gate (pitch). Never to
 * aim, pick or drag.
 */
class HeadTracker {
    private var provider: HMDTrackingProvider? = null

    fun start() {
        if (provider != null) return
        val p = HMDTrackingProvider()
        val r = runCatching { p.start() }
        Log.i(TAG, "TRACK hmd start=${r.getOrNull() ?: r.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() { runCatching { provider?.stop() }; provider = null }

    /** (position, rotation) or null when no valid pose (all-zero + identity counts as invalid). */
    fun pose(): Pair<V3, Q4>? {
        val p = provider ?: return null
        if (runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return null
        val pose = runCatching { p.latestData.hmdPose }.getOrNull() ?: return null
        val pos = V3(pose.position.x, pose.position.y, pose.position.z)
        val rot = Q4(pose.rotation.w, pose.rotation.x, pose.rotation.y, pose.rotation.z)
        return if (Place.valid(pos, rot)) pos to rot else null
    }
}

/** One frame of the aiming controller (Stage space): ray origin + unit direction (-Z of the controller), trigger. */
data class ControllerSample(val origin: V3, val dir: V3, val trigger: Float, val pressed: Boolean, val right: Boolean)

/**
 * Controller ray + trigger (ControllerTrackingProvider; same approach as pruning-class / Spatial Ruler). The app draws
 * its own ray and does its own orb pick (D41: "手柄射线自己画"), so a trigger drag works even where the system ray
 * does not reach into the scene. The action listener runs on the provider's thread and only copies into atomics.
 */
class ControllerInput {
    private var provider: ControllerTrackingProvider? = null
    private val rightValue = AtomicInteger(0)
    private val leftValue = AtomicInteger(0)
    private val rightPressed = AtomicBoolean(false)
    private val leftPressed = AtomicBoolean(false)
    private var useRight = true

    private val listener = ControllerTrackingProvider.ControllerActionListener { a: ControllerActionData ->
        rightValue.set(java.lang.Float.floatToIntBits(a.right.triggerValue))
        rightPressed.set(a.right.triggerPressed)
        leftValue.set(java.lang.Float.floatToIntBits(a.left.triggerValue))
        leftPressed.set(a.left.triggerPressed)
    }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }
        val r = runCatching { p.start() }
        Log.i(TAG, "TRACK controller start=${r.getOrNull() ?: r.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        provider?.let { p -> runCatching { p.removeControllerActionListener(listener) }; runCatching { p.stop() } }
        provider = null
    }

    fun sample(): ControllerSample? {
        val p = provider ?: return null
        if (runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        val rv = java.lang.Float.intBitsToFloat(rightValue.get()); val lv = java.lang.Float.intBitsToFloat(leftValue.get())
        val rd = rightPressed.get(); val ld = leftPressed.get()
        if (useRight && (lv > 0.15f || ld) && rv < 0.05f && !rd) useRight = false
        if (!useRight && (rv > 0.15f || rd) && lv < 0.05f && !ld) useRight = true
        val pickRight = if (useRight) data.right != null else data.left == null
        val pose = (if (pickRight) data.right else data.left) ?: return null
        val o = V3(pose.position.x, pose.position.y, pose.position.z)
        val q = Q4(pose.rotation.w, pose.rotation.x, pose.rotation.y, pose.rotation.z)
        if (!o.x.isFinite() || !Place.valid(o, q)) return null
        val d = q.rotate(V3(0f, 0f, -1f)).norm()
        return ControllerSample(o, d, if (pickRight) rv else lv, if (pickRight) rd else ld, pickRight)
    }

    @Suppress("unused") private val t0 = SystemClock.elapsedRealtime()
}
