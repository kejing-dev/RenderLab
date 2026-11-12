package io.embodit.renderlab.ui.control

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import androidx.compose.foundation.selection.toggleable
import com.pico.spatial.ui.design.Text
import io.embodit.renderlab.platform.TAG
import io.embodit.renderlab.ui.theme.LabColors

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; ui-audit runs read the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

/** Main action button: 64 dp tall (B31, QA q1-1), side by side with >= 16 dp gaps ([BUTTON_GAP]). */
@Composable
internal fun PrimaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null, container: Color? = null) {
    val fg = PicoTheme.colorScheme.labelPrimaryLight
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 160.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container ?: PicoTheme.colorScheme.interaction, contentColor = fg),
    ) {
        if (icon != null) {
            com.pico.spatial.ui.design.Icon(painter = androidx.compose.ui.res.painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = fg)
            Box(Modifier.width(8.dp))
        }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/primary", style = PicoTheme.typography.titleMedium, color = fg)
    }
}

@Composable
internal fun SecondaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, icon: Int? = null, highlight: Boolean = false) {
    val strong = danger || highlight
    val fg = if (strong) LabColors.OnError else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 96.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when { danger -> LabColors.Error; highlight -> LabColors.Primary; else -> LabColors.ButtonFace },
            contentColor = fg,
        ),
    ) {
        if (icon != null) {
            com.pico.spatial.ui.design.Icon(painter = androidx.compose.ui.res.painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = fg)
            Box(Modifier.width(6.dp))
        }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/button", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

