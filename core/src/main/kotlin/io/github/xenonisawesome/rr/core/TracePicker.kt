package io.github.xenonisawesome.rr.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.swing.JComponent

/** "Pick a trace" popup: latest (default), every `rr ls` entry newest first, or a directory from disk. */
object TracePicker {
    private sealed interface Item {
        data object Latest : Item
        data class Existing(val trace: RrTrace) : Item
        data object Browse : Item
    }

    /** [onChosen] gets the trace directory, or null for "latest". Call on the EDT. */
    fun choose(project: Project?, rrPath: String, anchor: JComponent?, onChosen: (File?) -> Unit) {
        val root = RrTraceDirs.effectiveRoot(RrSettings.getInstance().traceDir)
        ApplicationManager.getApplication().executeOnPooledThread {
            val traces = RrTraces.list(rrPath, root)
            ApplicationManager.getApplication().invokeLater {
                traces.onFailure { Messages.showErrorDialog(project, it.message ?: "rr ls failed", "rr") }
                val items = buildList {
                    add(Item.Latest)
                    traces.getOrDefault(emptyList()).forEach { add(Item.Existing(it)) }
                    add(Item.Browse)
                }
                val step = object : BaseListPopupStep<Item>("Replay Which Recording?", items) {
                    override fun getTextFor(value: Item): String = when (value) {
                        Item.Latest -> "Latest recording (default)"
                        Item.Browse -> "Choose a trace directory…"
                        is Item.Existing -> describe(value.trace)
                    }

                    override fun onChosen(selectedValue: Item, finalChoice: Boolean): PopupStep<*>? {
                        ApplicationManager.getApplication().invokeLater {
                            when (selectedValue) {
                                Item.Latest -> onChosen(null)
                                is Item.Existing -> onChosen(selectedValue.trace.dir)
                                Item.Browse -> browse(project, root, onChosen)
                            }
                        }
                        return FINAL_CHOICE
                    }
                }
                val popup = JBPopupFactory.getInstance().createListPopup(step)
                if (anchor != null) popup.showUnderneathOf(anchor) else popup.showInFocusCenter()
            }
        }
    }

    private fun describe(t: RrTrace): String {
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t.dir.lastModified()))
        return "${t.name}  —  $time" + if (t.incomplete) "  (incomplete)" else ""
    }

    private fun browse(project: Project?, root: String, onChosen: (File?) -> Unit) {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle("Select rr Trace Directory")
            .withDescription("A directory created by rr record, e.g. ~/.local/share/rr/a.out-3")
        val start = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(root)
        FileChooser.chooseFile(descriptor, project, start)?.let { vf ->
            val dir = File(vf.path)
            if (RrTraces.looksLikeTrace(dir)) onChosen(dir)
            else Messages.showErrorDialog(project, "'${dir.path}' does not look like an rr trace (no 'version' file).", "rr")
        }
    }

}
