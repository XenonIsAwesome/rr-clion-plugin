package io.github.xenonisawesome.rr.core

import java.io.File
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun versionParsing() {
        assertEquals(RrVersion(5, 7, 0), RrVersion.parse("rr version 5.7.0\n"))
        assertEquals(RrVersion(6, 1, 0), RrVersion.parse("warning\nrr version 6.1"))
        assertNull(RrVersion.parse("gdb 12"))
        assertTrue(RrVersion(5, 0, 0) < RrVersion(5, 7, 0))
        assertTrue(RrVersion(4, 9, 9) < RrBinary.MINIMUM)
    }

    @Test fun paranoidParsing() {
        assertEquals(1, PerfEventParanoid.parse("1\n"))
        assertEquals(4, PerfEventParanoid.parse(" 4 "))
        assertEquals(-1, PerfEventParanoid.parse("-1"))
        assertNull(PerfEventParanoid.parse("abc"))
        assertNull(PerfEventParanoid.parse(null))
        assertTrue(PerfEventParanoid.isTooRestrictive(2))
        assertFalse(PerfEventParanoid.isTooRestrictive(1))
        assertFalse(PerfEventParanoid.isTooRestrictive(-1))
        assertFalse(PerfEventParanoid.isTooRestrictive(null))
    }

    @Test fun paranoidReadFromFile() {
        val f = File.createTempFile("paranoid", "").apply { writeText("3\n"); deleteOnExit() }
        assertEquals(3, PerfEventParanoid.read(f))
        assertNull(PerfEventParanoid.read(File("/nonexistent/x")))
    }

    @Test fun pkexecCommand() {
        val cmd = PerfEventParanoid.fixCommand("/usr/sbin/sysctl")
        assertEquals(listOf("pkexec", "/usr/sbin/sysctl", "-w", "kernel.perf_event_paranoid=1"), cmd.exePath.let { listOf(it) + cmd.parametersList.list })
    }

    @Test fun lsParsingNamesOnly() {
        val out = "scorpion-core-gtest-6\nlatest-trace\nscorpion-core-gtest-5\n\ntrue-0\n"
        assertEquals(listOf("scorpion-core-gtest-6", "scorpion-core-gtest-5", "true-0"), RrTraces.parseLs(out))
    }

    @Test fun lsParsingToleratesNoiseAndLongFormat() {
        val out = """
            rr: Trace file `/x/incomplete' found.
            rr recording terminated abnormally and the trace is incomplete.

            TRACE-NAME   START-TIME   SIZE   CMD
            a.out-3   2026-01-02 10:00   1.2MB   ./a.out
            a.out-2   2026-01-01 10:00   1.1MB   ./a.out
              indented junk
        """.trimIndent()
        assertEquals(listOf("a.out-3", "a.out-2"), RrTraces.parseLs(out))
        assertEquals(emptyList<String>(), RrTraces.parseLs(""))
    }

    @Test fun lsKeepsNamesWithSingleSpaces() {
        assertEquals(listOf("my prog-1"), RrTraces.parseLs("my prog-1\n"))
    }

    @Test fun recordCommand() {
        assertEquals(
            listOf("rr", "record", "-h", "-o", "/t", "/bin/prog", "a", "b"),
            RrCommands.record("rr", listOf("-h"), "/t", "/bin/prog", listOf("a", "b")),
        )
        assertEquals(listOf("rr", "record", "/bin/p"), RrCommands.record("rr", emptyList(), null, "/bin/p", emptyList()))
    }

    @Test fun replayCommand() {
        assertEquals(listOf("rr", "replay", "-s", "5555", "-k"), RrCommands.replayServer("rr", 5555, null))
        assertEquals(listOf("rr", "replay", "-s", "5555", "-k", "/t/x-1"), RrCommands.replayServer("rr", 5555, File("/t/x-1")))
        assertEquals(mapOf("_RR_TRACE_DIR" to "/t"), RrCommands.env("/t"))
        assertTrue(RrCommands.env(" ").isEmpty())
    }

    @Test fun traceDirDefaults() {
        val home = File.createTempFile("home", "").apply { delete(); mkdirs(); deleteOnExit() }.path
        assertEquals("/custom", RrTraceDirs.defaultRoot(mapOf("_RR_TRACE_DIR" to "/custom"), home))
        assertEquals("$home/.local/share/rr", RrTraceDirs.defaultRoot(emptyMap(), home))
        assertEquals("/xdg/rr", RrTraceDirs.defaultRoot(mapOf("XDG_DATA_HOME" to "/xdg"), home))
        File(home, ".rr").mkdirs()
        assertEquals("$home/.rr", RrTraceDirs.defaultRoot(emptyMap(), home))
        assertEquals("/cfg", RrTraceDirs.effectiveRoot("/cfg", emptyMap()))
    }

    @Test fun incompleteTraceDetected() {
        val root = File.createTempFile("root", "").apply { delete(); mkdirs(); deleteOnExit() }
        File(root, "t-1").mkdirs(); File(root, "t-1/incomplete").writeText("")
        File(root, "t-2").mkdirs()
        assertTrue(RrTraces.toTrace(root, "t-1").incomplete)
        assertFalse(RrTraces.toTrace(root, "t-2").incomplete)
    }

    @Test fun listeningDetectionWithoutConnecting() {
        ServerSocket(0).use { s ->
            assertEquals(true, Ports.isListening(s.localPort))
            assertTrue(Ports.waitForListening(s.localPort, 2000))
        }
        val free = Ports.findFree()
        assertEquals(false, Ports.isListening(free))
        assertFalse(Ports.waitForListening(free, 300))
        assertFalse(Ports.waitForListening(free, 5000, isAlive = { false }))
    }

    @Test fun procNetRowParsing() {
        val row = "   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000 0 1 1 0 100 0 0 10 0"
        assertEquals(8080, Ports.parseListeningPort(row))
        assertNull(Ports.parseListeningPort(row.replace(" 0A ", " 01 ")))
    }

    @Test fun launchLineParsing() {
        val text = """
            Launch gdb with
              gdb '-l' '10000' '-ex' 'set sysroot /' '-ex' 'target extended-remote 127.0.0.1:43951' /tmp/tr/t-0/mmap_copy_5_t

            --------------------------------------------------
        """.trimIndent()
        val info = RrLaunchLine.parse(text)!!
        assertEquals(43951, info.port)
        assertEquals("127.0.0.1", info.host)
        assertEquals("/tmp/tr/t-0/mmap_copy_5_t", info.executable)
        assertNull(RrLaunchLine.parse("Launch gdb with"))
        assertNull(RrLaunchLine.parse("nothing here"))
        val quoted = RrLaunchLine.parse("Launch gdb with\n  gdb '-ex' 'target extended-remote :9' '/p/x'")
        assertEquals(9, quoted!!.port)
    }

    /** Architecture rule: core must not touch CIDR/CLion classes. */
    @Test fun coreDoesNotImportCidr() {
        val src = File("src/main/kotlin")
        assertTrue("run from module dir", src.isDirectory)
        val offenders = src.walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readLines().any { it.trimStart().startsWith("import com.jetbrains.cidr") || it.contains("com.intellij.clion") } }
        assertEquals(emptyList<File>(), offenders.toList())
    }
}

class GuessExecutableTest {
    @Test fun picksLowestNonLibraryMmap() {
        val d = File.createTempFile("trace", "").apply { delete(); mkdirs(); deleteOnExit() }
        listOf("mmap_hardlink_46_libzmq.so.5.2.5", "mmap_hardlink_31_libmantis.so.0.0.0", "mmap_hardlink_4_my-prog", "mmap_copy_9_other", "events")
            .forEach { File(d, it).writeText("") }
        assertEquals("mmap_hardlink_4_my-prog", RrTraces.guessExecutable(d)?.name)
        assertNull(RrTraces.guessExecutable(File(d, "nope")))
    }
}
