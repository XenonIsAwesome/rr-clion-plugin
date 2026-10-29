package io.github.xenonisawesome.rr.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep

/** "Which process do you want to debug?" for recordings that contain several (e.g. ctest -> test binaries). */
object ProcessPicker {
    /** [onChosen] gets the pid. Call on the EDT. */
    fun choose(processes: List<RrProcess>, onCancelled: () -> Unit, onChosen: (RrProcess) -> Unit) {
        var settled = false
        val step = object : BaseListPopupStep<RrProcess>("Debug Which Process?", processes) {
            override fun getTextFor(value: RrProcess): String {
                val exit = value.exitCode?.let { "  (exit $it)" }.orEmpty()
                val root = if (value.isRoot) "  [root]" else ""
                return "${value.pid}  ${value.command.take(160)}$exit$root"
            }

            override fun onChosen(selectedValue: RrProcess, finalChoice: Boolean): PopupStep<*>? {
                settled = true
                ApplicationManager.getApplication().invokeLater { onChosen(selectedValue) }
                return FINAL_CHOICE
            }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(step, 12)
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                ApplicationManager.getApplication().invokeLater { if (!settled) { settled = true; onCancelled() } }
            }
        })
        popup.showInFocusCenter()
    }
}
