package io.github.xenonisawesome.rr.clion

import com.intellij.execution.configurations.RunProfile
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

    /** Builds a GDB process that connects to [server] (already listening). */
    fun createReplayProcess(session: XDebugSession, project: Project, server: RrReplayServer, gdbInit: File?, trace: File?): XDebugProcess

    companion object {
        val instance: NativeDebugBridge by lazy { CidrBridge() }
    }
}
