package io.github.xenonisawesome.rr.clion

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import io.github.xenonisawesome.rr.core.RrPlatform
import io.github.xenonisawesome.rr.core.RrPreflight
import io.github.xenonisawesome.rr.core.TracePicker
import com.intellij.execution.configurations.ConfigurationTypeUtil

/** "Replay with rr": pick a recording (default: latest) and start a debug session on it. */
class ReplayWithRrAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && RrPlatform.isSupported
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val rr = RrPreflight.resolveRrOrExplain(project) ?: return
        TracePicker.choose(project, rr, null) { trace ->
            val manager = RunManager.getInstance(project)
            val type = ConfigurationTypeUtil.findConfigurationType(RrReplayConfigurationType::class.java)
            val settings = manager.findConfigurationByTypeAndName(type.id, NAME)
                ?: manager.createConfiguration(NAME, type.configurationFactories.first()).also {
                    it.isTemporary = true
                    manager.addConfiguration(it)
                }
            (settings.configuration as RrReplayRunConfiguration).tracePath = trace?.path ?: ""
            manager.selectedConfiguration = settings
            ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
        }
    }

    companion object {
        const val NAME = "rr Replay"
    }
}
