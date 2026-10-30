package com.veuroutes.search

import com.veuroutes.model.SearchableRoute
import java.util.concurrent.ConcurrentHashMap

/**
 * Relevance-based search for Vue Router routes.
 *
 * A query is treated as a URL path when it starts with `/`, looks like a URL (`https://…`), is a
 * `path: '/…'` property value, or simply contains a slash and no spaces. Path queries understand:
 *
 * - route parameters (`:id`), optional parameters (`:id?`), custom patterns (`:id(\\d+)`), and
 *   catch-all segments (`:pathMatch(.*)*` or `*`);
 * - full URLs, base-relative URLs, hash-mode URLs (`/#/dashboard`), query strings and fragments;
 * - trailing slashes, aliases, and case differences.
 *
 * Any other query is split into words and every word must match (AND). Results are always ordered
 * by relevance: exact path, literal children, dynamic matches, middle-of-path matches, then plain
 * text matches (name, component, alias, redirect, meta title, source file).
 */
object RouteSearch {

    fun <T : SearchableRoute> filter(rawQuery: String, routes: List<T>): List<T> {
        val query = Query.parse(rawQuery) ?: return routes
        val hits = ArrayList<Hit<T>>()
        routes.forEachIndexed { index, route ->
            val score = query.score(route) ?: return@forEachIndexed
            hits.add(Hit(route, score, index))
        }
        hits.sortWith(
            compareByDescending<Hit<T>> { it.score }
                .thenBy { it.route.path.length }
                .thenBy { it.route.path.lowercase() }
                .thenBy { it.index },
        )
        return hits.map { it.route }
    }

    /** Returns the relevance score of [route] for [rawQuery], or `null` when it does not match. */
    fun score(rawQuery: String, route: SearchableRoute): Int? = Query.parse(rawQuery)?.score(route)

    private class Hit<T : SearchableRoute>(val route: T, val score: Int, val index: Int)

    private class Query(
        private val text: String,
        private val pathMode: Boolean,
        private val tokens: List<String>,
    ) {

        private val segments: List<String> =
            if (pathMode) splitPath(text) else emptyList()

        fun score(route: SearchableRoute): Int? =
            if (pathMode) scorePath(route) else scoreTokens(tokens, route)

        private fun scorePath(route: SearchableRoute): Int? {
            val routePath = normalizeRoutePath(route.path)
            if (routePath.equals(text, ignoreCase = true)) return EXACT_PATH

            val aliases = route.alias?.split(',')
            if (aliases != null && aliases.any { normalizeAlias(it).equals(text, ignoreCase = true) }) {
                return EXACT_ALIAS
            }

            structuralScore(routePath)?.let { return it }

            if (routePath.lowercase().contains(text)) return PATH_SUBSTRING

            return scoreTokens(listOf(text), route)
        }

        private fun structuralScore(routePath: String): Int? {
            val route = cachedSegments(routePath)
            if (segments.isEmpty()) {
                return if (route.isNotEmpty() && route.all { it.kind == SegmentKind.WILDCARD }) {
                    CATCH_ALL
                } else {
                    null
                }
            }
            if (route.isEmpty()) return null

            matchSegments(segments, route, 0, 0)?.let { stats ->
                return when {
                    stats.extra > 0 && stats.dynamicCount == 0 ->
                        PREFIX_LITERAL - stats.extra * 300 + stats.staticCount * 10

                    stats.extra > 0 ->
                        PREFIX_DYNAMIC - stats.extra * 300 - stats.dynamicCount * 50 -
                            stats.wildcardCount * 500 + stats.customCount * 100 +
                            stats.staticCount * 10

                    stats.staticCount == 0 && stats.wildcardCount > 0 -> CATCH_ALL

                    stats.dynamicCount == 0 -> EXACT_PATH

                    else -> FULL_DYNAMIC - stats.dynamicCount * 300 -
                        stats.wildcardCount * 2_000 + stats.customCount * 150 +
                        stats.staticCount * 10
                }
            }

            if (segments.size >= route.size) return null

            var best: Int? = null
            for (offset in 1..(route.size - segments.size)) {
                val stats = matchSegments(segments, route.subList(offset, route.size), 0, 0) ?: continue
                val score = MIDDLE_MATCH - offset * 200 - stats.extra * 100 -
                    stats.dynamicCount * 1_000 - stats.wildcardCount * 500 +
                    stats.customCount * 50 + stats.staticCount * 5
                val current = best
                if (current == null || score > current) best = score
            }
            return best
        }

        companion object {

            fun parse(raw: String): Query? {
                var text = raw.trim()
                if (text.isEmpty()) return null
                text = text.trimEnd(',', ';', ' ')

                text = stripQuotes(text)
                PROPERTY_PREFIX.find(text)?.let { match ->
                    text = stripQuotes(match.groupValues[1].trim())
                }

                val hadOrigin = ORIGIN_PREFIX.containsMatchIn(text)
                text = ORIGIN_PREFIX.replace(text, "")
                when {
                    text.isEmpty() -> text = "/"
                    hadOrigin && !text.startsWith("/") -> text = "/$text"
                }

                if (!looksLikePath(text)) {
                    val tokens = tokenize(text).map { it.lowercase() }
                    if (tokens.isEmpty()) return null
                    return Query(text.lowercase(), pathMode = false, tokens = tokens)
                }

                return Query(normalizeQueryPath(text), pathMode = true, tokens = emptyList())
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Path matching
    // ---------------------------------------------------------------------------------------------

    private enum class SegmentKind { STATIC, PARAM, OPTIONAL_PARAM, WILDCARD }

    private class RouteSegment(
        val kind: SegmentKind,
        val literal: String = "",
        val pattern: Regex? = null,
    ) {
        fun matches(value: String): Boolean = when (kind) {
            SegmentKind.STATIC -> literal.equals(value, ignoreCase = true)
            else -> pattern?.matches(value) ?: true
        }
    }

    private data class SegmentStats(
        val staticCount: Int = 0,
        val dynamicCount: Int = 0,
        val customCount: Int = 0,
        val wildcardCount: Int = 0,
        val extra: Int = 0,
    )

    private fun matchSegments(
        query: List<String>,
        route: List<RouteSegment>,
        qi: Int,
        ri: Int,
    ): SegmentStats? {
        if (qi >= query.size) {
            var dynamic = 0
            var wildcards = 0
            var index = ri
            while (index < route.size) {
                when (route[index].kind) {
                    SegmentKind.OPTIONAL_PARAM -> {
                        dynamic++
                        index++
                    }

                    SegmentKind.WILDCARD -> {
                        dynamic++
                        wildcards++
                        index++
                    }

                    else -> return SegmentStats(
                        dynamicCount = dynamic,
                        wildcardCount = wildcards,
                        extra = route.size - index,
                    )
                }
            }
            return SegmentStats(dynamicCount = dynamic, wildcardCount = wildcards)
        }
        if (ri >= route.size) return null

        val segment = route[ri]
        return when (segment.kind) {
            SegmentKind.STATIC -> {
                if (!segment.matches(query[qi])) {
                    null
                } else {
                    matchSegments(query, route, qi + 1, ri + 1)
                        ?.let { it.copy(staticCount = it.staticCount + 1) }
                }
            }

            SegmentKind.PARAM -> {
                if (!segment.matches(query[qi])) {
                    null
                } else {
                    matchSegments(query, route, qi + 1, ri + 1)?.let { candidate ->
                        candidate.copy(
                            dynamicCount = candidate.dynamicCount + 1,
                            customCount = candidate.customCount + if (segment.pattern != null) 1 else 0,
                        )
                    }
                }
            }

            SegmentKind.OPTIONAL_PARAM -> {
                val custom = if (segment.pattern != null) 1 else 0
                var best: SegmentStats? = null
                if (segment.matches(query[qi])) {
                    matchSegments(query, route, qi + 1, ri + 1)?.let { candidate ->
                        val stats = candidate.copy(
                            dynamicCount = candidate.dynamicCount + 1,
                            customCount = candidate.customCount + custom,
                        )
                        if (isBetter(stats, best)) best = stats
                    }
                }
                matchSegments(query, route, qi, ri + 1)?.let { candidate ->
                    val stats = candidate.copy(
                        dynamicCount = candidate.dynamicCount + 1,
                        customCount = candidate.customCount + custom,
                    )
                    if (isBetter(stats, best)) best = stats
                }
                best
            }

            SegmentKind.WILDCARD -> {
                var best: SegmentStats? = null
                for (take in 0..(query.size - qi)) {
                    matchSegments(query, route, qi + take, ri + 1)?.let { candidate ->
                        val stats = candidate.copy(
                            dynamicCount = candidate.dynamicCount + 1,
                            wildcardCount = candidate.wildcardCount + 1,
                        )
                        if (isBetter(stats, best)) best = stats
                    }
                }
                best
            }
        }
    }

    private fun isBetter(candidate: SegmentStats, current: SegmentStats?): Boolean {
        if (current == null) return true
        if (candidate.extra != current.extra) return candidate.extra < current.extra
        if (candidate.staticCount != current.staticCount) {
            return candidate.staticCount > current.staticCount
        }
        if (candidate.dynamicCount != current.dynamicCount) {
            return candidate.dynamicCount < current.dynamicCount
        }
        if (candidate.customCount != current.customCount) {
            return candidate.customCount > current.customCount
        }
        return candidate.wildcardCount < current.wildcardCount
    }

    private fun parseSegment(raw: String): RouteSegment {
        if (raw == "*" || raw == "**") return RouteSegment(SegmentKind.WILDCARD)
        if (!raw.startsWith(":")) return RouteSegment(SegmentKind.STATIC, literal = raw)

        var body = raw.substring(1)
        var optional = false
        if (body.endsWith("?")) {
            optional = true
            body = body.dropLast(1)
        }

        var repeatable = false
        if (body.endsWith("*") || body.endsWith("+")) {
            repeatable = true
            body = body.dropLast(1)
        }

        val open = body.indexOf('(')
        val pattern = if (open >= 0 && body.endsWith(")")) {
            val expression = body.substring(open + 1, body.length - 1)
            runCatching { Regex(expression, RegexOption.IGNORE_CASE) }.getOrNull()
        } else {
            null
        }

        return when {
            repeatable -> RouteSegment(SegmentKind.WILDCARD)
            optional -> RouteSegment(SegmentKind.OPTIONAL_PARAM, pattern = pattern)
            else -> RouteSegment(SegmentKind.PARAM, pattern = pattern)
        }
    }

    private val segmentCache = ConcurrentHashMap<String, List<RouteSegment>>()

    private fun cachedSegments(routePath: String): List<RouteSegment> {
        if (segmentCache.size >= CACHE_LIMIT) segmentCache.clear()
        return segmentCache.getOrPut(routePath) {
            splitPath(routePath).map(::parseSegment)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Text matching
    // ---------------------------------------------------------------------------------------------

    private class RouteDocument(route: SearchableRoute) {
        val path: String = normalizeRoutePath(route.path)
        val lowerPath: String = path.lowercase()
        val segments: List<String> = splitPath(lowerPath)
        val name: String? = route.name?.lowercase()
        val component: String = route.componentLabel.lowercase()
        val componentRaw: String? = route.componentRaw?.lowercase()
        val alias: String? = route.alias?.lowercase()
        val redirect: String? = route.redirect?.lowercase()
        val metaTitle: String? = route.metaTitle?.lowercase()
        val source: String = route.sourceDisplay.lowercase()
        val compactPath: String = compact(lowerPath)
        val compactName: String? = name?.let(::compact)
        val compactComponent: String = compact(component)
    }

    private fun scoreTokens(tokens: List<String>, route: SearchableRoute): Int? {
        if (tokens.isEmpty()) return null
        val document = RouteDocument(route)

        var total = 0
        for (token in tokens) {
            total += tokenScore(token, document) ?: return null
        }

        total += TOKEN_BONUS
        if (tokens.size > 1 && orderedInPath(tokens, document.lowerPath)) {
            total += ORDERED_BONUS
        }
        return total
    }

    private fun tokenScore(token: String, doc: RouteDocument): Int? {
        val candidates = buildList {
            add(token)
            val trimmed = token.trim('/')
            if (trimmed.isNotEmpty() && trimmed != token) add(trimmed)
        }

        var best: Int? = null
        for (candidate in candidates) {
            val score = singleTokenScore(candidate, doc)
            if (score != null && (best == null || score > best)) best = score
        }
        return best
    }

    private fun singleTokenScore(value: String, doc: RouteDocument): Int? {
        if (value.isEmpty()) return null
        var best = 0

        fun consider(score: Int) {
            if (score > best) best = score
        }

        doc.name?.let { name ->
            when {
                name == value -> consider(10_000)
                name.startsWith(value) && value.length >= 2 -> consider(8_600)
                name.split('-', '_', '.', ' ').any { it == value } -> consider(8_200)
                name.contains(value) -> consider(7_000)
            }
        }

        val componentFile = doc.component.substringAfterLast('/')
        val componentName = componentFile.substringBeforeLast('.')
        when {
            componentName == value -> consider(8_000)
            componentName.startsWith(value) && value.length >= 3 -> consider(7_200)
            doc.component.contains(value) -> consider(6_200)
        }

        doc.componentRaw?.let { raw ->
            if (raw.contains(value)) consider(5_800)
        }

        when {
            doc.segments.any { it == value } -> consider(7_900)
            value.length >= 2 && doc.segments.any { it.startsWith(value) } -> consider(6_600)
            doc.lowerPath.contains(value) -> consider(6_000)
        }

        val compactValue = compact(value)
        if (compactValue.length >= 2) {
            when {
                doc.compactName != null && doc.compactName == compactValue -> consider(9_500)
                doc.compactName != null && doc.compactName.contains(compactValue) -> consider(7_400)
                doc.compactComponent.contains(compactValue) -> consider(6_400)
                doc.compactPath.contains(compactValue) -> consider(5_600)
            }
        }

        doc.alias?.let { alias ->
            if (alias.contains(value)) consider(5_000)
        }
        doc.metaTitle?.let { title ->
            if (title.contains(value)) consider(4_800)
        }
        doc.redirect?.let { redirect ->
            if (redirect.contains(value)) consider(4_600)
        }
        if (doc.source.contains(value)) consider(4_200)

        return if (best > 0) best else null
    }

    private fun orderedInPath(tokens: List<String>, path: String): Boolean {
        var from = 0
        for (token in tokens) {
            val index = path.indexOf(token, from)
            if (index < 0) return false
            from = index + token.length
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Normalization helpers
    // ---------------------------------------------------------------------------------------------

    private fun normalizeQueryPath(input: String): String {
        var text = input.trim()
        if (text.startsWith("/#/")) {
            text = text.removePrefix("/#")
        } else if (text.startsWith("#/")) {
            text = text.removePrefix("#")
        }

        val cut = text.indexOfFirst { it == '?' || it == '#' }
        if (cut >= 0) text = text.substring(0, cut)

        text = MULTIPLE_SLASHES.replace(text, "/")
        if (!text.startsWith("/")) text = "/$text"
        text = text.trimEnd('/')
        if (text.isEmpty()) text = "/"
        return text.lowercase()
    }

    private fun normalizeRoutePath(value: String): String {
        var path = MULTIPLE_SLASHES.replace(value.trim(), "/")
        path = path.trimEnd('/')
        return path.ifEmpty { "/" }
    }

    private fun normalizeAlias(value: String): String {
        val alias = normalizeRoutePath(value)
        return if (alias.startsWith("/")) alias else "/$alias"
    }

    private fun splitPath(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }

    private fun stripQuotes(value: String): String {
        if (value.length < 2) return value
        val first = value.first()
        if (first == value.last() && first in QUOTES) {
            return value.substring(1, value.length - 1)
        }
        return value
    }

    private fun looksLikePath(value: String): Boolean =
        value.startsWith("/") ||
            value.startsWith("#/") ||
            (!value.contains(' ') && value.contains('/'))

    private fun tokenize(input: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null

        for (char in input) {
            when {
                quote != null && char == quote -> quote = null
                quote == null && char in QUOTES -> quote = char
                quote == null && char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }

                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private fun compact(value: String): String = buildString {
        for (char in value) {
            if (char.isLetterOrDigit()) append(char.lowercaseChar())
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------------------------------

    private const val EXACT_PATH = 100_000
    private const val EXACT_ALIAS = 90_000
    private const val PREFIX_LITERAL = 72_000
    private const val FULL_DYNAMIC = 65_000
    private const val PREFIX_DYNAMIC = 60_000
    private const val MIDDLE_MATCH = 50_000
    private const val PATH_SUBSTRING = 30_000
    private const val CATCH_ALL = 1_000
    private const val TOKEN_BONUS = 1_000
    private const val ORDERED_BONUS = 800
    private const val CACHE_LIMIT = 5_000

    private val QUOTES = setOf('\'', '"', '`')
    private val PROPERTY_PREFIX = Regex("""^(?:path|url)\s*[:=]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val ORIGIN_PREFIX = Regex("""^[a-z][a-z0-9+.-]*://[^/]*""", RegexOption.IGNORE_CASE)
    private val MULTIPLE_SLASHES = Regex("/{2,}")
}
