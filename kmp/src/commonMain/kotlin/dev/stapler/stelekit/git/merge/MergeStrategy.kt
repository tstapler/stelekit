package dev.stapler.stelekit.git.merge

interface MergeStrategy {
    fun canHandle(base: List<String>, local: List<String>, remote: List<String>): Boolean
    fun applyMerge(base: List<String>, local: List<String>, remote: List<String>): List<String>
}
