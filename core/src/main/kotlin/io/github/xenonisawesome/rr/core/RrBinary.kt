package io.github.xenonisawesome.rr.core

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import java.io.File

/** Finds the rr executable and checks that it runs. */
object RrBinary {
    sealed interface Resolution {
        data class Found(val path: String, val version: RrVersion) : Resolution
        data class Problem(val message: String) : Resolution
    }

    /** Minimum rr we tolerate: older versions lack `rr gdbinit` and the `-s` server mode we rely on. */
    val MINIMUM = RrVersion(5, 0, 0)

    /** Locates the binary without running it. [configured] is the settings value (null/blank = PATH). */
    fun locate(configured: String?): File? {
        if (!configured.isNullOrBlank()) {
            val f = File(configured.trim())
            return f.takeIf { it.isFile && it.canExecute() }
        }
        return PathEnvironmentVariableUtil.findInPath("rr")
    }

    fun resolve(configured: String?): Resolution {
        if (!RrPlatform.isSupported) return Resolution.Problem(RrPlatform.UNSUPPORTED_MESSAGE)
        val file = locate(configured) ?: return Resolution.Problem(
            if (configured.isNullOrBlank()) {
                "rr was not found on PATH. Install it (for example `sudo apt install rr`) or set its location " +
                    "in Settings | Tools | rr."
            } else {
                "The configured rr binary '$configured' does not exist or is not executable."
            },
        )
        val out = RrProcesses.capture(listOf(file.path, "--version"), timeoutMs = 10_000)
        val version = RrVersion.parse(out.stdout.ifBlank { out.stderr })
            ?: return Resolution.Problem("'${file.path} --version' did not print a recognisable rr version: ${out.stdout.trim().ifEmpty { out.stderr.trim() }}")
        if (version < MINIMUM) {
            return Resolution.Problem("rr $version is too old; this plugin needs rr ${MINIMUM} or newer.")
        }
        return Resolution.Found(file.path, version)
    }
}

data class RrVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<RrVersion> {
    override fun compareTo(other: RrVersion) =
        compareValuesBy(this, other, RrVersion::major, RrVersion::minor, RrVersion::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val REGEX = Regex("""rr version\s+(\d+)\.(\d+)(?:\.(\d+))?""", RegexOption.IGNORE_CASE)

        /** Parses `rr version 5.7.0` (any surrounding noise tolerated). */
        fun parse(text: String): RrVersion? {
            val m = REGEX.find(text) ?: return null
            return RrVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { "0" }.toInt())
        }
    }
}
