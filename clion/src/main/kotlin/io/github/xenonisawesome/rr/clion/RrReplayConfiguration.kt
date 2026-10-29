package io.github.xenonisawesome.rr.clion

import com.intellij.execution.Executor
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.xenonisawesome.rr.core.RrBinary
import io.github.xenonisawesome.rr.core.RrPlatform
import io.github.xenonisawesome.rr.core.RrSettings
import io.github.xenonisawesome.rr.core.RrTraces
import io.github.xenonisawesome.rr.core.TracePicker
import java.io.File
import javax.swing.JComponent
import com.intellij.icons.AllIcons

class RrReplayConfigurationType : ConfigurationTypeBase(
    ID, "rr Replay", "Replay an rr recording in the CLion debugger", AllIcons.Actions.StartDebugger,
) {
    init { addFactory(RrReplayConfigurationFactory(this)) }

    companion object {
        const val ID = "RrReplayConfiguration"
    }
}

class RrReplayConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId() = "RrReplay"
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        RrReplayRunConfiguration(project, this, "rr Replay")

    override fun getOptionsClass() = RrReplayOptions::class.java
}

class RrReplayOptions : RunConfigurationOptions() {
    private val askOnRunProperty = property(false).provideDelegate(this, "askOnRun")

    /** Ask which trace to replay when running, as long as no trace has been chosen yet. */
    var askOnRun: Boolean
        get() = askOnRunProperty.getValue(this)
        set(value) { askOnRunProperty.setValue(this, value) }

    private val tracePathProperty = string("").provideDelegate(this, "tracePath")

    /** Trace directory; empty = latest trace. */
    var tracePath: String
        get() = tracePathProperty.getValue(this) ?: ""
        set(value) { tracePathProperty.setValue(this, value) }
}

class RrReplayRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<RrReplayOptions>(project, factory, name) {

    override fun getOptions(): RrReplayOptions = super.getOptions() as RrReplayOptions

    var tracePath: String
        get() = options.tracePath
        set(value) { options.tracePath = value.trim() }

    var askOnRun: Boolean
        get() = options.askOnRun
        set(value) { options.askOnRun = value }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = RrReplayEditor(project)

    override fun checkConfiguration() {
        if (!RrPlatform.isSupported) throw RuntimeConfigurationError(RrPlatform.UNSUPPORTED_MESSAGE)
        if (tracePath.isNotEmpty() && !RrTraces.looksLikeTrace(File(tracePath))) {
            throw RuntimeConfigurationError("'$tracePath' is not an rr trace directory.")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = RrReplayState()
}

/** Placeholder state: the real work happens in [RrReplayRunner], which starts the debug session itself. */
class RrReplayState : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult? = null
}

class RrReplayEditor(private val project: Project) : SettingsEditor<RrReplayRunConfiguration>() {
    private val traceField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select rr Trace Directory"),
        )
    }

    private val askBox = JBCheckBox("Let me pick the trace when running")

    override fun resetEditorFrom(s: RrReplayRunConfiguration) { traceField.text = s.tracePath; askBox.isSelected = s.askOnRun }
    override fun applyEditorTo(s: RrReplayRunConfiguration) { s.tracePath = traceField.text; s.askOnRun = askBox.isSelected }

    override fun createEditor(): JComponent = panel {
        row("Trace directory:") {
            cell(traceField).columns(COLUMNS_LARGE).comment("Leave empty to replay the latest recording (like bare <code>rr replay</code>).")
            button("Pick from rr ls…") {
                val rr = RrBinary.locate(RrSettings.getInstance().rrPath.ifEmpty { null })
                if (rr != null) TracePicker.choose(project, rr.path, traceField) { traceField.text = it?.path ?: "" }
            }
        }
        row {
            cell(askBox).comment(
                "Opens the trace list when you run, unless a trace is already set above. A trace picked then is " +
                    "remembered here; clear the field to be asked again. Choosing \"Latest\" is not remembered.",
            )
        }
    }
}
