package io.github.xenonisawesome.rr.clion

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.util.IconUtil
import io.github.xenonisawesome.rr.core.RrPlatform
import javax.swing.Icon

private fun AnActionEvent.debugSession(): XDebugSession? =
    getData(XDebugSession.DATA_KEY) ?: project?.let { XDebuggerManager.getInstance(it).currentSession }

internal fun notify(project: Project?, type: NotificationType, title: String, content: String) {
    ApplicationManager.getApplication().invokeLater {
        NotificationGroupManager.getInstance().getNotificationGroup("rr").createNotification(title, content, type).notify(project)
    }
}

/** Base for the toolbar actions: visible only in a native (GDB/LLDB) session on Linux, enabled while paused. */
abstract class RrDebuggerAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    protected open val needsSuspended = true

    override fun update(e: AnActionEvent) {
        val session = e.debugSession()
        val native = RrPlatform.isSupported && session != null && NativeDebugBridge.instance.isNativeSession(session)
        e.presentation.isVisible = native
        e.presentation.isEnabled = native && (!needsSuspended || session.isSuspended)
    }

    protected fun send(e: AnActionEvent, command: String, onOk: (String) -> Unit = {}) {
        val session = e.debugSession() ?: return
        val project = e.project
        NativeDebugBridge.instance.runDebuggerCommand(session, command) { result ->
            result.onSuccess(onOk).onFailure {
                notify(project, NotificationType.ERROR, "rr", "'$command' failed: ${it.message}")
            }
        }
    }
}

/** Sends one fixed GDB reverse-execution command. */
abstract class RrReverseAction(private val gdbCommand: String, forwardIcon: Icon) : RrDebuggerAction() {
    init {
        // Same icon as the matching forward debugger action, mirrored horizontally.
        templatePresentation.icon = IconUtil.flip(forwardIcon, true)
    }

    override fun actionPerformed(e: AnActionEvent) = send(e, gdbCommand)
}

class RrReverseContinueAction : RrReverseAction("reverse-continue", AllIcons.Actions.Resume)
class RrReverseStepIntoAction : RrReverseAction("reverse-step", AllIcons.Actions.TraceInto)
class RrReverseStepOverAction : RrReverseAction("reverse-next", AllIcons.Actions.TraceOver)
class RrReverseStepOutAction : RrReverseAction("reverse-finish", AllIcons.Actions.StepOut)

/** `when`: reports the current rr event number. */
class RrShowCurrentEventAction : RrDebuggerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        send(e, "when") { out -> notify(project, NotificationType.INFORMATION, "rr", out.trim().ifEmpty { "No output from 'when'." }) }
    }
}

/** `run <event>`: jumps to an event. Needs gdb's extended-remote mode; see docs/spike-notes.md. */
class RrGoToEventAction : RrDebuggerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val event = Messages.showInputDialog(
            e.project, "Event number to jump to:", "Go to rr Event", null, "",
            object : com.intellij.openapi.ui.InputValidatorEx {
                override fun checkInput(inputString: String?) = (inputString?.trim()?.toLongOrNull() ?: -1) >= 0
                override fun canClose(inputString: String?) = checkInput(inputString)
                override fun getErrorText(inputString: String?) =
                    if (inputString.isNullOrBlank() || checkInput(inputString)) null else "Enter a non-negative whole number"
            },
        )?.trim() ?: return
        send(e, "run $event")
    }
}
