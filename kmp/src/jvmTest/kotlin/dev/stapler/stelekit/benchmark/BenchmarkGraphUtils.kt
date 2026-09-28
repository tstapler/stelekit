package dev.stapler.stelekit.benchmark

import dev.stapler.stelekit.performance.QueryStat
import dev.stapler.stelekit.platform.Settings
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToInt

object BenchmarkGraphUtils {

    /**
     * Copies the graph at [sourcePath] to a temp directory under `~/.cache/stelekit-benchmarks/`.
     *
     * PlatformFileSystem.validatePath requires paths to be within the user's home directory,
     * so we cannot use system /tmp. Using ~/.cache keeps the copy under ~ and avoids
     * the path validation rejection that would silently give 0 pages.
     */
    fun copyGraphToTempDir(sourcePath: String, prefix: String = "stelekit-bench"): File {
        val cacheBase = File(System.getProperty("user.home"), ".cache/stelekit-benchmarks")
        cacheBase.mkdirs()
        val dest = Files.createTempDirectory(cacheBase.toPath(), prefix).toFile()
        File(sourcePath).copyRecursively(dest, overwrite = true)
        dest.deleteOnExit()
        return dest
    }

    fun tempDir(prefix: String = "stelekit-bench"): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    fun writeJson(file: File, data: Map<String, Any>) {
        try {
            file.parentFile?.mkdirs()
            val sb = StringBuilder("{\n")
            val entries = data.entries.toList()
            entries.forEachIndexed { index, (key, value) ->
                sb.append("  \"$key\": ")
                when (value) {
                    is String  -> sb.append("\"$value\"")
                    is Boolean -> sb.append(value.toString())
                    is Long    -> sb.append(value.toString())
                    is Int     -> sb.append(value.toString())
                    is Double  -> sb.append("%.2f".format(value))
                    else       -> sb.append("\"$value\"")
                }
                if (index < entries.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("}")
            file.writeText(sb.toString())
        } catch (_: Exception) {}
    }

    fun writeQueryStatsJson(file: File, stats: List<QueryStat>) {
        try {
            file.parentFile?.mkdirs()
            val sb = StringBuilder("[\n")
            stats.forEachIndexed { i, q ->
                sb.append(
                    """  {"table":"${q.tableName}","op":"${q.operation}","calls":${q.calls},"p50":${q.estimatePercentile(0.5)},"p99":${q.estimatePercentile(0.99)},"maxMs":${q.maxMs},"totalMs":${q.totalMs}}"""
                )
                if (i < stats.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("]")
            file.writeText(sb.toString())
        } catch (_: Exception) {}
    }

    fun percentile(samples: List<Long>, p: Int): Long {
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        val idx = ((p / 100.0) * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    fun noopSettings(): Settings = object : Settings {
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun getString(key: String, defaultValue: String) = defaultValue
        override fun putString(key: String, value: String) = Unit
        override fun containsKey(key: String) = false
    }
}
