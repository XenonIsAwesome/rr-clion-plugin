package io.github.xenonisawesome.rr.clion

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import io.github.xenonisawesome.rr.clion.cidr.CidrBridge
import io.github.xenonisawesome.rr.core.RrReplayServer
import java.io.File

/** Everything the plugin needs from the (unofficial) CIDR API. Implemented in exactly one file. */
interface NativeDebugBridge {
    fun isNativeSession(session: XDebugSession): Boolean

    /** Runs a raw GDB console command in the session. [onDone] gets the output or the failure. */
    fun runDebuggerCommand(session: XDebugSession, command: String, onDone: (Result<String>) -> Unit = {})

    /** Non-null = a user-readable reason why rr cannot be used with the active toolchain. */
    fun toolchainProblem(project: Project): String?

    fun isCidrRunConfiguration(profile: RunProfile): Boolean

    /**
     * Builds a GDB process that connects to [server] (already listening). If [state] belongs to a test
     * configuration (e.g. Google Test), the process gets that configuration's test-results console.
     */
    fun createReplayProcess(
        session: XDebugSession,
        project: Project,
        server: RrReplayServer,
        gdbInit: File?,
        trace: File?,
        state: RunProfileState?,
    ): XDebugProcess

    /**
     * Feeds the replayed program's output (produced by the rr server process) into the test-results console so the
     * test tree is populated. Returns false if [process] has no test console; the caller then prints it as plain text.
     */
    fun attachTestOutput(process: XDebugProcess, state: RunProfileState?, server: RrReplayServer): Boolean

    companion object {
        val instance: NativeDebugBridge by lazy { CidrBridge() }
    }
}
