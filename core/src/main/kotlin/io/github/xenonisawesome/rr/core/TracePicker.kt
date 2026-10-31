package io.github.xenonisawesome.rr.core

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
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

/** "Pick a trace" popup: latest (default), every `rr ls` entry newest first, a directory from disk, a download from a URL, or a local archive. */
object TracePicker {
    private sealed interface Item {
        data object Latest : Item
        data class Existing(val trace: RrTrace) : Item
        data object Browse : Item
        data object FromUrl : Item
        data object FromArchive : Item
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
                    add(Item.FromUrl)
                    add(Item.FromArchive)
                    add(Item.OpenFolder)
                }
                val step = object : BaseListPopupStep<Item>("Replay Which Recording?", items) {
                    override fun getTextFor(value: Item): String = when (value) {
                        Item.Latest -> "Latest recording (default)"
                        Item.Browse -> "Choose a trace directory…"
                        Item.FromUrl -> "Download a trace from a URL…"
                        Item.FromArchive -> "Unpack a trace from a local archive…"
                        Item.OpenFolder -> "Open trace directory in file explorer…"
                        is Item.Existing -> describe(value.trace)
                    }

                    override fun onChosen(selectedValue: Item, finalChoice: Boolean): PopupStep<*>? {
                        ApplicationManager.getApplication().invokeLater {
                            when (selectedValue) {
                                Item.Latest -> chosen(null)
                                is Item.Existing -> chosen(selectedValue.trace.dir)
                                Item.Browse -> { settled = true; browse(project, root, chosen, onCancelled) }
                                Item.FromUrl -> { settled = true; fromUrl(project, root, chosen, onCancelled) }
                                Item.FromArchive -> { settled = true; fromArchive(project, root, chosen, onCancelled) }
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

    /** Asks for a URL, downloads and unpacks the archive under [root], then reports the trace directory. */
    private fun fromUrl(project: Project?, root: String, onChosen: (File?) -> Unit, onCancelled: () -> Unit) {
        val url = Messages.showInputDialog(
            project,
            "URL of a trace archive (zip, tar, tar.gz, tar.xz, tar.zst, 7z, …):",
            "Download rr Trace",
            null,
            "",
            object : InputValidator {
                override fun checkInput(inputString: String?) = TraceDownloader.validateUrl(inputString.orEmpty()) == null
                override fun canClose(inputString: String?) = checkInput(inputString)
            },
        )?.trim()
        if (url.isNullOrEmpty()) { onCancelled(); return }
        unpackInBackground(project, onChosen, onCancelled) { indicator ->
            TraceDownloader.downloadAndExtract(
                url, File(root),
                progress = { read, total ->
                    if (total > 0) { indicator.isIndeterminate = false; indicator.fraction = read.toDouble() / total }
                    indicator.text2 = "${read / (1024 * 1024)} MB" + if (total > 0) " / ${total / (1024 * 1024)} MB" else ""
                },
                status = { indicator.text = it },
                cancelled = { indicator.isCanceled },
            )
        }
    }

    /** Asks for a local archive file and unpacks it under [root], then reports the trace directory. */
    private fun fromArchive(project: Project?, root: String, onChosen: (File?) -> Unit, onCancelled: () -> Unit) {
        val descriptor = FileChooserDescriptor(true, false, true, true, false, false)
            .withTitle("Select rr Trace Archive")
            .withDescription("An archive of a trace: zip, tar, tar.gz, tar.xz, tar.zst, 7z, …")
        val vf = FileChooser.chooseFile(descriptor, project, null)
        if (vf == null) { onCancelled(); return }
        val file = File(vf.path)
        unpackInBackground(project, onChosen, onCancelled) { indicator ->
            TraceDownloader.extractLocal(file, File(root), status = { indicator.text = it })
        }
    }

    private fun unpackInBackground(
        project: Project?,
        onChosen: (File?) -> Unit,
        onCancelled: () -> Unit,
        work: (ProgressIndicator) -> Result<File>,
    ) {
        var result: Result<File>? = null
        object : Task.Backgroundable(project, "Preparing rr trace", true) {
            override fun run(indicator: ProgressIndicator) {
                result = work(indicator)
            }

            override fun onSuccess() {
                val r = result
                val dir = r?.getOrNull()
                if (dir != null) onChosen(dir)
                else {
                    if (r?.exceptionOrNull()?.message != "Cancelled") {
                        Messages.showErrorDialog(project, r?.exceptionOrNull()?.message ?: "Unpacking failed", "rr")
                    }
                    onCancelled()
                }
            }

            override fun onCancel() = onCancelled()
        }.queue()
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
