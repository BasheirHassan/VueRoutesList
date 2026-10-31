package com.veuroutes.scan

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.veuroutes.model.VueRoute
import com.veuroutes.parser.ImportCollector
import com.veuroutes.parser.ParsedModule
import com.veuroutes.parser.ParsedRoute
import com.veuroutes.parser.RouteParser
import com.veuroutes.settings.VueRoutesSettings
import java.io.IOException

data class ScanResult(
    val routes: List<VueRoute>,
    val routerFileCount: Int,
)

class VueRoutesScanner(private val project: Project) {

    private val projectFileIndex = ProjectFileIndex.getInstance(project)
    private val baseDir: VirtualFile? = project.baseDir
    private val aliasResolver = AliasResolver(project)
    private val componentResolver = ComponentResolver(project, aliasResolver)

    private val sourceCache = HashMap<String, String?>()
    private val moduleCache = HashMap<String, ParsedModule?>()
    private val fileContexts = HashMap<String, FileContext>()
    private val loadingModules = HashSet<String>()
    private val composedModules = HashSet<String>()

    fun scan(): ScanResult {
        val settings = VueRoutesSettings.getInstance()
        val maxBytes = settings.maxFileSizeKb.toLong() * 1024L
        val excluded = settings.excludedFolderNames()
        val scope = GlobalSearchScope.projectScope(project)

        val candidates = LinkedHashSet<VirtualFile>()
        for (extension in SCAN_EXTENSIONS) {
            candidates.addAll(FilenameIndex.getAllFilesByExt(project, extension, scope))
        }

        val modules = LinkedHashMap<VirtualFile, ParsedModule>()
        for (file in candidates.sortedBy { it.path }) {
            ProgressManager.checkCanceled()

            if (!isScannable(file, maxBytes, excluded)) continue
            val module = loadModule(file) ?: continue
            if (module.routes.isEmpty() && module.arrays.values.all { it.isEmpty() }) continue
            modules[file] = module
        }

        // Fragments imported and spread into another router file are already part of that file's
        // route tree. Emitting them again would duplicate routes and lose their parent chain.
        val entries = modules.keys
            .filter { it.path !in composedModules }
            .ifEmpty { modules.keys.toList() }

        val routes = ArrayList<VueRoute>()
        val seen = HashSet<String>()
        for (file in entries) {
            ProgressManager.checkCanceled()
            val module = modules[file] ?: continue
            val context = fileContexts[file.path] ?: continue
            for (parsed in module.routes) {
                for (route in flatten(parsed, "", context)) {
                    val key = "${route.sourceFile.path}#${route.sourceLine}#${route.path}#${route.name}"
                    if (seen.add(key)) routes.add(route)
                }
            }
        }

        return ScanResult(routes, modules.size)
    }

    private fun isScannable(
        file: VirtualFile,
        maxBytes: Long,
        excluded: Set<String>,
    ): Boolean {
        if (!file.isValid || file.isDirectory) return false
        if (projectFileIndex.isExcluded(file) || projectFileIndex.isInLibrary(file)) return false
        if (file.length <= 0L || file.length > maxBytes) return false
        if (file.name.endsWith(".min.js") || file.name.endsWith(".d.ts") || file.name.endsWith(".map")) {
            return false
        }

        val relativePath = relativePath(file) ?: return false
        val segments = relativePath.split('/')
        if (segments.dropLast(1).any { it.startsWith(".") }) return false
        return segments.none { it.lowercase() in excluded }
    }

    /**
     * Parses [file] and recursively resolves route arrays imported from other modules, so
     * `children: [...accountRoutes]` keeps its full path and its parent layout.
     */
    private fun loadModule(file: VirtualFile): ParsedModule? {
        val key = file.path
        if (moduleCache.containsKey(key)) return moduleCache[key]
        if (!file.isValid || projectFileIndex.isExcluded(file) || projectFileIndex.isInLibrary(file)) {
            return null
        }
        if (!loadingModules.add(key)) return null

        try {
            val source = readSource(file)
            if (source == null || !RouteParser.looksLikeRouteFile(source)) {
                moduleCache[key] = null
                return null
            }

            val imports = ImportCollector.collect(source)
            fileContexts[key] = FileContext(
                file = file,
                relativePath = relativePath(file) ?: file.path,
                imports = imports,
            )
            val module = RouteParser.parseModule(source) { name ->
                val specifier = imports[name] ?: return@parseModule null
                val target = aliasResolver.resolve(specifier, file) ?: return@parseModule null
                if (target == file) return@parseModule null

                val targetModule = loadModule(target) ?: return@parseModule null
                val matched = targetModule.arrays[name]
                    ?: if (targetModule.arrays.size == 1) targetModule.arrays.values.first() else null
                if (matched != null) composedModules.add(target.path)
                matched
            }?.let { stampOrigin(it, key) }

            moduleCache[key] = module
            return module
        } finally {
            loadingModules.remove(key)
        }
    }

    /**
     * Marks every route with the file that declared it. Routes imported from a fragment already
     * carry their own origin, so they keep pointing at their real source file.
     */
    private fun stampOrigin(module: ParsedModule, path: String): ParsedModule =
        ParsedModule(
            routes = stampOrigin(module.routes, path),
            arrays = module.arrays.mapValues { (_, routes) -> stampOrigin(routes, path) },
        )

    private fun stampOrigin(routes: List<ParsedRoute>, path: String): List<ParsedRoute> =
        routes.map { route ->
            route.copy(
                children = stampOrigin(route.children, path),
                origin = route.origin ?: path,
            )
        }

    private fun readSource(file: VirtualFile): String? {
        val key = file.path
        if (sourceCache.containsKey(key)) return sourceCache[key]
        val source = try {
            VfsUtilCore.loadText(file)
        } catch (_: IOException) {
            null
        } catch (canceled: ProcessCanceledException) {
            throw canceled
        } catch (_: Exception) {
            null
        }
        sourceCache[key] = source
        return source
    }

    private data class FileContext(
        val file: VirtualFile,
        val relativePath: String,
        val imports: Map<String, String>,
    )

    private data class ParentComponent(
        val routePath: String,
        val label: String,
        val file: VirtualFile,
    )

    private fun flatten(
        parsed: ParsedRoute,
        parentPath: String,
        fallbackContext: FileContext,
        parent: ParentComponent? = null,
        base: ParentComponent? = null,
    ): List<VueRoute> {
        val context = parsed.origin?.let { fileContexts[it] } ?: fallbackContext
        val fullPath = combinePaths(parentPath, parsed.path)
        val componentFile = componentResolver.resolve(parsed.component, context.imports, context.file)
        val identifier = parsed.component?.identifier
        val importSpecifier = parsed.component?.importPath
        val expression = parsed.component?.expression

        val componentLabel = when {
            componentFile != null -> relativePath(componentFile) ?: componentFile.name
            !importSpecifier.isNullOrBlank() -> importSpecifier
            !identifier.isNullOrBlank() -> identifier
            !expression.isNullOrBlank() -> expression
            parsed.redirect != null -> "→ ${parsed.redirect}"
            else -> "—"
        }

        val selfComponent = if (componentFile != null) {
            ParentComponent(fullPath, componentLabel, componentFile)
        } else {
            null
        }

        val layout = base
            ?: componentResolver.resolveLayout(parsed.metaLayout)?.let { file ->
                ParentComponent(fullPath, relativePath(file) ?: file.name, file)
            }

        val route = VueRoute(
            path = fullPath,
            name = parsed.name,
            componentLabel = componentLabel,
            componentFile = componentFile,
            componentRaw = parsed.component?.expression,
            parentComponentLabel = parent?.label,
            parentComponentFile = parent?.file,
            parentRoutePath = parent?.routePath,
            baseComponentLabel = layout?.label,
            baseComponentFile = layout?.file,
            redirect = parsed.redirect,
            alias = parsed.alias,
            metaTitle = parsed.metaTitle,
            sourceFile = context.file,
            sourceLine = parsed.line,
            sourceColumn = parsed.column,
            sourceDisplay = context.relativePath,
        )

        val result = ArrayList<VueRoute>()
        result.add(route)
        for (child in parsed.children) {
            // Keep the topmost component in the chain as the base; deeper layouts stay parents.
            result.addAll(
                flatten(
                    parsed = child,
                    parentPath = fullPath,
                    fallbackContext = context,
                    parent = selfComponent ?: parent,
                    base = layout ?: selfComponent,
                ),
            )
        }
        return result
    }

    private fun combinePaths(parentPath: String, rawChildPath: String): String {
        val child = rawChildPath.trim()
        if (child.startsWith("/")) return child
        if (child.isEmpty()) return parentPath.ifBlank { "/" }
        return if (parentPath.isBlank() || parentPath == "/") {
            "/$child"
        } else {
            "${parentPath.trimEnd('/')}/$child"
        }
    }

    private fun relativePath(file: VirtualFile): String? {
        val base = baseDir ?: return file.path
        return VfsUtilCore.getRelativePath(file, base, '/') ?: file.path
    }

    companion object {
        private val SCAN_EXTENSIONS = listOf("js", "ts", "mjs", "cjs", "mts", "cts", "jsx", "tsx")
    }
}
