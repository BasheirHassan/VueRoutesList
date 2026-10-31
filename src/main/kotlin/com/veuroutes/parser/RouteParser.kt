package com.veuroutes.parser

/**
 * A component reference found on a route:
 * - [importPath] is set for lazy/dynamic imports and `require(...)`.
 * - [identifier] is set for a plain local identifier such as `HomeView`.
 * - [expression] always keeps the original (compacted) expression for display and debugging.
 */
data class ParsedComponent(
    val identifier: String?,
    val importPath: String?,
    val expression: String,
)

data class ParsedRoute(
    val path: String,
    val name: String?,
    val component: ParsedComponent?,
    val redirect: String?,
    val alias: String?,
    val metaTitle: String?,
    val line: Int,
    val column: Int,
    val children: List<ParsedRoute>,
    val metaLayout: String? = null,
    /** Absolute path of the module that declared this route, filled in by the scanner. */
    val origin: String? = null,
)

/**
 * The result of parsing one router module.
 *
 * [routes] is the navigable entry point of the file (`const routes = [...]`, `export default [...]`,
 * or the outermost array of a route fragment). [arrays] exposes every top-level array in the file by
 * variable name so another router file can expand `...importedRoutes`.
 */
data class ParsedModule(
    val routes: List<ParsedRoute>,
    val arrays: Map<String, List<ParsedRoute>>,
)

/**
 * A deliberately small, dependency-free route extractor for Vue Router configuration files.
 *
 * It understands the common shapes used by Vue Router 3 and 4:
 *
 * ```ts
 * const routes: RouteRecordRaw[] = [
 *   { path: '/', name: 'home', component: () => import('@/views/Home.vue') },
 *   { path: '/admin', children: [{ path: 'users', component: AdminUsers }] }
 * ]
 * createRouter({ history: createWebHistory(), routes })
 * ```
 *
 * It does not evaluate JavaScript. Values that cannot be expressed as literals are kept as raw
 * strings, which is enough for a route explorer and safe to run on every file in a project.
 */
object RouteParser {

    private val dynamicImportRegex = Regex(
        """\bimport\s*\(\s*(?:/\*[\s\S]*?\*/\s*|//[^\n]*\n?\s*)*['"]([^'"]+)['"]""",
    )
    private val requireRegex = Regex(
        """\brequire\s*\(\s*(?:/\*[\s\S]*?\*/\s*|//[^\n]*\n?\s*)*['"]([^'"]+)['"]""",
    )

    // `component: pages['./pages/Create.vue']` — a map produced by `import.meta.glob` indexed by a
    // file key. Keys are always real module paths (with extension) relative to the file that
    // declared the glob, so they resolve like a relative import.
    private val globIndexRegex = Regex(
        """\[\s*['"]([^'"]+\.(?:vue|tsx?|jsx?|mjs|cjs))['"]\s*\]""",
    )
    private val whitespaceRegex = Regex("""\s+""")

    private val nonComponentKeywords = setOf(
        "defineAsyncComponent",
        "defineComponent",
        "defineNuxtComponent",
        "h",
        "require",
        "import",
        "new",
        "function",
        "async",
        "await",
        "class",
        "()",
        "null",
        "undefined",
    )

    /**
     * A cheap pre-check used before tokenizing a file. It intentionally accepts a little more than
     * strictly necessary: the parser then confirms that the file contains an actual routes array.
     */
    fun looksLikeRouteFile(source: String): Boolean {
        if (source.isEmpty() || !source.contains("path")) return false
        val routerMarker = source.contains("vue-router") ||
            source.contains("createRouter") ||
            source.contains("createWebHistory") ||
            source.contains("createWebHashHistory") ||
            source.contains("VueRouter") ||
            source.contains("RouteRecordRaw") ||
            source.contains("new Router")
        if (routerMarker) return true

        val hasComponent = source.contains("component") || source.contains("redirect")
        val hasRouteContext = source.contains("routes") ||
            source.contains("Routes") ||
            source.contains("children") ||
            source.contains("export default") ||
            source.contains("defineRoutes")
        return hasComponent && hasRouteContext
    }

    fun parse(source: String): List<ParsedRoute> = parseModule(source)?.routes ?: emptyList()

    /**
     * Parses a router file and exposes its named route arrays.
     *
     * [externalArrays] resolves a spread of an array that lives in another module, e.g.
     * `children: [...accountRoutes]`. The provider receives the local identifier used in the file.
     */
    fun parseModule(
        source: String,
        externalArrays: (String) -> List<ParsedRoute>? = { null },
    ): ParsedModule? {
        if (!looksLikeRouteFile(source)) return null

        val tokens = Lexer(source).tokenize()
        val localArrays = findArrayVariables(tokens)
        val context = ParseContext(source, tokens, localArrays, externalArrays)
        val arrays = LinkedHashMap<String, List<ParsedRoute>>()
        for ((name, openIndex) in localArrays) {
            val parsed = parseRouteArray(context, openIndex, HashSet())
            if (parsed.isNotEmpty()) arrays[name] = parsed
        }

        val routeArrays = findRouteArrayStarts(tokens, localArrays)
        val routes = routeArrays.flatMap { parseRouteArray(context, it, HashSet()) }
        if (routes.isEmpty() && arrays.isEmpty()) return null
        return ParsedModule(routes, arrays)
    }

    // ---------------------------------------------------------------------------------------------
    // Tokenizer
    // ---------------------------------------------------------------------------------------------

    private enum class TokenKind { IDENT, STRING, TEMPLATE, NUMBER, PUNCT }

    private data class Token(
        val kind: TokenKind,
        val text: String,
        val value: String?,
        val start: Int,
        val end: Int,
        val line: Int,
        val column: Int,
    )

    private class Lexer(private val source: String) {
        private var index = 0
        private var line = 1
        private var column = 1
        private val tokens = ArrayList<Token>()

        fun tokenize(): List<Token> {
            while (index < source.length) {
                val c = source[index]
                when {
                    c.isWhitespace() -> advance()
                    c == '/' && peek(1) == '/' -> skipLineComment()
                    c == '/' && peek(1) == '*' -> skipBlockComment()
                    c == '\'' || c == '"' -> readString(c)
                    c == '`' -> readTemplate()
                    isIdentifierStart(c) -> readIdentifier()
                    c.isDigit() -> readNumber()
                    else -> readPunctuation()
                }
            }
            return tokens
        }

        private fun advance(): Char {
            val c = source[index++]
            when (c) {
                '\r' -> {
                    if (index < source.length && source[index] == '\n') index++
                    line++
                    column = 1
                }

                '\n' -> {
                    line++
                    column = 1
                }

                else -> column++
            }
            return c
        }

        private fun peek(offset: Int): Char? = source.getOrNull(index + offset)

        private fun skipLineComment() {
            while (index < source.length && source[index] != '\n' && source[index] != '\r') {
                advance()
            }
        }

        private fun skipBlockComment() {
            advance()
            advance()
            while (index < source.length) {
                if (source[index] == '*' && peek(1) == '/') {
                    advance()
                    advance()
                    return
                }
                advance()
            }
        }

        private fun readString(quote: Char) {
            val start = index
            val startLine = line
            val startColumn = column
            advance()
            val value = StringBuilder()
            while (index < source.length) {
                val c = source[index]
                when (c) {
                    '\\' -> {
                        advance()
                        if (index < source.length) {
                            val escaped = advance()
                            value.append(
                                when (escaped) {
                                    'n' -> '\n'
                                    'r' -> '\r'
                                    't' -> '\t'
                                    'b' -> '\b'
                                    'f' -> '\u000C'
                                    '0' -> '\u0000'
                                    else -> escaped
                                },
                            )
                        }
                    }

                    quote -> {
                        advance()
                        tokens.add(
                            Token(
                                TokenKind.STRING,
                                source.substring(start, index),
                                value.toString(),
                                start,
                                index,
                                startLine,
                                startColumn,
                            ),
                        )
                        return
                    }

                    else -> value.append(advance())
                }
            }
            tokens.add(
                Token(
                    TokenKind.STRING,
                    source.substring(start, index),
                    value.toString(),
                    start,
                    index,
                    startLine,
                    startColumn,
                ),
            )
        }

        private fun readTemplate() {
            val start = index
            val startLine = line
            val startColumn = column
            advance()
            val value = StringBuilder()
            while (index < source.length) {
                val c = source[index]
                when (c) {
                    '\\' -> {
                        advance()
                        if (index < source.length) {
                            val escaped = advance()
                            value.append(
                                when (escaped) {
                                    'n' -> '\n'
                                    'r' -> '\r'
                                    't' -> '\t'
                                    '`' -> '`'
                                    '\\' -> '\\'
                                    else -> escaped
                                },
                            )
                        }
                    }

                    '`' -> {
                        advance()
                        tokens.add(
                            Token(
                                TokenKind.TEMPLATE,
                                source.substring(start, index),
                                value.toString(),
                                start,
                                index,
                                startLine,
                                startColumn,
                            ),
                        )
                        return
                    }

                    else -> value.append(advance())
                }
            }
            tokens.add(
                Token(
                    TokenKind.TEMPLATE,
                    source.substring(start, index),
                    value.toString(),
                    start,
                    index,
                    startLine,
                    startColumn,
                ),
            )
        }

        private fun readIdentifier() {
            val start = index
            val startLine = line
            val startColumn = column
            while (index < source.length && isIdentifierPart(source[index])) {
                advance()
            }
            val text = source.substring(start, index)
            tokens.add(Token(TokenKind.IDENT, text, text, start, index, startLine, startColumn))
        }

        private fun readNumber() {
            val start = index
            val startLine = line
            val startColumn = column
            while (index < source.length) {
                val c = source[index]
                if (!(c.isLetterOrDigit() || c == '.' || c == '_')) break
                advance()
            }
            tokens.add(
                Token(
                    TokenKind.NUMBER,
                    source.substring(start, index),
                    null,
                    start,
                    index,
                    startLine,
                    startColumn,
                ),
            )
        }

        private fun readPunctuation() {
            val start = index
            val startLine = line
            val startColumn = column
            if (source.startsWith("...", index)) {
                advance()
                advance()
                advance()
            } else {
                advance()
            }
            tokens.add(
                Token(
                    TokenKind.PUNCT,
                    source.substring(start, index),
                    null,
                    start,
                    index,
                    startLine,
                    startColumn,
                ),
            )
        }

        private fun isIdentifierStart(c: Char): Boolean =
            c == '_' || c == '$' || c.isLetter() || c.code >= 0x80

        private fun isIdentifierPart(c: Char): Boolean =
            c == '_' || c == '$' || c.isLetterOrDigit() || c.code >= 0x80
    }

    // ---------------------------------------------------------------------------------------------
    // Locating route arrays
    // ---------------------------------------------------------------------------------------------

    private data class ParseContext(
        val source: String,
        val tokens: List<Token>,
        val localArrays: Map<String, Int>,
        val externalArrays: (String) -> List<ParsedRoute>?,
    )

    private fun findArrayVariables(tokens: List<Token>): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        for (i in 0 until tokens.size - 2) {
            val keyword = tokens[i]
            if (keyword.kind != TokenKind.IDENT || keyword.text !in setOf("const", "let", "var")) continue
            val name = tokens[i + 1]
            if (name.kind != TokenKind.IDENT) continue

            var j = i + 2
            var depth = 0
            val limit = minOf(tokens.size, i + 160)
            while (j < limit) {
                when (tokens[j].text) {
                    "=" -> {
                        if (depth == 0) {
                            if (tokens.getOrNull(j + 1)?.text == "[") result[name.text] = j + 1
                            break
                        }
                    }

                    "{", "[", "(" -> depth++
                    "}", "]", ")" -> {
                        if (depth > 0) depth-- else break
                    }

                    ";" -> break
                }
                j++
            }
        }
        return result
    }

    private fun findRouteArrayStarts(
        tokens: List<Token>,
        localArrays: Map<String, Int>,
    ): List<Int> {
        val starts = LinkedHashSet<Int>()
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.kind != TokenKind.IDENT && token.kind != TokenKind.STRING) continue
            val key = token.value ?: token.text
            if (key != "routes") continue
            val start = assignedArrayStart(tokens, i, localArrays)
            if (start != null) starts.add(start)
        }
        if (starts.isNotEmpty()) return starts.toList()

        // Fallback for route modules that export a bare array:
        //     export default [{ path: '/', component: Home }]
        // Only direct object elements are considered, and nested arrays are discarded.
        val candidates = tokens.indices.filter { i ->
            tokens[i].text == "[" && arrayHasDirectRouteObject(tokens, i)
        }
        if (candidates.isEmpty()) return emptyList()
        return candidates.filter { candidate ->
            candidates.none { other ->
                other != candidate &&
                    other < candidate &&
                    matchingIndex(tokens, other) > matchingIndex(tokens, candidate)
            }
        }
    }

    private fun assignedArrayStart(
        tokens: List<Token>,
        nameIndex: Int,
        localArrays: Map<String, Int>,
    ): Int? {
        val next = tokens.getOrNull(nameIndex + 1) ?: return null
        if (next.text == "=") {
            val value = tokens.getOrNull(nameIndex + 2) ?: return null
            if (value.text == "[") return nameIndex + 2
            return if (value.kind == TokenKind.IDENT) localArrays[value.text] else null
        }
        if (next.text != ":") return null

        val directIndex = nameIndex + 2
        val direct = tokens.getOrNull(directIndex) ?: return null
        if (direct.text == "[") {
            if (arrayHasDirectRouteObject(tokens, directIndex)) return directIndex

            // `const routes: RouteRecordRaw[] = [...]`: the first array belongs to the type.
            val close = matchingIndex(tokens, directIndex)
            if (tokens.getOrNull(close + 1)?.text == "=") {
                return if (tokens.getOrNull(close + 2)?.text == "[") close + 2 else null
            }
            return directIndex
        }

        // `routes: appRoutes` where `const appRoutes = [...]` lives in the same file.
        if (direct.kind == TokenKind.IDENT) {
            localArrays[direct.text]?.let { return it }
        }

        // `const routes: Array<RouteRecordRaw> = [...]`
        var i = directIndex
        var depth = 0
        val limit = minOf(tokens.size, directIndex + 200)
        while (i < limit) {
            when (tokens[i].text) {
                "=" -> {
                    if (depth == 0) {
                        val value = tokens.getOrNull(i + 1) ?: return null
                        if (value.text == "[") return i + 1
                        return if (value.kind == TokenKind.IDENT) localArrays[value.text] else null
                    }
                }

                "{", "[", "(" -> depth++
                "}", "]", ")" -> {
                    if (depth == 0) return null
                    depth--
                }

                ",", ";" -> if (depth == 0) return null
            }
            i++
        }
        return null
    }

    private fun arrayHasDirectRouteObject(tokens: List<Token>, openIndex: Int): Boolean {
        val close = matchingIndex(tokens, openIndex)
        var i = openIndex + 1
        while (i < close) {
            when (tokens[i].text) {
                "{" -> {
                    if (hasDirectProperty(tokens, i, "path")) return true
                    i = matchingIndex(tokens, i) + 1
                }

                "..." -> i = valueEnd(tokens, i + 1, close).coerceAtLeast(i + 1)
                else -> i++
            }
        }
        return false
    }

    private fun hasDirectProperty(tokens: List<Token>, openIndex: Int, property: String): Boolean {
        val close = matchingIndex(tokens, openIndex)
        var i = openIndex + 1
        while (i < close) {
            if (tokens[i].text == "...") {
                i = valueEnd(tokens, i + 1, close).coerceAtLeast(i + 1)
                continue
            }
            val key = keyOf(tokens[i])
            if (key == property && tokens.getOrNull(i + 1)?.text == ":") return true
            i = if (tokens.getOrNull(i + 1)?.text == ":") {
                valueEnd(tokens, i + 2, close).coerceAtLeast(i + 1)
            } else {
                i + 1
            }
        }
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------------

    private fun parseRouteArray(
        context: ParseContext,
        openIndex: Int,
        activeArrays: MutableSet<Int>,
    ): List<ParsedRoute> {
        if (!activeArrays.add(openIndex)) return emptyList()
        try {
            val tokens = context.tokens
            val close = matchingIndex(tokens, openIndex)
            val routes = ArrayList<ParsedRoute>()
            var i = openIndex + 1
            while (i < close) {
                val token = tokens[i]
                when (token.text) {
                    "{" -> {
                        val parsed = parseRouteObject(context, i, activeArrays)
                        if (parsed.first != null) routes.add(parsed.first!!)
                        i = parsed.second
                    }

                    "..." -> {
                        val spreadTarget = tokens.getOrNull(i + 1)
                        if (spreadTarget?.kind == TokenKind.IDENT) {
                            val array = context.localArrays[spreadTarget.text]
                            if (array != null && array != openIndex) {
                                routes.addAll(parseRouteArray(context, array, activeArrays))
                            } else {
                                context.externalArrays(spreadTarget.text)?.let(routes::addAll)
                            }
                        }
                        i = valueEnd(tokens, i + 1, close).coerceAtLeast(i + 1)
                    }

                    else -> i++
                }
            }
            return routes
        } finally {
            activeArrays.remove(openIndex)
        }
    }

    private fun parseRouteObject(
        context: ParseContext,
        openIndex: Int,
        activeArrays: MutableSet<Int>,
    ): Pair<ParsedRoute?, Int> {
        val tokens = context.tokens
        val close = matchingIndex(tokens, openIndex)

        var path: String? = null
        var name: String? = null
        var redirect: String? = null
        var alias: String? = null
        var metaTitle: String? = null
        var metaLayout: String? = null
        var component: ParsedComponent? = null
        var children: List<ParsedRoute> = emptyList()
        var pathToken: Token? = null

        var i = openIndex + 1
        while (i < close) {
            val token = tokens[i]
            if (token.text == ",") {
                i++
                continue
            }
            if (token.text == "...") {
                i = valueEnd(tokens, i + 1, close).coerceAtLeast(i + 1)
                continue
            }
            val key = keyOf(token)
            if (key == null || tokens.getOrNull(i + 1)?.text != ":") {
                i++
                continue
            }

            val valueStart = i + 2
            if (valueStart >= close) break
            val valueLimit = valueEnd(tokens, valueStart, close)

            when (key) {
                "path" -> {
                    pathToken = tokens[valueStart]
                    path = scalar(context, valueStart, valueLimit)
                }

                "name" -> name = scalar(context, valueStart, valueLimit)
                "redirect" -> redirect = scalar(context, valueStart, valueLimit)
                "alias" -> alias = parseAlias(context, valueStart, valueLimit)

                "component" -> component = parseComponent(context, valueStart, valueLimit)

                "components" -> {
                    if (component == null && tokens[valueStart].text == "{") {
                        component = parseDefaultComponent(context, valueStart)
                    }
                }

                "children" -> {
                    val arrayStart = when {
                        tokens[valueStart].text == "[" -> valueStart
                        valueLimit == valueStart + 1 && tokens[valueStart].kind == TokenKind.IDENT ->
                            context.localArrays[tokens[valueStart].text]
                        else -> null
                    }
                    if (arrayStart != null) {
                        children = parseRouteArray(context, arrayStart, activeArrays)
                    } else if (valueLimit == valueStart + 1 && tokens[valueStart].kind == TokenKind.IDENT) {
                        children = context.externalArrays(tokens[valueStart].text).orEmpty()
                    }
                }

                "meta" -> {
                    if (tokens[valueStart].text == "{") {
                        val meta = parseMeta(context, valueStart)
                        metaTitle = meta.first
                        metaLayout = meta.second
                    }
                }
            }

            i = if (valueLimit > i) valueLimit else i + 1
        }

        val hasRouteData = pathToken != null ||
            name != null ||
            component != null ||
            redirect != null ||
            alias != null ||
            metaTitle != null ||
            children.isNotEmpty()
        if (!hasRouteData) return null to (close + 1)

        val anchor = pathToken ?: tokens[openIndex]
        val route = ParsedRoute(
            path = path.orEmpty(),
            name = name?.takeIf { it.isNotBlank() },
            component = component,
            redirect = redirect?.takeIf { it.isNotBlank() },
            alias = alias?.takeIf { it.isNotBlank() },
            metaTitle = metaTitle?.takeIf { it.isNotBlank() },
            line = anchor.line,
            column = anchor.column,
            children = children,
            metaLayout = metaLayout?.takeIf { it.isNotBlank() },
        )
        return route to (close + 1)
    }

    private fun parseComponent(context: ParseContext, start: Int, end: Int): ParsedComponent? {
        if (start >= end) return null
        val tokens = context.tokens
        val raw = context.source.substring(tokens[start].start, tokens[end - 1].end).trim()
        if (raw.isEmpty()) return null

        val importMatch = dynamicImportRegex.find(raw) ?: requireRegex.find(raw)
        val importPath = importMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: globIndexRegex.find(raw)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

        val simpleIdentifier = if (end - start == 1) {
            tokens[start].takeIf { it.kind == TokenKind.IDENT }?.text
                ?.takeIf { it !in nonComponentKeywords }
        } else {
            null
        }

        return ParsedComponent(
            identifier = simpleIdentifier,
            importPath = importPath,
            expression = compact(raw),
        )
    }

    private fun parseAlias(context: ParseContext, start: Int, end: Int): String? {
        if (start >= end) return null
        val tokens = context.tokens
        if (tokens[start].text != "[") return scalar(context, start, end)

        val close = matchingIndex(tokens, start)
        return tokens.subList(start + 1, close)
            .asSequence()
            .filter { it.kind == TokenKind.STRING || it.kind == TokenKind.TEMPLATE }
            .mapNotNull { it.value }
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
    }

    private fun parseDefaultComponent(context: ParseContext, openIndex: Int): ParsedComponent? {
        val tokens = context.tokens
        val close = matchingIndex(tokens, openIndex)
        var i = openIndex + 1
        while (i < close) {
            if (tokens[i].text == "...") {
                i = valueEnd(tokens, i + 1, close).coerceAtLeast(i + 1)
                continue
            }
            val key = keyOf(tokens[i])
            if (key == "default" && tokens.getOrNull(i + 1)?.text == ":") {
                val valueStart = i + 2
                if (valueStart >= close) return null
                return parseComponent(context, valueStart, valueEnd(tokens, valueStart, close))
            }
            i = if (tokens.getOrNull(i + 1)?.text == ":") {
                valueEnd(tokens, i + 2, close).coerceAtLeast(i + 1)
            } else {
                i + 1
            }
        }
        return null
    }

    private fun parseMeta(context: ParseContext, openIndex: Int): Pair<String?, String?> {
        val tokens = context.tokens
        val close = matchingIndex(tokens, openIndex)
        var title: String? = null
        var layout: String? = null
        var i = openIndex + 1
        while (i < close) {
            if (tokens[i].text == "...") {
                i = valueEnd(tokens, i + 1, close).coerceAtLeast(i + 1)
                continue
            }
            val key = keyOf(tokens[i])
            if ((key == "title" || key == "layout") && tokens.getOrNull(i + 1)?.text == ":") {
                val valueStart = i + 2
                if (valueStart < close) {
                    val value = scalar(context, valueStart, valueEnd(tokens, valueStart, close))
                        ?.takeIf { it.isNotBlank() }
                    if (key == "title") title = value else layout = value
                }
            }
            i = if (tokens.getOrNull(i + 1)?.text == ":") {
                valueEnd(tokens, i + 2, close).coerceAtLeast(i + 1)
            } else {
                i + 1
            }
        }
        return title to layout
    }

    // ---------------------------------------------------------------------------------------------
    // Token helpers
    // ---------------------------------------------------------------------------------------------

    private fun keyOf(token: Token): String? = when (token.kind) {
        TokenKind.IDENT -> token.text
        TokenKind.STRING -> token.value
        else -> null
    }

    private fun scalar(context: ParseContext, start: Int, end: Int): String? {
        if (start >= end) return null
        val token = context.tokens[start]
        if (token.kind == TokenKind.STRING || token.kind == TokenKind.TEMPLATE) {
            return token.value ?: ""
        }
        if (end == start + 1 && token.kind == TokenKind.IDENT) {
            return token.text
        }
        val raw = context.source.substring(
            context.tokens[start].start,
            context.tokens[end - 1].end,
        )
        return compact(raw)
    }

    private fun compact(value: String): String = value.replace(whitespaceRegex, " ").trim()

    private fun matchingIndex(tokens: List<Token>, openIndex: Int): Int {
        val open = tokens.getOrNull(openIndex)?.text ?: return tokens.size - 1
        val close = when (open) {
            "{" -> "}"
            "[" -> "]"
            "(" -> ")"
            else -> return openIndex
        }
        var depth = 0
        for (i in openIndex until tokens.size) {
            when (tokens[i].text) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return tokens.size - 1
    }

    /**
     * Returns the index of the first top-level comma or closing bracket of the containing object,
     * i.e. the exclusive end of the current value expression.
     */
    private fun valueEnd(tokens: List<Token>, start: Int, limit: Int): Int {
        var i = start
        var depth = 0
        while (i < limit) {
            when (tokens[i].text) {
                "{", "[", "(" -> depth++
                "}", "]", ")" -> {
                    if (depth == 0) return i
                    depth--
                }

                "," -> if (depth == 0) return i
            }
            i++
        }
        return limit
    }
}
