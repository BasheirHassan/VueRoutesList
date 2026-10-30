package com.veuroutes.scan

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.veuroutes.model.VueRoute
import com.veuroutes.parser.ImportCollector
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

    fun scan(): ScanResult {
        val settings = VueRoutesSettings.getInstance()
        val maxBytes = settings.maxFileSizeKb.toLong() * 1024L
        val excluded = settings.excludedFolderNames()
        val scope = GlobalSearchScope.projectScope(project)

        val candidates = LinkedHashSet<VirtualFile>()
        for (extension in SCAN_EXTENSIONS) {
            candidates.addAll(FilenameIndex.getAllFilesByExt(project, extension, scope))
        }

        val routes = ArrayList<VueRoute>()
        var routerFiles = 0

        for (file in candidates.sortedBy { it.path }) {
            ProgressManager.checkCanceled()

            if (!file.isValid || file.isDirectory) continue
            if (projectFileIndex.isExcluded(file) || projectFileIndex.isInLibrary(file)) continue
            if (file.length <= 0L || file.length > maxBytes) continue
            if (file.name.endsWith(".min.js") || file.name.endsWith(".d.ts") || file.name.endsWith(".map")) continue

            val relativePath = relativePath(file) ?: continue
            val segments = relativePath.split('/')
            if (segments.dropLast(1).any { it.startsWith(".") }) continue
            if (segments.any { it.lowercase() in excluded }) continue

            val source = try {
                VfsUtilCore.loadText(file)
            } catch (_: IOException) {
                continue
            }

            if (!RouteParser.looksLikeRouteFile(source)) continue

            val parsedRoutes = RouteParser.parse(source)
            if (parsedRoutes.isEmpty()) continue

            routerFiles++
            val imports = ImportCollector.collect(source)
            val fileContext = FileContext(file, relativePath, imports)
            for (parsed in parsedRoutes) {
                routes.addAll(flatten(parsed, "", fileContext))
            }
        }

        return ScanResult(routes, routerFiles)
    }

    private data class FileContext(
        val file: VirtualFile,
        val relativePath: String,
        val imports: Map<String, String>,
    )

    private data class ParentComponent(
        val routePath: String,
        val label: String,
        val file: VirtualFile?,
    )

    private fun flatten(
        parsed: ParsedRoute,
        parentPath: String,
        context: FileContext,
        parent: ParentComponent? = null,
    ): List<VueRoute> {
        val fullPath = combinePaths(parentPath, parsed.path)
        val identifier = parsed.component?.identifier
        val expression = parsed.component?.expression
        val importSpecifier = parsed.component?.importPath
            ?: identifier?.let { context.imports[it] }
        val componentFile = importSpecifier?.let { aliasResolver.resolve(it, context.file) }
        val componentLabel = when {
            componentFile != null -> relativePath(componentFile) ?: componentFile.name
            !importSpecifier.isNullOrBlank() -> importSpecifier
            !identifier.isNullOrBlank() -> identifier
            !expression.isNullOrBlank() -> expression
            parsed.redirect != null -> "→ ${parsed.redirect}"
            else -> "—"
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
            redirect = parsed.redirect,
            alias = parsed.alias,
            metaTitle = parsed.metaTitle,
            sourceFile = context.file,
            sourceLine = parsed.line,
            sourceColumn = parsed.column,
            sourceDisplay = context.relativePath,
        )

        val childParent = if (parsed.component != null) {
            ParentComponent(fullPath, componentLabel, componentFile)
        } else {
            parent
        }

        val result = ArrayList<VueRoute>()
        result.add(route)
        for (child in parsed.children) {
            result.addAll(flatten(child, fullPath, context, childParent))
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
