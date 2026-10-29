package io.github.xenonisawesome.rr.core

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.nio.charset.StandardCharsets

/** What `rr replay -s` tells us on stderr about how to connect (the "Launch gdb with" block). */
data class RrLaunchInfo(val host: String, val port: Int, val executable: String?)

object RrLaunchLine {
    private val TOKEN = Regex("'([^']*)'|(\\S+)")
    private val TARGET = Regex("""target extended-remote\s+(\S*):(\d+)""")

    /** Parses the shell-quoted `gdb '-l' '10000' '-ex' ... '/path/to/exe'` line. Null until it appears. */
    fun parse(text: String): RrLaunchInfo? {
        val idx = text.indexOf("Launch gdb with")
        if (idx < 0) return null
        val line = text.substring(idx).lineSequence().drop(1).firstOrNull { it.contains("gdb") } ?: return null
        val tokens = TOKEN.findAll(line.substringAfter("gdb")).map { it.groups[1]?.value ?: it.groupValues[2] }.toList()
        var host: String? = null
        var port: Int? = null
        val positional = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "-ex" && i + 1 < tokens.size -> {
                    TARGET.find(tokens[i + 1])?.let { host = it.groupValues[1].ifEmpty { null }; port = it.groupValues[2].toInt() }
                    i += 2
                }
                t.startsWith("-") -> i += 2 // option with a value, e.g. -l 10000
                else -> { positional += t; i++ }
            }
        }
        val p = port ?: return null
        return RrLaunchInfo(host ?: "127.0.0.1", p, positional.joinToString(" ").ifEmpty { null })
    }
}

/**
 * A running `rr replay -s <port> -k [trace]` debug server. The caller connects a debugger to
 * [port] and must call [stop] when the session ends.
 */
class RrReplayServer private constructor(
    val port: Int,
    private val handler: OSProcessHandler,
) {
    private val buffer = StringBuilder()
    private val sinks = mutableListOf<(String, Boolean) -> Unit>()

    @Volatile var launchInfo: RrLaunchInfo? = null
        private set

    val isAlive: Boolean get() = !handler.isProcessTerminated

    val processHandler: OSProcessHandler get() = handler

    init {
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val isErr = outputType == ProcessOutputTypes.STDERR
                val snapshot: List<(String, Boolean) -> Unit>
                synchronized(this@RrReplayServer) {
                    buffer.append(event.text)
                    if (launchInfo == null) launchInfo = RrLaunchLine.parse(buffer.toString())
                    snapshot = sinks.toList()
                }
                snapshot.forEach { it(event.text, isErr) }
            }
        })
        handler.startNotify()
    }

    /** Receives everything printed so far, then everything printed later. Text is (text, isStderr). */
    fun addOutputSink(sink: (String, Boolean) -> Unit) {
        val backlog: String
        synchronized(this) {
            backlog = buffer.toString()
            sinks += sink
        }
        if (backlog.isNotEmpty()) sink(backlog, false)
    }

    fun output(): String = synchronized(this) { buffer.toString() }

    /**
     * Blocks until the server accepts a debugger (port listening and the launch line seen), the
     * process dies, or [timeoutMs] passes. Returns null on success, else a human-readable error.
     */
    fun awaitReady(timeoutMs: Long, checkCanceled: () -> Unit = {}): String? {
        val ok = Ports.waitForListening(port, timeoutMs, isAlive = { isAlive }, checkCanceled = checkCanceled)
        if (!ok) {
            val out = output().trim()
            return if (!isAlive) "rr replay exited before it was ready (exit code ${handler.process.exitValue()}).\n$out"
            else "Timed out waiting for rr replay to listen on port $port.\n$out"
        }
        // The launch line is printed right before listening; give it a moment so we get the exe path.
        val deadline = System.nanoTime() + 2_000_000_000L
        while (launchInfo == null && isAlive && System.nanoTime() < deadline) Thread.sleep(20)
        return null
    }

    fun stop() {
        if (!handler.isProcessTerminated) handler.destroyProcess()
    }

    companion object {
        /** Starts the server. Never call on the EDT. */
        @Throws(ExecutionException::class)
        fun start(rr: String, trace: File?, traceRoot: String?, pid: Int? = null, port: Int = Ports.findFree()): RrReplayServer {
            val args = RrCommands.replayServer(rr, port, trace, pid)
            val cmd = GeneralCommandLine(args)
                .withCharset(StandardCharsets.UTF_8)
                .withEnvironment(RrCommands.env(traceRoot))
            return RrReplayServer(port, OSProcessHandler(cmd))
        }

        /** Writes `rr gdbinit` to a temp file for `source`-ing; null if rr could not produce it. */
        fun writeGdbInit(rr: String): File? {
            val out = RrProcesses.capture(RrCommands.gdbInit(rr), timeoutMs = 15_000)
            if (out.exitCode != 0 || out.stdout.isBlank()) return null
            val f = FileUtil.createTempFile("rr-gdbinit", ".gdb", true)
            f.writeText(out.stdout)
            return f
        }
    }
}
