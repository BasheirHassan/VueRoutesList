package com.veuroutes.scan

/**
 * Pure helpers that turn a component expression into candidate file suffixes.
 *
 * Router files often wrap the actual component in a project helper, for example:
 *
 * ```ts
 * component: pageComponent('Accounts/Index')
 * component: view('Dashboard')
 * ```
 *
 * The helper usually maps the argument to a folder such as `src/Pages` through
 * `import.meta.glob`. These helpers keep that knowledge testable without the platform.
 */
object ComponentHints {

    private val literalRegex = Regex("""['"`]([^'"`\n]{1,300})['"`]""")
    private val ignoredLiterals = setOf(
        "vue",
        "vue-router",
        "vuex",
        "pinia",
        "axios",
        "react",
        "react-dom",
        "true",
        "false",
        "null",
        "undefined",
        "default",
    )

    /** Folders, in priority order, that usually contain route components. */
    private val componentRoots = listOf(
        "Pages",
        "pages",
        "Views",
        "views",
        "Layouts",
        "layouts",
        "Components",
        "components",
    )

    /** Every quoted string inside [expression], most specific first. */
    fun literals(expression: String): List<String> =
        literalRegex.findAll(expression)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
            .filter { it.isNotEmpty() && it !in ignoredLiterals }
            .distinct()
            .toList()

    /**
     * Path suffixes to look for, relative to any folder in the project.
     *
     * `Accounts/Index` becomes `Accounts/Index.vue` (which matches `src/Pages/Accounts/Index.vue`)
     * and `Pages/Accounts/Index.vue` (used when the same name exists in several folders).
     * A bare name such as `Dashboard` prefers the conventional component folders first.
     */
    fun suffixCandidates(literal: String): List<String> {
        var clean = literal
            .substringBefore('?')
            .substringBefore('#')
            .replace('\\', '/')
            .trim()
        while (clean.startsWith("./") || clean.startsWith("../")) {
            clean = clean.substringAfter('/')
        }
        if (clean.startsWith("@/") || clean.startsWith("~/")) clean = clean.substring(2)
        clean = clean.trimStart('/')
        if (clean.isEmpty()) return emptyList()

        val base = clean.removeSuffix(".vue")
        if (base.isEmpty()) return emptyList()

        val candidates = LinkedHashSet<String>()
        if (base.contains('/')) {
            candidates.add("$base.vue")
            for (root in componentRoots) candidates.add("$root/$base.vue")
        } else {
            for (root in componentRoots) candidates.add("$root/$base.vue")
            candidates.add("$base.vue")
        }
        return candidates.toList()
    }
}
