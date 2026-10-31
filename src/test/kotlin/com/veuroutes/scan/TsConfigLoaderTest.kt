package com.veuroutes.scan

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class TsConfigLoaderTest {

    private lateinit var root: File

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("veuroutes-tsconfig").toFile()
    }

    @AfterEach
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `reads baseUrl and paths from a config file`() {
        write(
            "tsconfig.json",
            """
                {
                  "compilerOptions": {
                    "baseUrl": ".",
                    "paths": {
                      "@/*": ["src/*"],
                      "@shared/*": ["shared/*"]
                    }
                  }
                }
            """.trimIndent(),
        )

        val config = TsConfigLoader.load(root).single()
        assertEquals(path("."), config.baseUrlDir.invariantSeparatorsPath)
        assertEquals(2, config.rules.size)

        val app = config.rules.single { it.prefix == "@/" }
        assertEquals(path("src") + "/*", app.targetTemplate)
        assertTrue(app.wildcard)

        val shared = config.rules.single { it.prefix == "@shared/" }
        assertEquals(path("shared") + "/*", shared.targetTemplate)
    }

    @Test
    fun `ignores unrelated json files and reads all config variants`() {
        write("package.json", """{ "name": "app" }""")
        write("vite.json", """{ "compilerOptions": { "paths": { "@bad/*": ["bad/*"] } } }""")
        write("tsconfig.node.json", """{ "compilerOptions": { "paths": { "@node/*": ["node/*"] } } }""")
        write("tsconfig.web.json", """{ "compilerOptions": { "paths": { "@web/*": ["web/*"] } } }""")

        val configs = TsConfigLoader.load(root)
        assertEquals(2, configs.size)
        val prefixes = configs.flatMap { config -> config.rules.map { it.prefix } }.toSet()
        assertEquals(setOf("@node/", "@web/"), prefixes)
    }

    @Test
    fun `follows a local extends chain`() {
        write(
            "tsconfig.base.json",
            """
                {
                  "compilerOptions": {
                    "baseUrl": ".",
                    "paths": { "@/*": ["src/*"] }
                  }
                }
            """.trimIndent(),
        )
        write(
            "tsconfig.json",
            """
                {
                  "extends": "./tsconfig.base",
                  "compilerOptions": {
                    "paths": { "@shared/*": ["shared/*"] }
                  }
                }
            """.trimIndent(),
        )

        val child = TsConfigLoader.load(root).single { it.rules.size == 2 }
        val byPrefix = child.rules.associateBy { it.prefix }

        assertEquals(path("src") + "/*", byPrefix.getValue("@/").targetTemplate)
        assertEquals(path("shared") + "/*", byPrefix.getValue("@shared/").targetTemplate)
        assertEquals(path("."), child.baseUrlDir.invariantSeparatorsPath)
    }

    @Test
    fun `tolerates a package based extends`() {
        write(
            "tsconfig.web.json",
            """
                {
                  "extends": "@vue/tsconfig/tsconfig.dom.json",
                  "compilerOptions": {
                    "baseUrl": ".",
                    "paths": { "@/*": ["src/*"] }
                  }
                }
            """.trimIndent(),
        )

        val config = TsConfigLoader.load(root).single()
        assertEquals(path("src") + "/*", config.rules.single().targetTemplate)
    }

    @Test
    fun `recognises config file names`() {
        assertTrue(TsConfigLoader.isConfigFileName("tsconfig.json"))
        assertTrue(TsConfigLoader.isConfigFileName("tsconfig.app.json"))
        assertTrue(TsConfigLoader.isConfigFileName("tsconfig.web.json"))
        assertTrue(TsConfigLoader.isConfigFileName("jsconfig.json"))
        assertTrue(TsConfigLoader.isConfigFileName("TSConfig.JSON"))
        assertFalse(TsConfigLoader.isConfigFileName("package.json"))
        assertFalse(TsConfigLoader.isConfigFileName("tsconfig.json.bak"))
    }

    private fun write(name: String, content: String) {
        File(root, name).writeText(content)
    }

    private fun path(relative: String): String =
        File(root, relative).normalize().path.replace('\\', '/')
}
