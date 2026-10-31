package com.veuroutes.model

import com.intellij.openapi.vfs.VirtualFile

/**
 * A flattened, navigable Vue Router route. Nested children are flattened into [VueRoute.path]
 * (the full path), while the definition remains navigable through [sourceFile] + [sourceLine].
 */
data class VueRoute(
    override val path: String,
    override val name: String?,
    override val componentLabel: String,
    val componentFile: VirtualFile?,
    override val componentRaw: String?,
    val parentComponentLabel: String?,
    val parentComponentFile: VirtualFile?,
    val parentRoutePath: String?,
    val baseComponentLabel: String?,
    val baseComponentFile: VirtualFile?,
    override val redirect: String?,
    override val alias: String?,
    override val metaTitle: String?,
    val sourceFile: VirtualFile,
    val sourceLine: Int,
    val sourceColumn: Int,
    override val sourceDisplay: String,
) : SearchableRoute {

    val hasComponentFile: Boolean
        get() = componentFile != null

    val isRedirectOnly: Boolean
        get() = redirect != null && componentFile == null

    /**
     * True when a distinct base layout exists above this route. The route's own component and the
     * closest parent component are already reachable through the other menu items.
     */
    val showsBaseComponent: Boolean
        get() = baseComponentFile != null &&
            baseComponentFile != componentFile &&
            baseComponentFile != parentComponentFile

    fun tooltipHtml(): String {
        val rows = buildList {
            add("Path" to path)
            if (!name.isNullOrBlank()) add("Name" to name)
            if (!componentLabel.isNullOrBlank() && componentLabel != "—") {
                add("Component" to componentLabel)
            }
            if (!componentRaw.isNullOrBlank() && componentRaw != componentLabel) {
                add("Component expression" to componentRaw)
            }
            if (!parentComponentLabel.isNullOrBlank()) {
                add("Parent component" to parentComponentLabel)
            }
            if (!parentRoutePath.isNullOrBlank()) {
                add("Parent route" to parentRoutePath)
            }
            if (!baseComponentLabel.isNullOrBlank() && baseComponentLabel != parentComponentLabel) {
                add("Base component" to baseComponentLabel)
            }
            if (!redirect.isNullOrBlank()) add("Redirect" to redirect)
            if (!alias.isNullOrBlank()) add("Alias" to alias)
            if (!metaTitle.isNullOrBlank()) add("Meta title" to metaTitle)
            add("Defined in" to "$sourceDisplay:$sourceLine")
        }
        return buildString {
            append("<html><body style='width: 360px'>")
            for ((index, row) in rows.withIndex()) {
                if (index > 0) append("<br/>")
                append("<b>").append(escapeHtml(row.first)).append(":</b> ")
                append(escapeHtml(row.second))
            }
            val hints = buildList {
                if (hasComponentFile) add("Double-click the Component column to open it.")
                if (parentComponentFile != null) {
                    add("Right-click the row to open the parent layout component.")
                }
                if (showsBaseComponent) {
                    add("Right-click the row to open the base layout component.")
                }
            }
            if (hints.isNotEmpty()) {
                append("<br/><br/><i>")
                append(hints.joinToString(" "))
                append("</i>")
            }
            append("</body></html>")
        }
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
