package io.github.xenonisawesome.rr.core

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
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
        data object OpenFolder : Item
    }

    /** [onChosen] gets the trace directory, or null for "latest". Call on the EDT. */
    fun choose(
        project: Project?,
        rrPath: String,
        anchor: JComponent?,
        onCancelled: () -> Unit = {},
        onChosen: (File?) -> Unit,
    ) {
        var settled = false
        val chosen: (File?) -> Unit = { settled = true; onChosen(it) }
        val root = RrTraceDirs.effectiveRoot(RrSettings.getInstance().traceDir)
        ApplicationManager.getApplication().executeOnPooledThread {
            val traces = RrTraces.list(rrPath, root)
            ApplicationManager.getApplication().invokeLater {
                traces.onFailure { Messages.showErrorDialog(project, it.message ?: "rr ls failed", "rr") }
                val items = buildList {
                    add(Item.Latest)
                    traces.getOrDefault(emptyList()).forEach { add(Item.Existing(it)) }
                    add(Item.Browse)
                    add(Item.OpenFolder)
                }
                val step = object : BaseListPopupStep<Item>("Replay Which Recording?", items) {
                    override fun getTextFor(value: Item): String = when (value) {
                        Item.Latest -> "Latest recording (default)"
                        Item.Browse -> "Choose a trace directory…"
                        Item.OpenFolder -> "Open trace directory in file explorer…"
                        is Item.Existing -> describe(value.trace)
                    }

                    override fun onChosen(selectedValue: Item, finalChoice: Boolean): PopupStep<*>? {
                        ApplicationManager.getApplication().invokeLater {
                            when (selectedValue) {
                                Item.Latest -> chosen(null)
                                is Item.Existing -> chosen(selectedValue.trace.dir)
                                Item.Browse -> { settled = true; browse(project, root, chosen, onCancelled) }
                                Item.OpenFolder -> {
                                    // Opening the folder is not a choice: show the picker again afterwards.
                                    settled = true
                                    openInFileExplorer(project, root)
                                    choose(project, rrPath, anchor, onCancelled, onChosen)
                                }
                            }
                        }
                        return FINAL_CHOICE
                    }
                }
                val popup = JBPopupFactory.getInstance().createListPopup(step)
                popup.addListener(object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) {
                        // Selection handling is deferred with invokeLater, so settle after it had a chance to run.
                        ApplicationManager.getApplication().invokeLater { if (!settled) { settled = true; onCancelled() } }
                    }
                })
                if (anchor != null) popup.showUnderneathOf(anchor) else popup.showInFocusCenter()
            }
        }
    }

    private fun openInFileExplorer(project: Project?, root: String) {
        val dir = File(root)
        if (dir.isDirectory) RevealFileAction.openDirectory(dir)
        else Messages.showErrorDialog(project, "The trace directory '$root' does not exist yet. Record something with rr first.", "rr")
    }

    private fun describe(t: RrTrace): String {
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t.dir.lastModified()))
        return "${t.name}  —  $time" + if (t.incomplete) "  (incomplete)" else ""
    }

    private fun browse(project: Project?, root: String, onChosen: (File?) -> Unit, onCancelled: () -> Unit) {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle("Select rr Trace Directory")
            .withDescription("A directory created by rr record, e.g. ~/.local/share/rr/a.out-3")
        val start = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(root)
        val vf = FileChooser.chooseFile(descriptor, project, start)
        if (vf == null) { onCancelled(); return }
        val dir = File(vf.path)
        if (RrTraces.looksLikeTrace(dir)) onChosen(dir)
        else {
            Messages.showErrorDialog(project, "'${dir.path}' does not look like an rr trace (no 'version' file).", "rr")
            onCancelled()
        }
    }

}
