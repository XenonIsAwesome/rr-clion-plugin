package io.github.xenonisawesome.rr.core

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import java.nio.charset.StandardCharsets

/** Small helpers for running short-lived helper processes. Never call on the EDT. */
object RrProcesses {
    data class Output(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    fun capture(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Int = 30_000,
    ): Output {
        val cmd = GeneralCommandLine(command).withCharset(StandardCharsets.UTF_8).withEnvironment(env)
        return capture(cmd, timeoutMs)
    }

    fun capture(cmd: GeneralCommandLine, timeoutMs: Int): Output {
        return try {
            val o: ProcessOutput = CapturingProcessHandler(cmd).runProcess(timeoutMs)
            Output(o.exitCode, o.stdout, o.stderr, o.isTimeout)
        } catch (e: Exception) {
            Output(-1, "", e.message ?: e.javaClass.simpleName, false)
        }
    }
}
