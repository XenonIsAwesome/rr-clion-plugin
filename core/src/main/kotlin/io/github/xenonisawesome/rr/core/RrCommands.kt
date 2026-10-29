package io.github.xenonisawesome.rr.core

import java.io.File

/** Pure construction of rr command lines. No IDE state, so it is trivially testable. */
object RrCommands {
    /** `rr record [flags] [-o outDir] program args...`. */
    fun record(
        rr: String,
        extraFlags: List<String>,
        outputDir: String?,
        program: String,
        programArgs: List<String>,
    ): List<String> = buildList {
        add(rr)
        add("record")
        addAll(extraFlags)
        if (!outputDir.isNullOrBlank()) { add("-o"); add(outputDir) }
        add(program)
        addAll(programArgs)
    }

    /** `rr replay -s <port> -k [trace]`: debug server only, keep listening after detach. */
    fun replayServer(rr: String, port: Int, trace: File?): List<String> = buildList {
        add(rr); add("replay"); add("-s"); add(port.toString()); add("-k")
        if (trace != null) add(trace.path)
    }

    /** `rr gdbinit`: gdb macros rr normally injects itself (restart, seek-ticks, hook-run, checkpoints). */
    fun gdbInit(rr: String): List<String> = listOf(rr, "gdbinit")

    /** Environment for rr processes so a configured trace root applies. */
    fun env(configuredTraceDir: String?): Map<String, String> =
        configuredTraceDir?.takeIf { it.isNotBlank() }?.let { mapOf(RrTraceDirs.ENV_VAR to it) } ?: emptyMap()
}
