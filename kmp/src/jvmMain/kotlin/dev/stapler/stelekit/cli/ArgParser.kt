package dev.stapler.stelekit.cli

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.EffectiveNotesPath
import java.io.File

class ArgParseException(message: String, val exitCode: Int) : Exception(message)

fun parseArgs(args: Array<String>): SyncArgs {
    var graphPath: EffectiveNotesPath? = null
    var commitOnly = false
    var fetchOnly = false
    var dryRun = false
    var jsonOutput = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--help" -> throw ArgParseException(USAGE, 0)
            "--graph" -> {
                if (i + 1 >= args.size) throw ArgParseException("--graph requires a path argument", 5)
                graphPath = EffectiveNotesPath(args[++i])
            }
            "--commit-only" -> commitOnly = true
            "--fetch-only" -> fetchOnly = true
            "--dry-run" -> dryRun = true
            "--json" -> jsonOutput = true
            else -> throw ArgParseException("Unknown flag: ${args[i]}", 5)
        }
        i++
    }

    if (commitOnly && fetchOnly) {
        throw ArgParseException("--commit-only and --fetch-only are mutually exclusive", 5)
    }
    if (dryRun && (commitOnly || fetchOnly)) {
        throw ArgParseException("--dry-run cannot be combined with --commit-only or --fetch-only", 5)
    }

    return SyncArgs(graphPath, commitOnly, fetchOnly, dryRun, jsonOutput)
}

/**
 * The notes folder to sync: `--graph`, else the last opened graph (`lastGraphPath` mirrors its notes
 * folder). Canonicalized to block path traversal (e.g. "../../etc") and resolve symlinks. Left is the
 * message to show when there is no usable folder.
 */
internal fun resolveNotesPath(args: SyncArgs, lastGraphPath: EffectiveNotesPath?): Either<String, EffectiveNotesPath> {
    val requested = args.graphPath
        ?: lastGraphPath?.takeIf { it.directory().isNotEmpty() }
        ?: return "No graph path specified and no last opened graph found. Use --graph <path>.".left()
    val directory = File(requested.directory()).canonicalFile
    if (!directory.isDirectory) return "Graph path does not exist or is not a directory: ${directory.path}".left()
    return EffectiveNotesPath(directory.path).right()
}

private val USAGE = """
    Usage: stelekit-sync [options]

    Options:
      --graph <path>    Path to the graph directory (default: last opened graph)
      --commit-only     Only commit local changes, do not fetch or push
      --fetch-only      Only fetch remote changes, do not commit or push
      --dry-run         Show what would happen without making changes
      --json            Output results as JSON
      --help            Show this help message
""".trimIndent()
