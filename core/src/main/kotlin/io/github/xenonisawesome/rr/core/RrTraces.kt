package io.github.xenonisawesome.rr.core

import java.io.File

/** One recorded trace as shown in the picker. */
data class RrTrace(
    /** Directory name inside the trace root, e.g. `a.out-3`. */
    val name: String,
    /** Absolute trace directory. */
    val dir: File,
    /** rr marks traces whose recording died with an `incomplete` file; replaying them usually fails. */
    val incomplete: Boolean,
) {
    override fun toString() = if (incomplete) "$name (incomplete)" else name
}

object RrTraces {
    const val LATEST_LINK = "latest-trace"

    /** Arguments for `rr ls`: names only, newest first. (`-l` aborts when any trace is incomplete.) */
    val LS_ARGS = listOf("ls", "-t", "-r")

    /**
     * Parses `rr ls` output defensively: one trace name per line, with
     * - blank lines, diagnostics (`rr: ...`, `Trace file ... found.`, indented text) skipped,
     * - the `latest-trace` symlink dropped (it duplicates an entry),
     * - long-format rows reduced to their first column.
     * Order is preserved.
     */
    fun parseLs(output: String): List<String> {
        val names = LinkedHashSet<String>()
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank() || raw.first().isWhitespace()) continue
            if (line.startsWith("rr:") || line.startsWith("Trace file") || line.startsWith("rr recording")) continue
            if (line.startsWith("TRACE") || line.startsWith("Usage")) continue
            val name = line.split(Regex("\\s{2,}|\\t"), limit = 2)[0].trim()
            if (name.isEmpty() || name == LATEST_LINK) continue
            names += name
        }
        return names.toList()
    }

    /** Lists traces via `rr ls`, newest first. Never call on the EDT. */
    fun list(rrPath: String, traceRoot: String): Result<List<RrTrace>> {
        val out = RrProcesses.capture(
            listOf(rrPath) + LS_ARGS,
            env = mapOf(RrTraceDirs.ENV_VAR to traceRoot),
            timeoutMs = 15_000,
        )
        if (out.timedOut) return Result.failure(IllegalStateException("'rr ls' timed out"))
        val names = parseLs(out.stdout)
        if (names.isEmpty() && out.exitCode != 0) {
            return Result.failure(IllegalStateException("'rr ls' failed (exit ${out.exitCode}): ${out.stderr.trim()}"))
        }
        return Result.success(names.map { toTrace(File(traceRoot), it) })
    }

    internal fun toTrace(root: File, name: String): RrTrace {
        val dir = File(root, name)
        return RrTrace(name, dir, incomplete = File(dir, "incomplete").exists())
    }

    /** The trace bare `rr replay` would use, if the link exists. */
    fun latest(traceRoot: String): File? =
        File(traceRoot, LATEST_LINK).takeIf { it.exists() }?.let { runCatching { it.canonicalFile }.getOrNull() }

    /** True if [dir] looks like an rr trace directory (has the `version` file rr writes). */
    fun looksLikeTrace(dir: File): Boolean = dir.isDirectory && File(dir, "version").isFile

    private val MMAP_FILE = Regex("""mmap_(?:copy|hardlink)_(\d+)_(.+)""")

    /**
     * Fallback when rr's launch line gave no executable: the recorded program is the lowest-numbered
     * non-library `mmap_copy_N_name` / `mmap_hardlink_N_name` file in the trace directory.
     */
    fun guessExecutable(traceDir: File): File? =
        traceDir.listFiles()?.mapNotNull { f ->
            MMAP_FILE.matchEntire(f.name)?.takeIf { !it.groupValues[2].contains(".so") }?.let { it.groupValues[1].toInt() to f }
        }?.minByOrNull { it.first }?.second
}
