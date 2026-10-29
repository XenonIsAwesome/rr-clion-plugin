package io.github.xenonisawesome.rr.core

import com.intellij.openapi.util.SystemInfo

/** rr is Linux-only; every entry point checks this first so other OSes load but stay inert. */
object RrPlatform {
    val isSupported: Boolean get() = SystemInfo.isLinux

    const val UNSUPPORTED_MESSAGE = "rr only runs on Linux. The rr plugin is inactive on this operating system."
}
