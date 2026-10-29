package io.github.xenonisawesome.rr.clion

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import io.github.xenonisawesome.rr.core.RrPreflight
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise

/**
 * Runs a CMake/CLion configuration like "Run without debugging". The command line is wrapped in
 * `rr record` by [io.github.xenonisawesome.rr.clion.cidr.RrRecordExtension], keyed on [RUNNER_ID].
 */
class RrRecordRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId() = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == RrRecordExecutor.ID && NativeDebugBridge.instance.isCidrRunConfiguration(profile)

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        val project = environment.project
        if (RrPreflight.resolveRrOrExplain(project) == null) return resolvedPromise(null)
        if (!RrPreflight.ensurePerfAccess(project)) return resolvedPromise(null)
        val result = state.execute(environment.executor, this)
            ?: throw ExecutionException("The run configuration did not start a process.")
        return resolvedPromise(RunContentBuilder(result, environment).showRunContent(environment.contentToReuse))
    }

    companion object {
        const val RUNNER_ID = "RrRecordRunner"
    }
}
