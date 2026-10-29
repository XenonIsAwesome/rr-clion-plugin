package io.github.xenonisawesome.rr.clion

import com.intellij.execution.Executor
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowId
import javax.swing.Icon

/** The "Record with rr" button next to Run and Debug. Output goes to the regular Run tool window. */
class RrRecordExecutor : Executor() {
    private val icon: Icon = AllIcons.Debugger.Db_set_breakpoint

    override fun getToolWindowId() = ToolWindowId.RUN
    override fun getToolWindowIcon(): Icon = AllIcons.Toolwindows.ToolWindowRun
    override fun getIcon(): Icon = icon
    override fun getDisabledIcon(): Icon = IconLoader.getDisabledIcon(icon)
    override fun getDescription() = "Run the selected configuration under rr record"
    override fun getActionName() = "Record with rr"
    override fun getId() = ID
    override fun getStartActionText() = "Record with rr"
    override fun getStartActionText(configurationName: String) = "Record '$configurationName' with rr"
    override fun getContextActionId() = "RrRecordClass"
    override fun getHelpId(): String? = null

    companion object {
        const val ID = "RrRecord"
    }
}
