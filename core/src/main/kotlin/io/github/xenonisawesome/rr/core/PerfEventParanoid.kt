package io.github.xenonisawesome.rr.core

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import java.io.File

/**
 * rr needs hardware performance counters from unprivileged processes, which the kernel only allows
 * when /proc/sys/kernel/perf_event_paranoid is <= 1.
 */
object PerfEventParanoid {
    val PROC_FILE = File("/proc/sys/kernel/perf_event_paranoid")
    const val REQUIRED_LEVEL = 1

    /** Exact command to show the user (and to copy) when we cannot run pkexec ourselves. */
    const val MANUAL_COMMAND = "sudo sysctl -w kernel.perf_event_paranoid=1"

    const val EXPLANATION =
        "rr uses the CPU's hardware performance counters to replay your program deterministically. " +
            "Linux only exposes them to normal users when kernel.perf_event_paranoid is 1 or lower; " +
            "it is currently higher."

    const val PERSIST_HINT =
        "The change lasts until reboot. To make it permanent, put \"kernel.perf_event_paranoid = 1\" in " +
            "/etc/sysctl.d/10-rr.conf."

    /** Parses file content; null when unreadable or not a number. */
    fun parse(text: String?): Int? = text?.trim()?.toIntOrNull()

    /** Current level, or null if it cannot be determined (then we do not nag). */
    fun read(file: File = PROC_FILE): Int? = parse(runCatching { file.readText() }.getOrNull())

    fun isTooRestrictive(level: Int?): Boolean = level != null && level > REQUIRED_LEVEL

    /** `pkexec sysctl -w kernel.perf_event_paranoid=1`, which triggers the polkit graphical prompt. */
    fun fixCommand(sysctl: String = findSysctl()): GeneralCommandLine =
        GeneralCommandLine("pkexec", sysctl, "-w", "kernel.perf_event_paranoid=$REQUIRED_LEVEL")

    fun findSysctl(): String {
        PathEnvironmentVariableUtil.findInPath("sysctl")?.let { return it.path }
        return listOf("/usr/sbin/sysctl", "/sbin/sysctl").firstOrNull { File(it).canExecute() } ?: "sysctl"
    }

    fun isPkexecAvailable(): Boolean = PathEnvironmentVariableUtil.findInPath("pkexec") != null

    sealed interface FixResult {
        /** Level is now acceptable. */
        data class Fixed(val level: Int) : FixResult
        /** Command ran but the level is still too high (or unreadable). */
        data class NotEffective(val level: Int?, val details: String) : FixResult
        /** User dismissed the polkit prompt (pkexec exit 126) or authorisation failed (127). */
        data class Declined(val details: String) : FixResult
        /** pkexec missing, so the caller should fall back to showing [MANUAL_COMMAND]. */
        data object PkexecMissing : FixResult
    }

    /** Runs the pkexec fix and re-reads the file to confirm. Blocks while the polkit prompt is open. */
    fun applyFix(file: File = PROC_FILE): FixResult {
        if (!isPkexecAvailable()) return FixResult.PkexecMissing
        val out = RrProcesses.capture(fixCommand(), timeoutMs = 5 * 60_000)
        val details = (out.stderr.ifBlank { out.stdout }).trim()
        // pkexec: 126 = not authorised / dialog dismissed, 127 = could not authenticate (e.g. no polkit agent)
        if (out.exitCode == 126) return FixResult.Declined(details)
        if (out.exitCode == 127) return FixResult.PkexecMissing
        val level = read(file)
        return if (level != null && !isTooRestrictive(level)) FixResult.Fixed(level) else FixResult.NotEffective(level, details)
    }
}
