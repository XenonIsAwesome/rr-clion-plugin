package io.github.xenonisawesome.rr.core

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

object Ports {
    /** Asks the OS for a currently free loopback port. Racy by nature, but good enough for rr's `-s`. */
    fun findFree(): Int = ServerSocket(0).use { it.localPort }

    /**
     * True if some socket is in LISTEN state on [port], judged from /proc/net/tcp{,6}.
     *
     * We deliberately do not probe with a connection: `rr replay -s` accepts exactly the first
     * connection as the debugger, so a probe would steal it. Returns null if /proc is unreadable.
     */
    fun isListening(port: Int, procNetFiles: List<File> = listOf(File("/proc/net/tcp"), File("/proc/net/tcp6"))): Boolean? {
        var readable = false
        for (file in procNetFiles) {
            val lines = try { file.readLines() } catch (_: Exception) { continue }
            readable = true
            if (lines.drop(1).any { parseListeningPort(it) == port }) return true
        }
        return if (readable) false else null
    }

    /** Local port of a /proc/net/tcp row whose state is LISTEN (0A), else null. */
    internal fun parseListeningPort(line: String): Int? {
        val cols = line.trim().split(Regex("\\s+"))
        if (cols.size < 4 || cols[3] != "0A") return null
        return cols[1].substringAfterLast(':').toIntOrNull(16)
    }

    /**
     * Waits until [port] is listening. Returns false on timeout, or as soon as [isAlive] reports
     * that the server process died. [checkCanceled] may throw to abort.
     */
    fun waitForListening(
        port: Int,
        timeoutMs: Long,
        pollMs: Long = 100,
        isAlive: () -> Boolean = { true },
        checkCanceled: () -> Unit = {},
        host: String = "127.0.0.1",
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            checkCanceled()
            val listening = isListening(port) ?: connectProbe(host, port)
            if (listening) return true
            if (!isAlive() || System.nanoTime() >= deadline) return false
            Thread.sleep(pollMs)
        }
    }

    private fun connectProbe(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 250) }
        true
    } catch (_: java.io.IOException) {
        false
    }
}
