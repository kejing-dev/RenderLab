package io.embodit.renderlab.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The studio is the default Mixed Stage (manifest). */
object Containers {
    const val STUDIO = "RenderLabStage"
    const val CONTROL = "RenderLabControl"
    const val CONTROL_TAG = "control"
}

/** The three pages of the control window (design panels.control: 打光 / 题卡 / 设置). */
enum class Page(val stateId: String) { LIGHT("S02"), SHOTS("S08"), SETTINGS("S11") }

/** Window-page state shared process-wide so debug commands and the flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.LIGHT)
    var exitConfirm by mutableStateOf(false)
    var clearConfirm by mutableStateOf(false)
}
