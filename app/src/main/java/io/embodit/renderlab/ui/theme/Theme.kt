package io.embodit.renderlab.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.renderlab.domain.Language
import io.embodit.renderlab.platform.Graph
import java.util.Locale

/** Studio teal (icon #1F4E5A) + warm key gold; text roles measured on the window glass (D13). */
object LabColors {
    val Primary = Color(0xFF1F5866)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFD5ECEF)
    val Secondary = Color(0xFF6B4A33)
    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F23)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF3A3F44)
    val Amber = Color(0xFFE0A040)
    /** Cards drawn on the glass (alignment card, shot card, result card). */
    val CardFace = Color(0xFFF4F1EB)
    /** Secondary button face (opaque so contrast is measured on a known colour). */
    val ButtonFace = Color(0xFFE4E1DB)
    /** The top-down light diagram background (dark studio floor) and its strokes. */
    val Diagram = Color(0xFF26292E)
    val DiagramLine = Color(0xFF9AA0A6)
    val Good = Color(0xFF1E7A4C)
    val Bad = Color(0xFF8A3A2E)
    val Star = Color(0xFFB7791F)
    val StarOff = Color(0xFF75757B)
    val Key = Color(0xFFFFD27A)
    val Fill = Color(0xFF9CC7FF)
    val Rim = Color(0xFFE7A6FF)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). The language setting localises resources below this
 * point through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun LabTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = LabColors.OnPrimary,
        labelPrimary = LabColors.Ink,
        labelSecondary = LabColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = LabColors.Error,
        alert = LabColors.Amber,
        passable = LabColors.Secondary,
        interaction = LabColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
