package io.github.xenonisawesome.rr.clion

import com.intellij.execution.Executor
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import com.intellij.openapi.wm.ToolWindowId
import javax.swing.Icon

/** The "Replay with rr" button next to Run, Debug and Record. Opens a debug session on a recording. */
class RrReplayExecutor : Executor() {
    private val icon: Icon = IconUtil.flip(AllIcons.Actions.Resume, true) // same as the Reverse Continue button

    override fun getToolWindowId() = ToolWindowId.DEBUG
    override fun getToolWindowIcon(): Icon = AllIcons.Toolwindows.ToolWindowDebugger
    override fun getIcon(): Icon = icon
    override fun getDisabledIcon(): Icon = IconLoader.getDisabledIcon(icon)
    override fun getDescription() = "Replay an rr recording in the debugger"
    override fun getActionName() = "Replay"
    override fun getId() = ID
    override fun getStartActionText() = "Replay with rr"
    override fun getStartActionText(configurationName: String) = "Replay '$configurationName' with rr"
    override fun getContextActionId() = "RrReplayClass"
    override fun getHelpId(): String? = null

    companion object {
        const val ID = "RrReplay"
    }
}
