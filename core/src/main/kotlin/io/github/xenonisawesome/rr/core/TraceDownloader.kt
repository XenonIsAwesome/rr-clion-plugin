package io.github.xenonisawesome.rr.core

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * Downloads a packed rr trace archive (e.g. a CI artifact made with `rr pack` + `tar -czf`) and unpacks it
 * into the trace root. Archive format is detected from magic bytes, not the file name, so any common
 * format works: zip, tar, tar.gz/bz2/xz/zst/lz4, 7z, rar.
 * Everything here blocks: never call on the EDT.
 */
object TraceDownloader {
    enum class Format { ZIP, TAR, SEVEN_ZIP, RAR }

    private val ARCHIVE_SUFFIXES = listOf(
        ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz4", ".tar.z", ".tgz", ".tbz2", ".tbz", ".txz", ".tzst",
        ".tar", ".zip", ".7z", ".rar", ".gz", ".bz2", ".xz", ".zst",
    )

    /** Validates user input; returns an error message or null if it is an http(s) URL. */
    fun validateUrl(text: String): String? {
        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return "Not a valid URL"
        if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host.isNullOrEmpty()) return "Enter an http:// or https:// URL"
        return null
    }

    /** A filesystem-safe base name for the extraction directory, from the URL's last path segment. */
    fun baseName(url: String): String {
        val last = (runCatching { URI(url.trim()).path }.getOrNull() ?: url).trimEnd('/').substringAfterLast('/')
        var name = java.net.URLDecoder.decode(last, Charsets.UTF_8)
        val lower = name.lowercase()
        ARCHIVE_SUFFIXES.firstOrNull { lower.endsWith(it) }?.let { name = name.dropLast(it.length) }
        name = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        return name.ifEmpty { "downloaded-trace" }
    }

    fun detectFormat(file: File): Format? {
        val h = ByteArray(512)
        val n = file.inputStream().use { it.read(h) }
        fun starts(vararg b: Int) = n >= b.size && b.indices.all { (h[it].toInt() and 0xff) == b[it] }
        return when {
            starts(0x50, 0x4B, 0x03, 0x04) || starts(0x50, 0x4B, 0x05, 0x06) -> Format.ZIP
            starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> Format.SEVEN_ZIP
            starts(0x52, 0x61, 0x72, 0x21) -> Format.RAR
            starts(0x1F, 0x8B) || starts(0x42, 0x5A, 0x68) || starts(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) ||
                starts(0x28, 0xB5, 0x2F, 0xFD) || starts(0x04, 0x22, 0x4D, 0x18) || starts(0x1F, 0x9D) -> Format.TAR
            n >= 262 && String(h, 257, 5, Charsets.ISO_8859_1) == "ustar" -> Format.TAR
            else -> null
        }
    }

    /**
     * Downloads [url] into a temp file, extracts it under [traceRoot] and returns the trace directory.
     * [progress] gets (bytesRead, totalBytesOrMinus1); [cancelled] is polled during the copy.
     */
    fun downloadAndExtract(
        url: String,
        traceRoot: File,
        progress: (Long, Long) -> Unit = { _, _ -> },
        status: (String) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Result<File> = runCatching {
        val archive = File.createTempFile("rr-trace-", ".download")
        try {
            status("Downloading…")
            download(url.trim(), archive, progress, cancelled)
            extractToRoot(archive, baseName(url), traceRoot, status).getOrThrow()
        } finally {
            archive.delete()
        }
    }

    /** Extracts a local archive under [traceRoot] and returns the trace directory. The file is left untouched. */
    fun extractLocal(archive: File, traceRoot: File, status: (String) -> Unit = {}): Result<File> =
        extractToRoot(archive, baseName(archive.name), traceRoot, status)

    private fun extractToRoot(archive: File, base: String, traceRoot: File, status: (String) -> Unit): Result<File> = runCatching {
        traceRoot.mkdirs()
        val dest = uniqueDir(traceRoot, base)
        try {
            status("Unpacking…")
            val format = detectFormat(archive)
                ?: throw IOException("Unrecognized archive format (expected zip, tar, tar.gz/bz2/xz/zst, 7z or rar)")
            if (!dest.mkdirs()) throw IOException("Cannot create ${dest.path}")
            extract(archive, format, dest)
            findTrace(dest) ?: throw IOException("The archive does not contain an rr trace (no 'version' file found)")
        } catch (e: Throwable) {
            dest.deleteRecursively()
            throw e
        }
    }

    private fun uniqueDir(root: File, base: String): File {
        var dir = File(root, base)
        var i = 1
        while (dir.exists()) dir = File(root, "$base-${i++}")
        return dir
    }

    private fun download(url: String, out: File, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(30))
            .build()
        val request = HttpRequest.newBuilder(URI(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            if (response.statusCode() !in 200..299) throw IOException("Download failed: HTTP ${response.statusCode()}")
            val total = response.headers().firstValueAsLong("Content-Length").orElse(-1)
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    if (cancelled()) throw IOException("Cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    read += n
                    progress(read, total)
                }
            }
        }
    }

    internal fun extract(archive: File, format: Format, dest: File) {
        when (format) {
            Format.ZIP -> unzip(archive, dest)
            // GNU tar and bsdtar both detect gzip/bzip2/xz/zstd/lz4 on their own when reading a file.
            Format.TAR -> run(listOf("tar", "-xf", archive.path, "-C", dest.path), "tar")
            Format.SEVEN_ZIP, Format.RAR -> sevenZip(archive, dest)
        }
    }

    private fun unzip(archive: File, dest: File) {
        val root = dest.canonicalFile
        ZipInputStream(archive.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val target = File(root, entry.name).canonicalFile
                if (target != root && !target.path.startsWith(root.path + File.separator)) {
                    throw IOException("Unsafe path in archive: ${entry.name}")
                }
                if (entry.isDirectory) target.mkdirs()
                else {
                    target.parentFile.mkdirs()
                    target.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }

    private fun sevenZip(archive: File, dest: File) {
        val tool = listOf("7z", "7zz", "7za", "7zr").firstOrNull { com.intellij.execution.configurations.PathEnvironmentVariableUtil.findInPath(it) != null }
            ?: throw IOException("Unpacking 7z/rar archives needs '7z' (p7zip) on PATH")
        run(listOf(tool, "x", "-y", "-o${dest.path}", archive.path), tool)
    }

    private fun run(cmd: List<String>, name: String) {
        val out = RrProcesses.capture(cmd, env = emptyMap(), timeoutMs = 30 * 60_000)
        if (out.timedOut) throw IOException("'$name' timed out")
        if (out.exitCode != 0) {
            val msg = out.stderr.trim().lines().lastOrNull().orEmpty()
            throw IOException("'$name' failed (exit ${out.exitCode}): $msg")
        }
    }

    /** Finds the trace directory inside an extracted tree (up to 3 levels deep), preferring `latest-trace`. */
    fun findTrace(root: File): File? {
        val found = mutableListOf<File>()
        fun walk(dir: File, depth: Int) {
            if (RrTraces.looksLikeTrace(dir)) { found += dir; return }
            if (depth >= 3) return
            dir.listFiles()?.filter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                ?.sortedBy { it.name }?.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        if (found.size > 1) {
            val latest = found.firstNotNullOfOrNull { f ->
                File(f.parentFile, RrTraces.LATEST_LINK).takeIf { it.exists() }?.let { runCatching { it.canonicalFile }.getOrNull() }
            }
            if (latest != null && latest in found.map { it.canonicalFile }) return latest
        }
        return found.firstOrNull()
    }
}
