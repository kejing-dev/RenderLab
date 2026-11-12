package io.embodit.renderlab.data

import android.content.Context
import android.util.Log
import io.embodit.renderlab.domain.Language
import io.embodit.renderlab.domain.SaveCodec
import io.embodit.renderlab.domain.SaveStore
import io.embodit.renderlab.domain.Saved
import io.embodit.renderlab.domain.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Atomic text file: temp file, read-back check, rename; the previous good copy stays as .bak (Chess Stores.kt pattern). */
private fun atomicWrite(dir: File, name: String, text: String, check: (String) -> Boolean) {
    val file = File(dir, name)
    val tmp = File(dir, "$name.tmp")
    tmp.writeText(text)
    check(check(tmp.readText())) { "read-back mismatch" }
    if (file.exists()) file.copyTo(File(dir, "$name.bak"), overwrite = true)
    if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
}

/** files/progress.txt (design offline.save): best stars per shot, where you were, the lights, the free-light picks. */
class FileSaveStore(context: Context) : SaveStore {
    private val dir = context.filesDir

    override fun load(): Saved? =
        listOf("progress.txt", "progress.txt.bak").firstNotNullOfOrNull { n -> runCatching { File(dir, n).takeIf { it.exists() }?.let { SaveCodec.decode(it.readText()) } }.getOrNull() }

    override fun save(s: Saved) {
        val text = SaveCodec.encode(s)
        runCatching { atomicWrite(dir, "progress.txt", text) { SaveCodec.decode(it) == s } }.onFailure { Log.e("RenderLab", "SAVE failed", it) }
    }

    override fun clear() { File(dir, "progress.txt").delete(); File(dir, "progress.txt.bak").delete() }
}

/** App settings in SharedPreferences, exposed as a StateFlow so both windows recompose on change. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    fun set(s: Settings) {
        _settings.value = s
        prefs.edit().putString("language", s.language.name).putInt("volume", s.volume).putInt("lastVolume", s.lastVolume).apply()
    }

    /** Title-bar [声音] (T11): mute <-> the last audible level (remembered). */
    fun toggleMute() = update { if (it.volume == 0) it.copy(volume = it.lastVolume.coerceIn(1, 3)) else it.copy(volume = 0, lastVolume = it.volume) }

    fun setVolume(v: Int) = update { it.copy(volume = v.coerceIn(0, 3), lastVolume = if (v > 0) v else it.lastVolume) }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            volume = prefs.getInt("volume", 2).coerceIn(0, 3),
            lastVolume = prefs.getInt("lastVolume", 2).coerceIn(1, 3),
        )
    }.getOrElse { Settings() }
}
