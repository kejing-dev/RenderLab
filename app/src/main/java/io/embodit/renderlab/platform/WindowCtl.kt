package io.embodit.renderlab.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.renderlab.domain.LevelGate
import io.embodit.renderlab.ui.Containers

/**
 * The control window in Full Space (native-window-move §3.3 / §3.5 / §7 item 9): every open is first asked and goes out
 * only once the head is level (|pitch| <= 10° for 300 ms; watchdogs so a Stage without frames never keeps it shut).
 * [叫回面前] closes the window and, after ON_DESTROY, asks again (the system reopens it straight ahead). Exit closes the
 * window and the Stage before the tasks are removed (D44).
 */
class WindowCtl {
    var panelState by mutableStateOf("never")
        private set
    val panelOpen: Boolean get() = panelState != "never" && panelState != "ON_DESTROY" && panelState != "ON_STOP"
    /** "Look straight ahead" wanted (the Stage shows it on the line of sight). */
    var levelHint by mutableStateOf(false)
        private set

    private var navigator: SpatialNavigator? = null
    private var reopenPending = false
    private val gate = LevelGate()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastStepMs = 0L
    @Volatile var openedMs = 0L
        private set

    fun attach(nav: SpatialNavigator) { navigator = nav }
    fun detach() { navigator = null }
    fun attachPanel(@Suppress("UNUSED_PARAMETER") nav: SpatialNavigator) {}

    /** Ask for the window (Stage start, the strip tap while it is closed, the reopen of [叫回面前]). */
    fun open(why: String) {
        if (panelOpen) return
        Log.i(TAG, "WINDOW ask why=$why")
        gate.ask(why, SystemClock.elapsedRealtime())
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3_000L)
    }

    /**
     * Every Stage frame: head pitch in degrees (+ up), null without a valid head pose; [yawOffDeg] how far you look away
     * from the window's spot beside the studio (v0.1.3 side by side). Returns the reason when the window goes out now.
     */
    fun stepGate(now: Long, pitchDeg: Float?, yawOffDeg: Float? = null): String? {
        lastStepMs = now
        val hint = gate.hintWanted(pitchDeg, yawOffDeg) || (gate.waiting && pitchDeg == null)
        if (hint != levelHint) Log.i(TAG, "LEVEL hint=$hint pitch=${pitchDeg?.let { "%.1f".format(it) }} yawOff=${yawOffDeg?.let { "%.1f".format(it) }}")
        levelHint = hint
        val why = gate.step(now, pitchDeg, yawOffDeg) ?: return null
        levelHint = false
        Log.i(TAG, "LEVEL ok pitch=${pitchDeg?.let { p -> "%.1f".format(p) }} yawOff=${yawOffDeg?.let { "%.1f".format(it) }} -> open")
        openNow(why)
        return why
    }

    /** Debug `level`: open now, whatever the head does (QA screenshots on an emulator whose head cannot turn). */
    fun forceOpen() { val why = gate.pending ?: return; gate.cancel(); levelHint = false; openNow("$why/forced") }

    private fun openNow(why: String) {
        main.removeCallbacks(watchdog)
        Log.i(TAG, "WINDOW open why=$why")
        openedMs = SystemClock.elapsedRealtime()
        main.post { runCatching { navigator?.openWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.e(TAG, "WINDOW open failed", it) } }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!gate.waiting) return
            val now = SystemClock.elapsedRealtime()
            val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > 15_000L else now - lastStepMs > 3_000L
            if (stalled) { val why = gate.pending ?: return; gate.cancel(); levelHint = false; openNow("$why/noframes") } else main.postDelayed(this, 3_000L)
        }
    }

    /** Settings [叫回面前]: close and reopen straight ahead (after ON_DESTROY, through the level gate). */
    fun reopen() {
        if (!panelOpen) { open("recall"); return }
        reopenPending = true
        Log.i(TAG, "WINDOW recall close")
        runCatching { navigator?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }
    }

    fun onPanelLifecycle(event: String) {
        panelState = event
        Log.i(TAG, "WINDOW lifecycle $event")
        if (reopenPending && event == "ON_DESTROY") { reopenPending = false; main.postDelayed({ open("recall") }, 150) }
    }

    /** Debug: the system [×] on the control window. */
    fun closeControlOnly() { runCatching { navigator?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) } }

    /** Exit (D26 / D44): the control window and the Stage. */
    suspend fun closeAll() {
        gate.cancel(); levelHint = false
        val nav = navigator ?: return
        runCatching { nav.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.w(TAG, "EXIT close control: $it") }
        runCatching { nav.closeStage() }.onFailure { Log.w(TAG, "EXIT closeStage: $it") }
        Log.i(TAG, "EXIT closed window + stage")
    }
}
