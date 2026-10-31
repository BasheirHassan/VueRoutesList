package com.veuroutes.scan

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.veuroutes.parser.ParsedComponent

/**
 * Finds the Vue file behind a route `component` value.
 *
 * Resolution order:
 * 1. an explicit `import()` / `require()` / glob-map key;
 * 2. an imported identifier (`component: HomeView`);
 * 3. a string literal wrapped in a project helper (`component: pageComponent('Accounts/Index')`);
 * 4. a project-wide unique match by file name (`component: RouterPassthrough`).
 *
 * Step 3 is what makes helper-based routers work: the helper usually prefixes the literal with a
 * folder such as `src/Pages`, so the resolver also tries the conventional `Pages`/`Views`/
 * `Layouts`/`Components` folders.
 */
class ComponentResolver(
    private val project: Project,
    private val aliasResolver: AliasResolver,
) {

    private val scope: GlobalSearchScope = GlobalSearchScope.projectScope(project)
    private val projectFileIndex: ProjectFileIndex = ProjectFileIndex.getInstance(project)

    private var componentFiles: List<VirtualFile>? = null
    private val filesByName = HashMap<String, List<VirtualFile>>()
    private val suffixCache = HashMap<String, VirtualFile?>()

    fun resolve(
        component: ParsedComponent?,
        imports: Map<String, String>,
        sourceFile: VirtualFile,
    ): VirtualFile? {
        if (component == null) return null

        component.importPath?.let { specifier ->
            aliasResolver.resolve(specifier, sourceFile)?.let { return it }
        }

        val identifier = component.identifier
        if (identifier != null) {
            imports[identifier]?.let { specifier ->
                aliasResolver.resolve(specifier, sourceFile)?.let { return it }
            }
            val matches = filesWithName("$identifier.vue")
            if (matches.size == 1) return matches.single()
        }

        val expression = component.expression
        for (literal in ComponentHints.literals(expression)) {
            resolveLiteral(literal, sourceFile)?.let { return it }
        }
        return null
    }

    /** Resolves the layout declared in `meta.layout`, e.g. `'guest'` → `GuestLayout.vue`. */
    fun resolveLayout(layout: String?): VirtualFile? {
        val value = layout?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.equals("none", true) || value.equals("false", true)) return null

        val pascal = value
            .split('-', '_', ' ', '.')
            .filter { it.isNotBlank() }
            .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
        if (pascal.isBlank()) return null

        for (candidate in listOf("${pascal}Layout.vue", "$pascal.vue")) {
            findBySuffix("Layouts/$candidate")?.let { return it }
            findBySuffix(candidate)?.let { return it }
        }
        return null
    }

    private fun resolveLiteral(raw: String, sourceFile: VirtualFile): VirtualFile? {
        val literal = raw.trim()
        if (literal.isEmpty() || literal.contains(' ')) return null
        if (literal in ignoredModules || literal.startsWith("@vue/")) return null

        val hasPathMarkers = literal.contains('/') ||
            literal.startsWith(".") ||
            literal.startsWith("@") ||
            literal.startsWith("~")
        val looksLikeComponent = literal.endsWith(".vue") ||
            hasPathMarkers ||
            literal.firstOrNull()?.isUpperCase() == true
        if (!looksLikeComponent) return null

        if (literal.startsWith(".") || literal.startsWith("@") ||
            literal.startsWith("~") || literal.startsWith("/")
        ) {
            aliasResolver.resolve(literal, sourceFile)?.let { return it }
            if (!literal.endsWith(".vue")) {
                aliasResolver.resolve("$literal.vue", sourceFile)?.let { return it }
            }
        }

        for (suffix in ComponentHints.suffixCandidates(literal)) {
            findBySuffix(suffix)?.let { return it }
        }
        return null
    }

    private fun findBySuffix(suffix: String): VirtualFile? {
        if (suffixCache.containsKey(suffix)) return suffixCache[suffix]

        val normalized = suffix.replace('\\', '/').trimStart('/')
        val fileName = normalized.substringAfterLast('/')
        val match = filesWithName(fileName)
            .firstOrNull { it.path.replace('\\', '/').endsWith("/$normalized", ignoreCase = true) }

        suffixCache[suffix] = match
        return match
    }

    private fun filesWithName(name: String): List<VirtualFile> =
        filesByName.getOrPut(name) {
            allComponents().filter { it.name.equals(name, ignoreCase = true) }
        }

    private fun allComponents(): List<VirtualFile> {
        componentFiles?.let { return it }
        val result = ArrayList<VirtualFile>()
        for (extension in COMPONENT_EXTENSIONS) {
            try {
                result.addAll(FilenameIndex.getAllFilesByExt(project, extension, scope))
            } catch (_: IndexNotReadyException) {
                return emptyList()
            } catch (canceled: ProcessCanceledException) {
                throw canceled
            } catch (_: Exception) {
                // Ignore a single failed index query; other extensions may still work.
            }
        }
        val usable = result.filter(::isUsable)
        componentFiles = usable
        return usable
    }

    private fun isUsable(file: VirtualFile): Boolean =
        file.isValid && !file.isDirectory &&
            projectFileIndex.isInContent(file) && !projectFileIndex.isInLibrary(file)

    companion object {
        private val COMPONENT_EXTENSIONS = listOf("vue", "tsx", "jsx")
        private val ignoredModules = setOf(
            "vue",
            "vue-router",
            "vuex",
            "pinia",
            "axios",
        )
    }
}
