package io.github.xenonisawesome.rr.core

import java.io.File

/** Where rr keeps its traces. Mirrors rr's own lookup so we can show and scan it without running rr. */
object RrTraceDirs {
    const val ENV_VAR = "_RR_TRACE_DIR"

    fun defaultRoot(
        env: Map<String, String> = System.getenv(),
        home: String = System.getProperty("user.home"),
    ): String {
        env[ENV_VAR]?.takeIf { it.isNotBlank() }?.let { return it }
        // rr prefers the legacy ~/.rr if it already exists.
        val legacy = File(home, ".rr")
        if (legacy.isDirectory) return legacy.path
        val xdg = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() } ?: File(home, ".local/share").path
        return File(xdg, "rr").path
    }

    /** Configured trace root from settings, or rr's default. */
    fun effectiveRoot(configured: String?, env: Map<String, String> = System.getenv()): String =
        configured?.takeIf { it.isNotBlank() } ?: defaultRoot(env)
}
