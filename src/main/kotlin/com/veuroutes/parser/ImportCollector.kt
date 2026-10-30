package com.veuroutes.parser

/**
 * Maps local names used in a router file to the module they were imported from.
 *
 * This is intentionally regex-based: it never resolves or evaluates modules, it only needs enough
 * information to turn `component: HomeView` into `@/views/HomeView.vue`.
 */
object ImportCollector {

    private val defaultImportRegex = Regex(
        """(?m)^\s*import\s+(?:type\s+)?([A-Za-z_$][\w$]*)\s+from\s+['"]([^'"]+)['"]""",
    )

    private val namedImportRegex = Regex(
        """(?m)^\s*import\s+(?:type\s+)?\{([^}]*)\}\s+from\s+['"]([^'"]+)['"]""",
    )

    private val asyncAssignmentRegex = Regex(
        """(?m)(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*(?::[^=\n]+)?=\s*(?:defineAsyncComponent\s*\(\s*)?(?:\(\s*\)\s*=>\s*)?import\s*\(\s*(?:/\*[\s\S]*?\*/\s*|//[^\n]*\n?\s*)*['"]([^'"]+)['"]""",
    )

    private val requireAssignmentRegex = Regex(
        """(?m)(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*(?::[^=\n]+)?=\s*require\s*\(\s*['"]([^'"]+)['"]""",
    )

    private val identifierRegex = Regex("""^[A-Za-z_$][\w$]*$""")

    fun collect(source: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()

        for (match in defaultImportRegex.findAll(source)) {
            val local = match.groupValues[1]
            val specifier = match.groupValues[2]
            if (local.isNotBlank() && specifier.isNotBlank()) result[local] = specifier
        }

        for (match in namedImportRegex.findAll(source)) {
            val names = match.groupValues[1]
            val specifier = match.groupValues[2]
            if (specifier.isBlank()) continue
            for (part in names.split(',')) {
                for (candidate in candidateNames(part)) {
                    result[candidate] = specifier
                }
            }
        }

        for (match in asyncAssignmentRegex.findAll(source)) {
            val local = match.groupValues[1]
            val specifier = match.groupValues[2]
            if (local.isNotBlank() && specifier.isNotBlank()) result[local] = specifier
        }

        for (match in requireAssignmentRegex.findAll(source)) {
            val local = match.groupValues[1]
            val specifier = match.groupValues[2]
            if (local.isNotBlank() && specifier.isNotBlank()) result[local] = specifier
        }

        return result
    }

    private fun candidateNames(raw: String): List<String> {
        val part = raw.trim()
            .removePrefix("type ")
            .trim()
        if (part.isEmpty()) return emptyList()

        val pieces = part.split(Regex("""\s+as\s+"""))
        val names = ArrayList<String>(2)
        when (pieces.size) {
            1 -> names.add(part)
            2 -> {
                names.add(pieces[0].trim())
                names.add(pieces[1].trim())
            }
        }
        return names.filter { identifierRegex.matches(it) }
    }
}
