package dev.stapler.stelekit.cli

class ArgParseException(message: String, val exitCode: Int) : Exception(message)

fun parseArgs(args: Array<String>): SyncArgs {
    var graphPath: String? = null
    var commitOnly = false
    var fetchOnly = false
    var dryRun = false
    var jsonOutput = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--help" -> {
                println(USAGE)
                kotlin.system.exitProcess(0)
            }
            "--graph" -> {
                if (i + 1 >= args.size) throw ArgParseException("--graph requires a path argument", 5)
                graphPath = args[++i]
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

    return SyncArgs(graphPath, commitOnly, fetchOnly, dryRun, jsonOutput)
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
