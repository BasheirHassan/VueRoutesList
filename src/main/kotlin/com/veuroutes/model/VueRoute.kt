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
            if (hasComponentFile) {
                append("<br/><br/><i>Double-click the Component column to open it.")
                if (parentComponentFile != null) {
                    append(" Right-click the row to open the parent layout component.")
                }
                append("</i>")
            } else if (parentComponentFile != null) {
                append("<br/><br/><i>Right-click the row to open the parent layout component.</i>")
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
