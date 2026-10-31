package com.veuroutes.scan

import java.io.File

/**
 * Reads `tsconfig*.json` / `jsconfig*.json` files from the project root and extracts the
 * `compilerOptions.paths` / `baseUrl` aliases used by Vue projects.
 *
 * This is pure Kotlin over [File]s (no IntelliJ APIs), which keeps the alias rules testable.
 * Local `extends` chains (`"extends": "./tsconfig.base.json"`) are followed; package-based
 * extends such as `@vue/tsconfig/tsconfig.dom.json` are ignored because node_modules is opaque
 * to the plugin.
 */
object TsConfigLoader {

    data class AliasRule(
        val prefix: String,
        val suffix: String,
        val targetTemplate: String,
        val wildcard: Boolean,
    )

    data class Config(
        val baseUrlDir: File,
        val rules: List<AliasRule>,
    )

    private data class RawOptions(
        val baseUrl: String? = null,
        val paths: Map<String, String> = emptyMap(),
        val extends: String? = null,
    )

    private data class Resolved(
        val baseUrlDir: File,
        val paths: Map<String, String>,
    )

    private val configNameRegex = Regex(
        """^(?:tsconfig|jsconfig)(?:\.[\w.-]+)?\.json$""",
        RegexOption.IGNORE_CASE,
    )
    private val baseUrlRegex = Regex("""["']?baseUrl["']?\s*:\s*["']([^"']+)["']""")
    private val pathsBlockRegex = Regex("""["']?paths["']?\s*:\s*\{([\s\S]*?)}""")
    private val pathsEntryRegex = Regex("""["']([^"']+)["']\s*:\s*\[\s*["']([^"']+)["']""")
    private val extendsRegex = Regex("""["']?extends["']?\s*:\s*["']([^"']+)["']""")

    fun isConfigFileName(name: String): Boolean = configNameRegex.matches(name)

    /** Returns one [Config] per matching root config file, in file-name order. */
    fun load(root: File): List<Config> {
        val configFiles = root
            .listFiles(java.io.FileFilter { file -> file.isFile && isConfigFileName(file.name) })
            ?.sortedBy { it.name }
            .orEmpty()

        return configFiles.mapNotNull { file -> loadOne(file, HashSet()) }
    }

    private fun loadOne(configFile: File, visited: MutableSet<String>): Config? {
        val raw = readOptions(configFile, visited) ?: return null
        val resolved = resolve(configFile, raw, visited) ?: return null

        val rules = ArrayList<AliasRule>()
        for ((alias, target) in resolved.paths) {
            val star = alias.indexOf('*')
            rules.add(
                if (star >= 0) {
                    AliasRule(
                        prefix = alias.substring(0, star),
                        suffix = alias.substring(star + 1),
                        targetTemplate = target,
                        wildcard = true,
                    )
                } else {
                    AliasRule(
                        prefix = alias,
                        suffix = "",
                        targetTemplate = target,
                        wildcard = false,
                    )
                },
            )
        }

        // A config that only sets the default baseUrl adds nothing beyond the project root.
        val declaredBaseUrl = raw.baseUrl
        val useful = rules.isNotEmpty() || (declaredBaseUrl != null && declaredBaseUrl != ".")
        return if (useful) Config(resolved.baseUrlDir, rules) else null
    }

    private fun resolve(file: File, raw: RawOptions, visited: MutableSet<String>): Resolved? {
        val parent = raw.extends?.let { specifier ->
            if (specifier.startsWith(".")) {
                findExtendedFile(File(file.parentFile, specifier))?.let { parentFile ->
                    readOptions(parentFile, visited)?.let { parentRaw ->
                        resolve(parentFile, parentRaw, visited)
                    }
                }
            } else {
                null
            }
        }

        val baseUrlDir = when {
            raw.baseUrl != null -> File(file.parentFile, raw.baseUrl).normalize()
            parent != null -> parent.baseUrlDir
            else -> file.parentFile
        }

        val paths = LinkedHashMap<String, String>()
        parent?.let { paths.putAll(it.paths) }
        for ((alias, target) in raw.paths) {
            val targetFile = if (target.startsWith("/")) File(target) else File(baseUrlDir, target)
            paths[alias] = targetFile.normalize().path.replace('\\', '/')
        }

        return Resolved(baseUrlDir, paths)
    }

    private fun readOptions(file: File, visited: MutableSet<String>): RawOptions? {
        val key = runCatching { file.canonicalPath }.getOrDefault(file.path)
        if (!visited.add(key)) return null

        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val paths = LinkedHashMap<String, String>()
        pathsBlockRegex.find(text)?.groupValues?.getOrNull(1)?.let { block ->
            for (entry in pathsEntryRegex.findAll(block)) {
                paths.putIfAbsent(entry.groupValues[1], entry.groupValues[2])
            }
        }

        return RawOptions(
            baseUrl = baseUrlRegex.find(text)?.groupValues?.getOrNull(1),
            paths = paths,
            extends = extendsRegex.find(text)?.groupValues?.getOrNull(1),
        )
    }

    private fun findExtendedFile(candidate: File): File? {
        if (candidate.isFile) return candidate
        if (!candidate.name.endsWith(".json", ignoreCase = true)) {
            val withExtension = File(candidate.parentFile, candidate.name + ".json")
            if (withExtension.isFile) return withExtension
        }
        return null
    }
}
