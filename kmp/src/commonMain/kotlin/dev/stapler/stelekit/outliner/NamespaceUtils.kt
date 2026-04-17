package dev.stapler.stelekit.outliner

/**
 * Utility for handling Logseq page namespaces.
 * Namespaces use "/" as a separator, e.g., "Parent/Child".
 */
object NamespaceUtils {
    fun getNamespace(name: String): String? {
        val parts = name.split("/")
        if (parts.size <= 1) return null
        return parts.dropLast(1).joinToString("/")
    }

    fun getShortName(name: String): String {
        return name.split("/").last()
    }

    fun getParentPages(name: String): List<String> {
        val parts = name.split("/")
        if (parts.size <= 1) return emptyList()
        
        val parents = mutableListOf<String>()
        var current = ""
        for (i in 0 until parts.size - 1) {
            current = if (current.isEmpty()) parts[i] else "$current/${parts[i]}"
            parents.add(current)
        }
        return parents
    }
}
