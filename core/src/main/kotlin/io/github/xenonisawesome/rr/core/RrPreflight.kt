package io.github.xenonisawesome.rr.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection

/** Checks run on the EDT before recording or replaying: rr present, platform supported, perf access. */
object RrPreflight {
    private const val TITLE = "rr"

    /** Returns the resolved rr path, or null after showing an explanatory message. EDT only. */
    fun resolveRrOrExplain(project: Project?): String? {
        if (!RrPlatform.isSupported) {
            Messages.showErrorDialog(project, RrPlatform.UNSUPPORTED_MESSAGE, TITLE)
            return null
        }
        val configured = RrSettings.getInstance().rrPath.ifEmpty { null }
        var result: RrBinary.Resolution? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { result = RrBinary.resolve(configured) }, "Looking for rr…", true, project,
        )
        return when (val r = result) {
            is RrBinary.Resolution.Found -> r.path
            is RrBinary.Resolution.Problem -> { Messages.showErrorDialog(project, r.message, TITLE); null }
            null -> null
        }
    }

    /** True if recording can proceed (perf access OK, or fixed after confirmation). EDT only. */
    fun ensurePerfAccess(project: Project?): Boolean {
        var level = PerfEventParanoid.read()
        if (!PerfEventParanoid.isTooRestrictive(level)) return true
        while (true) {
            val canRun = PerfEventParanoid.isPkexecAvailable()
            val options = if (canRun) arrayOf("Run pkexec…", "Copy Command", "Cancel") else arrayOf("Copy Command", "Cancel")
            val message = "${PerfEventParanoid.EXPLANATION}\n\nCurrent value: $level\n\n" +
                (if (canRun) "I can run this with administrator rights (a system password prompt will appear):\n"
                else "pkexec is not installed. Run this in a terminal:\n") +
                "    ${if (canRun) PerfEventParanoid.fixCommand().commandLineString else PerfEventParanoid.MANUAL_COMMAND}\n\n" +
                PerfEventParanoid.PERSIST_HINT
            val choice = Messages.showDialog(project, message, "rr Needs Performance Counter Access", options, 0, Messages.getWarningIcon())
            val picked = options.getOrNull(choice)
            when (picked) {
                "Copy Command" -> {
                    CopyPasteManager.getInstance().setContents(StringSelection(PerfEventParanoid.MANUAL_COMMAND))
                    return false
                }
                "Run pkexec…" -> {
                    // Explicit confirmation was the click above; nothing runs as root without it.
                    var fix: PerfEventParanoid.FixResult? = null
                    ProgressManager.getInstance().run(object : Task.Modal(project, "Waiting for authorisation…", true) {
                        override fun run(indicator: ProgressIndicator) { fix = PerfEventParanoid.applyFix() }
                    })
                    when (val f = fix) {
                        is PerfEventParanoid.FixResult.Fixed -> return true
                        is PerfEventParanoid.FixResult.Declined -> return false
                        is PerfEventParanoid.FixResult.NotEffective -> {
                            level = f.level
                            Messages.showErrorDialog(project, "perf_event_paranoid is still ${f.level}. ${f.details}", TITLE)
                            return false
                        }
                        else -> {
                            Messages.showInfoMessage(
                                project,
                                "Could not run pkexec (no polkit agent?). Run this in a terminal instead:\n\n${PerfEventParanoid.MANUAL_COMMAND}",
                                TITLE,
                            )
                            CopyPasteManager.getInstance().setContents(StringSelection(PerfEventParanoid.MANUAL_COMMAND))
                            return false
                        }
                    }
                }
                else -> return false
            }
        }
    }

    @Suppress("unused")
    private fun assertEdt() = ApplicationManager.getApplication().assertIsDispatchThread()
}
