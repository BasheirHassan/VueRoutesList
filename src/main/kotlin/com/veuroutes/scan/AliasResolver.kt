package com.veuroutes.scan

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Resolves component import specifiers to files in the project.
 *
 * Resolution order:
 * 1. relative imports (`../views/Home.vue`)
 * 2. `compilerOptions.paths` from tsconfig.json / jsconfig.json / tsconfig.app.json
 * 3. `compilerOptions.baseUrl`
 * 4. the conventional Vue aliases `@/` and `~/` pointing at `src/` or the project root
 *
 * Files outside the project content roots (for example node_modules) are not returned; in that
 * case the route table keeps showing the original import specifier.
 */
class AliasResolver(private val project: Project) {

    private data class AliasRule(
        val prefix: String,
        val suffix: String,
        val targetTemplate: String,
        val wildcard: Boolean,
    )

    private data class Config(
        val baseUrlDir: File,
        val rules: List<AliasRule>,
    )

    private val projectFileIndex = ProjectFileIndex.getInstance(project)
    private val baseDir: VirtualFile? = project.baseDir
    private val basePath: String? = baseDir?.path?.replace('\\', '/')
    private val baseDirFile: File? = basePath?.let { File(it) }

    private val configs: List<Config> = loadConfigs()
    private val aliasRules: List<AliasRule> = buildRules()
    private val baseUrlDirs: List<File> = configs.map { it.baseUrlDir }.distinct()

    private val fileExtensions = listOf(".vue", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs")
    private val indexFiles = listOf("index.vue", "index.ts", "index.tsx", "index.js", "index.jsx")

    fun resolve(specifier: String, sourceFile: VirtualFile): VirtualFile? {
        val clean = specifier
            .substringBefore('?')
            .substringBefore('#')
            .replace('\\', '/')
            .trim()
        if (clean.isEmpty()) return null

        val candidates = ArrayList<String>()

        when {
            clean.startsWith(".") -> {
                val parent = sourceFile.parent?.path ?: basePath
                if (parent != null) {
                    candidates.add(File(parent, clean).normalize().path.replace('\\', '/'))
                }
            }

            clean.startsWith("/") -> {
                basePath?.let { candidates.add(File(it, clean.trimStart('/')).normalize().path.replace('\\', '/')) }
            }

            else -> {
                for (rule in aliasRules) {
                    val target = matchRule(rule, clean) ?: continue
                    candidates.add(target)
                }
                for (baseUrlDir in baseUrlDirs) {
                    candidates.add(File(baseUrlDir, clean).normalize().path.replace('\\', '/'))
                }
            }
        }

        for (candidate in candidates.distinct()) {
            val file = findFile(File(candidate)) ?: continue
            if (isUsable(file)) return file
        }
        return null
    }

    private fun matchRule(rule: AliasRule, specifier: String): String? {
        if (rule.wildcard) {
            if (!specifier.startsWith(rule.prefix) || !specifier.endsWith(rule.suffix)) return null
            if (specifier.length < rule.prefix.length + rule.suffix.length) return null
            val middle = specifier.substring(
                rule.prefix.length,
                specifier.length - rule.suffix.length,
            )
            return rule.targetTemplate.replace("*", middle)
        }

        val prefix = rule.prefix.trimEnd('/')
        return when {
            specifier == rule.prefix || specifier == prefix -> rule.targetTemplate
            specifier.startsWith("$prefix/") -> {
                val rest = specifier.substring(prefix.length + 1)
                "${rule.targetTemplate.trimEnd('/')}/$rest"
            }

            else -> null
        }
    }

    private fun findFile(candidate: File): VirtualFile? {
        val fileSystem = LocalFileSystem.getInstance()

        val exact = fileSystem.findFileByIoFile(candidate)
        if (exact != null) {
            if (!exact.isDirectory) return exact
            for (index in indexFiles) {
                val child = fileSystem.findFileByIoFile(File(candidate, index))
                if (child != null && !child.isDirectory) return child
            }
            return null
        }

        if (candidate.extension.isEmpty()) {
            for (extension in fileExtensions) {
                val withExtension = fileSystem.findFileByIoFile(File(candidate.path + extension))
                if (withExtension != null && !withExtension.isDirectory) return withExtension
            }
        }

        for (index in indexFiles) {
            val child = fileSystem.findFileByIoFile(File(candidate, index))
            if (child != null && !child.isDirectory) return child
        }
        return null
    }

    private fun isUsable(file: VirtualFile): Boolean =
        file.isValid && !file.isDirectory && projectFileIndex.isInContent(file) && !projectFileIndex.isInLibrary(file)

    private fun loadConfigs(): List<Config> {
        val root = baseDirFile ?: return emptyList()
        val configNames = listOf("tsconfig.json", "jsconfig.json", "tsconfig.app.json", "tsconfig.base.json")
        val result = ArrayList<Config>()

        for (name in configNames) {
            val configFile = File(root, name)
            if (!configFile.isFile) continue
            val text = runCatching { configFile.readText() }.getOrNull() ?: continue

            val baseUrlRaw = Regex("""["']?baseUrl["']?\s*:\s*["']([^"']+)["']""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?: "."
            val baseUrlDir = File(root, baseUrlRaw).normalize()

            val pathsBlock = Regex("""["']?paths["']?\s*:\s*\{([\s\S]*?)}""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)

            val rules = ArrayList<AliasRule>()
            if (pathsBlock != null) {
                val entryRegex = Regex("""["']([^"']+)["']\s*:\s*\[\s*["']([^"']+)["']""")
                for (entry in entryRegex.findAll(pathsBlock)) {
                    val alias = entry.groupValues[1]
                    val targetRaw = entry.groupValues[2]
                    val targetFile = if (targetRaw.startsWith("/")) {
                        File(targetRaw)
                    } else {
                        File(baseUrlDir, targetRaw)
                    }
                    val target = targetFile.normalize().path.replace('\\', '/')
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
            }

            if (rules.isNotEmpty() || baseUrlRaw != ".") {
                result.add(Config(baseUrlDir, rules))
            }
        }
        return result
    }

    private fun buildRules(): List<AliasRule> {
        val rules = ArrayList<AliasRule>()
        configs.forEach { rules.addAll(it.rules) }

        val root = basePath
        if (root != null) {
            val src = "$root/src"
            rules.add(AliasRule("@/", "", "$src/*", true))
            rules.add(AliasRule("@/", "", "$root/*", true))
            rules.add(AliasRule("~/", "", "$src/*", true))
            rules.add(AliasRule("~/", "", "$root/*", true))
        }

        return rules.sortedByDescending { it.prefix.length }
    }
}
