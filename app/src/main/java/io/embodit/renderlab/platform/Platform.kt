package io.embodit.renderlab.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.renderlab.data.FileSaveStore
import io.embodit.renderlab.data.SettingsStore
import io.embodit.renderlab.domain.LabFlow
import io.embodit.renderlab.domain.Sound
import io.embodit.renderlab.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

const val TAG = "RenderLab"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        io.embodit.renderlab.ui.studio.input.BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/** The process-wide objects (native-window-move.md §3.4: state shared by the volume and the window lives here). */
class Graph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val sounds = SoundPlayer(context)
    val store = FileSaveStore(context)
    val flow = LabFlow(
        scope = scope,
        engine = Dispatchers.Default,
        clock = { SystemClock.uptimeMillis() },
        store = store,
        onSound = { s, rate -> sounds.play(s, rate) },
        log = { Log.i(TAG, it) },
    )
    val window = WindowCtl()
    /** Head + controller tracking (Full Space only). */
    val tracking = Tracking()
    /** Studio pose in Stage space; null until the first valid head pose (then set once, and again on [叫回面前]). */
    @Volatile var studioPose: io.embodit.renderlab.domain.Pose? = null
    @Volatile var recallStudio = true
    /** Where the control window should open (flat unit vector from the eye), beside the studio (v0.1.3, D54 / D41). */
    @Volatile var windowDir: io.embodit.renderlab.domain.V3? = null

    /** Settings [叫回面前] (D44 in Full Space): the studio back in front of you at desk height, the window reopened ahead. */
    fun recall() {
        Log.i(TAG, "RECALL studio + window")
        recallStudio = true
        window.reopen()
    }
    /** S00 -> ready: the studio's meshes are built. */
    val sceneReady = MutableStateFlow(false)

    init {
        scope.launch { settings.settings.collect { s -> sounds.volumeLevel = s.volume } }
        flow.start()
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

class Tracking {
    val head = HeadTracker()
    val controller = ControllerInput()
    fun start() { head.start(); controller.start() }
    fun stop() { head.stop(); controller.stop() }
}

/** Volume percent of each snap stop (Settings.volume 0 = mute, 1..3 = low / mid / high). */
val VOLUME_PERCENT = intArrayOf(0, 50, 85, 100)

/**
 * Event sounds from assets/sounds/<name>.wav (+ _2 / _3 variants) through a SoundPool (design assets.sounds; no
 * ambience, B24). Recorded events rotate variants with a +-3 % random pitch (design soundPolicy); one instance per
 * event and a soft limiter keep the summed peaks under -1 dBFS (D51).
 */
class SoundPlayer(context: Context) {
    @Volatile var volumeLevel = 2

    private val pool = SoundPool.Builder().setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<Sound, List<Int>>()
    private val meta = HashMap<Int, Pair<Long, Float>>()
    private val loaded = HashSet<Int>()
    private val random = Random(7)
    private val lastVariant = HashMap<Sound, Int>()
    private val playing = HashMap<Sound, Int>()
    private val active = HashMap<Int, Pair<Long, Float>>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (s in Sound.entries) {
            val base = s.name.lowercase()
            val found = ArrayList<Int>()
            for (name in listOf("$base.wav", "${base}_2.wav", "${base}_3.wav")) {
                runCatching {
                    val id = context.assets.openFd("sounds/$name").use { fd -> pool.load(fd, 1) }
                    found += id
                    meta[id] = context.assets.open("sounds/$name").use { readMeta(it.readBytes()) }
                }
            }
            if (found.isNotEmpty()) ids[s] = found else Log.w(TAG, "no sound for $s")
        }
    }

    fun play(s: Sound, pitch: Float = 1f) {
        val level = volumeLevel.coerceIn(0, 3)
        if (level == 0) return
        val variants = ids[s] ?: return
        val vol = VOLUME_PERCENT[level] / 100f
        var v = random.nextInt(variants.size)
        if (v == lastVariant[s] && variants.size > 1) v = (v + 1) % variants.size
        lastVariant[s] = v
        val rate = (0.97f + random.nextFloat() * 0.06f) * pitch
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        val now = SystemClock.uptimeMillis()
        synchronized(active) {
            playing.remove(s)?.let { pool.stop(it); active.remove(it) }
            active.entries.removeAll { it.value.first <= now }
            val (durMs, peak) = meta[id] ?: (500L to 0.9f)
            val others = active.values.sumOf { it.second.toDouble() }.toFloat()
            val gain = MixHeadroom.gain(vol * peak, others)
            val stream = pool.play(id, vol * gain, vol * gain, 1, 0, rate.coerceIn(0.5f, 2f))
            if (stream != 0) {
                playing[s] = stream
                active[stream] = (now + (durMs / rate).toLong()) to vol * gain * peak
            }
        }
    }

    fun release() = runCatching { pool.release() }

    companion object {
        const val MAX_STREAMS = 6

        /** Duration (ms) and peak (linear) of a 16-bit PCM WAV. */
        fun readMeta(b: ByteArray): Pair<Long, Float> {
            var i = 12
            var rate = 22050; var ch = 1
            while (i + 8 <= b.size) {
                val id = String(b, i, 4)
                val n = (b[i + 4].toInt() and 255) or ((b[i + 5].toInt() and 255) shl 8) or ((b[i + 6].toInt() and 255) shl 16) or ((b[i + 7].toInt() and 255) shl 24)
                if (id == "fmt ") {
                    ch = (b[i + 10].toInt() and 255) or ((b[i + 11].toInt() and 255) shl 8)
                    rate = (b[i + 12].toInt() and 255) or ((b[i + 13].toInt() and 255) shl 8) or ((b[i + 14].toInt() and 255) shl 16)
                }
                if (id == "data") {
                    var peak = 0
                    var j = i + 8
                    val end = minOf(b.size - 1, i + 8 + n)
                    while (j < end) { val v = abs(((b[j + 1].toInt() shl 8) or (b[j].toInt() and 255)).toShort().toInt()); if (v > peak) peak = v; j += 2 }
                    return (n / 2L / ch * 1000 / rate) to peak / 32768f
                }
                i += 8 + n + (n and 1)
            }
            return 500L to 0.9f
        }
    }
}

/** The mix limiter rule, kept pure for the unit test: the summed peaks of everything sounding stay <= -1 dBFS. */
object MixHeadroom {
    const val CEILING = 0.891f

    fun gain(newPeak: Float, othersPeak: Float): Float {
        if (newPeak <= 0f) return 1f
        val room = CEILING - othersPeak
        return (room / newPeak).coerceIn(0.15f, 1f)
    }
}
