package com.veuroutes.model

/**
 * The read-only view of a route that [com.veuroutes.search.RouteSearch] needs.
 *
 * Keeping the search independent from IntelliJ's [com.intellij.openapi.vfs.VirtualFile] makes the
 * matching and ranking rules testable with plain unit tests.
 */
interface SearchableRoute {
    val path: String
    val name: String?
    val componentLabel: String
    val componentRaw: String?
    val redirect: String?
    val alias: String?
    val metaTitle: String?
    val sourceDisplay: String
}
