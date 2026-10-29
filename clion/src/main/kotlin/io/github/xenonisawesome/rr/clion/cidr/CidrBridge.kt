package io.github.xenonisawesome.rr.clion.cidr

// The ONLY file that imports com.jetbrains.cidr. If a CLion release breaks the unofficial API,
// this is the one place to fix.
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilder
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.jetbrains.cidr.execution.CidrConsoleBuilder
import com.jetbrains.cidr.execution.testing.CidrTestCommandLineState
import com.jetbrains.cidr.cpp.execution.debugger.backend.CLionGDBDriverConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPToolSet
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains
import com.jetbrains.cidr.execution.CidrRunConfigurationExtensionBase
import com.jetbrains.cidr.execution.ConfigurationExtensionContext
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess
import com.jetbrains.cidr.execution.debugger.remote.CidrRemoteDebugParameters
import com.jetbrains.cidr.execution.debugger.remote.CidrRemoteGDBDebugProcess
import com.jetbrains.cidr.lang.toolchains.CidrToolEnvironment
import com.jetbrains.cidr.lang.workspace.OCRunConfiguration
import io.github.xenonisawesome.rr.clion.NativeDebugBridge
import io.github.xenonisawesome.rr.clion.RrRecordRunner
import io.github.xenonisawesome.rr.core.RrBinary
import io.github.xenonisawesome.rr.core.RrCommands
import io.github.xenonisawesome.rr.core.RrReplayServer
import io.github.xenonisawesome.rr.core.RrSettings
import io.github.xenonisawesome.rr.core.RrTraces
import java.io.File
import java.nio.file.Path

class CidrBridge : NativeDebugBridge {
    private val LOG = Logger.getInstance(CidrBridge::class.java)

    override fun isNativeSession(session: XDebugSession): Boolean = session.debugProcess is CidrDebugProcess

    override fun runDebuggerCommand(session: XDebugSession, command: String, onDone: (Result<String>) -> Unit) {
        val process = session.debugProcess as? CidrDebugProcess
        if (process == null) {
            onDone(Result.failure(IllegalStateException("Not a native debug session")))
            return
        }
        process.postCommand(
            CidrDebugProcess.SuspendedDebuggerCommand<String> { driver -> driver.executeInterpreterCommand(command) },
        ).whenComplete { out, err ->
            onDone(if (err != null) Result.failure(err.cause ?: err) else Result.success(out.orEmpty()))
        }
    }

    override fun toolchainProblem(project: Project): String? {
        val toolchain = CPPToolchains.getInstance().defaultToolchain
            ?: return "No C/C++ toolchain is configured (Settings | Build, Execution, Deployment | Toolchains)."
        when (toolchain.toolSetKind) {
            CPPToolSet.Kind.WSL, CPPToolSet.Kind.SSH, CPPToolSet.Kind.DOCKER ->
                return "rr is not supported with remote, Docker or WSL toolchains (toolchain '${toolchain.name}'). " +
                    "Switch to a local toolchain."
            else -> Unit
        }
        // LLDB toolchains are not rejected: the only API that exposes the debugger kind is scheduled for removal.
        return null
    }

    override fun isCidrRunConfiguration(profile: RunProfile): Boolean = profile is OCRunConfiguration<*, *>

    // Same trick as CLionTestLauncher.createConsoleBuilder: a test state builds its own test-results console.
    private fun consoleBuilderFor(project: Project, state: RunProfileState?): TextConsoleBuilder {
        if (state is CidrTestCommandLineState<*>) {
            val dir = Path.of(project.basePath ?: ".")
            return object : CidrConsoleBuilder(project, CidrToolEnvironment(), dir) {
                override fun createConsole(): ConsoleView = state.createConsole(this)
            }
        }
        return TextConsoleBuilderFactory.getInstance().createBuilder(project)
    }

    override fun attachTestOutput(process: XDebugProcess, state: RunProfileState?, server: RrReplayServer): Boolean {
        if (state !is CidrTestCommandLineState<*>) return false
        val console = (process as? CidrDebugProcess)?.getConsole() ?: return false
        console.attachToProcess(server.processHandler)
        return true
    }

    override fun createReplayProcess(
        session: XDebugSession,
        project: Project,
        server: RrReplayServer,
        gdbInit: File?,
        trace: File?,
        state: RunProfileState?,
    ): XDebugProcess {
        val toolchain = CPPToolchains.getInstance().defaultToolchain
            ?: throw ExecutionException("No C/C++ toolchain is configured.")
        val info = server.launchInfo
        val host = info?.host ?: "127.0.0.1"
        val exe = info?.executable?.takeIf { File(it).isFile }
            ?: trace?.let { RrTraces.guessExecutable(it) }?.path
        LOG.info("rr replay: connect=$host:${server.port} launchInfo=$info symbolFile=$exe trace=$trace gdbInit=$gdbInit")
        val params = CidrRemoteDebugParameters(
            "$host:${server.port}",
            exe.orEmpty(),
            "/", // equivalent of `set sysroot /`, which rr requires
            emptyList(),
            0,
            // rr's own macros (restart, seek-ticks, hook-run, checkpoints) are only injected when rr starts gdb itself.
            // Explicit `file` as a belt-and-braces fallback in case the symbol file parameter is not applied.
            listOfNotNull(exe?.let { "file $it" }, gdbInit?.let { "source ${it.path}" }),
        )
        val process = CidrRemoteGDBDebugProcess(
            CLionGDBDriverConfiguration(project, toolchain),
            params,
            session,
            consoleBuilderFor(project, state),
        )
        // CLion's own launchers (CidrLauncher.startDebugProcess) call start() on the EDT right after creating
        // the process; without it GDB is spawned but never loads the file or connects to the target.
        process.start()
        return process
    }
}

/**
 * Wraps the debuggee command line in `rr record` when the run was started by [RrRecordRunner].
 * Hooked in through the `cidr.runConfigurationExtension` extension point.
 */
class RrRecordExtension : CidrRunConfigurationExtensionBase() {
    override fun isApplicableFor(configuration: OCRunConfiguration<*, *>): Boolean = true

    override fun isEnabledFor(
        applicableConfiguration: OCRunConfiguration<*, *>,
        environment: CidrToolEnvironment,
        runnerSettings: RunnerSettings?,
    ): Boolean = true

    override fun patchCommandLine(
        configuration: OCRunConfiguration<*, *>,
        runnerSettings: RunnerSettings?,
        environment: CidrToolEnvironment,
        cmdLine: GeneralCommandLine,
        runnerId: String,
        context: ConfigurationExtensionContext,
    ) {
        if (runnerId != RrRecordRunner.RUNNER_ID) return
        val host = environment.hostMachine
        if (host.isRemote || host.isWsl) {
            throw ExecutionException("Recording with rr is not supported on remote, Docker or WSL toolchains.")
        }
        val settings = RrSettings.getInstance()
        val rr = RrBinary.locate(settings.rrPath.ifEmpty { null })
            ?: throw ExecutionException("rr was not found. Install it or set its path in Settings | Tools | rr.")
        val program = cmdLine.exePath
        val programArgs = cmdLine.parametersList.list.toList()
        val full = RrCommands.record(rr.path, settings.recordFlagList(), null, program, programArgs)
        cmdLine.exePath = full.first()
        cmdLine.parametersList.clearAll()
        cmdLine.parametersList.addAll(full.drop(1))
        cmdLine.withEnvironment(RrCommands.env(settings.traceDir.ifEmpty { null }))
    }
}
