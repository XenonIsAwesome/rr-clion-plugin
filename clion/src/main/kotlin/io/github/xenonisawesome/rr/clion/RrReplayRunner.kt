package io.github.xenonisawesome.rr.clion

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import io.github.xenonisawesome.rr.core.RrPreflight
import io.github.xenonisawesome.rr.core.RrReplayServer
import io.github.xenonisawesome.rr.core.RrSettings
import io.github.xenonisawesome.rr.core.RrTraceDirs
import io.github.xenonisawesome.rr.core.RrTraces
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise
import java.io.File

/**
 * Debug-executor runner for [RrReplayRunConfiguration]. Starts `rr replay -s <port> -k`, waits for
 * the port, then opens a normal CLion GDB session connected to it (the Remote Debug flow).
 */
class RrReplayRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId() = "RrReplayRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is RrReplayRunConfiguration

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        val project = environment.project
        val config = environment.runProfile as RrReplayRunConfiguration
        val rr = RrPreflight.resolveRrOrExplain(project) ?: return resolvedPromise(null)
        NativeDebugBridge.instance.toolchainProblem(project)?.let {
            Messages.showErrorDialog(project, it, "rr Replay")
            return resolvedPromise(null)
        }

        val promise = AsyncPromise<RunContentDescriptor?>()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Starting rr replay…", true) {
            override fun run(indicator: ProgressIndicator) {
                var server: RrReplayServer? = null
                var gdbInit: File? = null
                try {
                    val settings = RrSettings.getInstance()
                    val root = RrTraceDirs.effectiveRoot(settings.traceDir)
                    val trace = resolveTrace(config.tracePath, root)
                    server = RrReplayServer.start(rr, trace, settings.traceDir.ifEmpty { null })
                    server.awaitReady(30_000) { indicator.checkCanceled() }?.let { throw ExecutionException(it) }
                    gdbInit = RrReplayServer.writeGdbInit(rr)
                    val srv = server
                    val init = gdbInit
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            val starter = object : XDebugProcessStarter() {
                                override fun start(session: XDebugSession): XDebugProcess =
                                    startProcess(session, project, srv, init)
                            }
                            val result = XDebuggerManager.getInstance(project).newSessionBuilder(starter)
                                .environment(environment).startSession()
                            promise.setResult(result.runContentDescriptor)
                        } catch (e: Throwable) {
                            srv.stop(); init?.delete()
                            promise.setError(e)
                        }
                    }
                } catch (e: ProcessCanceledException) {
                    server?.stop(); gdbInit?.delete()
                    promise.setResult(null)
                } catch (e: Throwable) {
                    server?.stop(); gdbInit?.delete()
                    promise.setError(if (e is ExecutionException) e else ExecutionException(e.message ?: e.toString(), e))
                }
            }
        })
        return promise
    }

    private fun startProcess(
        session: XDebugSession,
        project: com.intellij.openapi.project.Project,
        server: RrReplayServer,
        gdbInit: File?,
    ): XDebugProcess {
        val process = NativeDebugBridge.instance.createReplayProcess(session, project, server, gdbInit)
        // The replayed program's output is produced by the rr server process; show it in the console.
        server.addOutputSink { text, isErr ->
            ApplicationManager.getApplication().invokeLater {
                session.consoleView?.print(
                    text,
                    if (isErr) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.NORMAL_OUTPUT,
                )
            }
        }
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionStopped() {
                server.stop()
                gdbInit?.delete()
            }
        })
        return process
    }

    /** null = rr's default (latest). */
    private fun resolveTrace(path: String, root: String): File? {
        if (path.isEmpty()) {
            RrTraces.latest(root) ?: throw ExecutionException(
                "No recordings found in $root. Use \"Record with rr\" first, or pick a trace directory.",
            )
            return null
        }
        val dir = File(path)
        if (!RrTraces.looksLikeTrace(dir)) throw ExecutionException("'$path' is not an rr trace directory.")
        if (File(dir, "incomplete").exists()) {
            throw ExecutionException("The trace '$path' is incomplete (the recording was interrupted) and cannot be replayed.")
        }
        return dir
    }
}
