package dev.stapler.stelekit.domain

/**
 * Generates English surface forms (inflections) from a base word.
 * Used by [PageNameIndex] to build stem variant entries in the Aho-Corasick matcher.
 *
 * Each [Form] carries the surface text and the number of characters from the match
 * start that correspond to the canonical page name (baseLength), so the matcher can
 * report a span covering only the base — e.g. "running" → span of 3 ("run"), enabling
 * [[Run]]ning link insertion.
 *
 * Rules applied per suffix:
 * - IE → YING before -ing: die → dying
 * - E-drop before vowel suffixes: make → making / maker; hope → hoped
 * - CVC doubling (final consonant-vowel-consonant, not w/x/y): run → running / runner / runned
 * - Y → I alternation (consonant+y): cry → cries / cried / crier; happy → happily
 * - LE → LY: simple → simply
 * - Sibilant +ES (ch/sh/s/x/z): watch → watches
 * - Default: plain append
 */
internal object EnglishInflector {

    data class Form(val text: String, val baseLength: Int)

    fun inflect(base: String): List<Form> {
        if (base.length < 2) return emptyList()
        return buildList {
            addAll(pluralForms(base))
            addAll(ingForms(base))
            addAll(edForms(base))
            addAll(erForms(base))
            addAll(lyForms(base))
        }.filter { it.text != base }
    }

    private fun pluralForms(base: String): List<Form> = when {
        consonantY(base) ->
            listOf(Form(base.dropLast(1) + "ies", base.length - 1))
        base.endsWith("ch") || base.endsWith("sh") ||
                base.endsWith("x") || base.endsWith("z") || base.endsWith("s") ->
            listOf(Form(base + "es", base.length))
        else ->
            listOf(Form(base + "s", base.length))
    }

    private fun ingForms(base: String): List<Form> = when {
        base.endsWith("ie") ->
            listOf(Form(base.dropLast(2) + "ying", base.length - 2))
        base.endsWith("e") && base.length > 2 ->
            listOf(Form(base.dropLast(1) + "ing", base.length - 1))
        isCVC(base) ->
            listOf(Form(base + base.last() + "ing", base.length))
        else ->
            listOf(Form(base + "ing", base.length))
    }

    private fun edForms(base: String): List<Form> = when {
        base.endsWith("e") ->
            listOf(Form(base + "d", base.length))
        consonantY(base) ->
            listOf(Form(base.dropLast(1) + "ied", base.length - 1))
        isCVC(base) ->
            listOf(Form(base + base.last() + "ed", base.length))
        else ->
            listOf(Form(base + "ed", base.length))
    }

    private fun erForms(base: String): List<Form> = when {
        base.endsWith("e") ->
            listOf(Form(base + "r", base.length))
        consonantY(base) ->
            listOf(Form(base.dropLast(1) + "ier", base.length - 1))
        isCVC(base) ->
            listOf(Form(base + base.last() + "er", base.length))
        else ->
            listOf(Form(base + "er", base.length))
    }

    private fun lyForms(base: String): List<Form> = when {
        base.length > 3 && base.endsWith("le") ->
            listOf(Form(base.dropLast(2) + "ly", base.length - 2))
        consonantY(base) ->
            listOf(Form(base.dropLast(1) + "ily", base.length - 1))
        else ->
            listOf(Form(base + "ly", base.length))
    }

    /** True when [base] ends with a consonant followed by 'y'. */
    private fun consonantY(base: String): Boolean =
        base.length > 1 && base.endsWith("y") && !isVowel(base[base.length - 2])

    /**
     * Monosyllabic CVC check: ends in consonant–vowel–consonant where the final
     * consonant is not w, x, or y. Signals that the final consonant should be doubled
     * before vowel suffixes (-ing, -ed, -er).
     */
    private fun isCVC(base: String): Boolean {
        if (base.length < 3) return false
        val c3 = base[base.length - 1]
        val c2 = base[base.length - 2]
        val c1 = base[base.length - 3]
        return !isVowel(c3) && c3 !in "wxy" && isVowel(c2) && !isVowel(c1)
    }

    private fun isVowel(c: Char): Boolean = c in "aeiou"
}
