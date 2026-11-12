package io.embodit.renderlab

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.renderlab.ui.Containers
import io.embodit.renderlab.ui.control.ControlWindow
import io.embodit.renderlab.ui.studio.StudioScreen
import io.embodit.renderlab.ui.theme.LabTheme

/**
 * Full Space (user change 2026-11-12): one Mixed Stage holds the tabletop studio, placed once in front of you at desk
 * height (and again on [叫回面前]); one Planar system window (600 x 572 dp, min 560 x 520, max 900 x 700) holds every 2D
 * page. The Stage asks for the window when it starts; the system opens it straight ahead once the head is level.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            LabTheme { StudioScreen() }
        }
        WindowContainer(Containers.CONTROL, form = Form.Planar, defaultSize = WindowContainerSize(width = 600.dp, height = 572.dp)) {
            LabTheme {
                Box(Modifier.windowConstraints(minWidth = 560.dp, minHeight = 520.dp, maxWidth = 900.dp, maxHeight = 700.dp)) { ControlWindow() }
            }
        }
    }
