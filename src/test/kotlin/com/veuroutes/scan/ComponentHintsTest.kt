package com.veuroutes.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComponentHintsTest {

    @Test
    fun `extracts string literals from helper calls`() {
        assertEquals(
            listOf("Accounts/Index"),
            ComponentHints.literals("pageComponent('Accounts/Index')"),
        )
        assertEquals(
            listOf("@/views/Users/Create.vue"),
            ComponentHints.literals("()=>import('@/views/Users/Create.vue')"),
        )
        assertTrue(ComponentHints.literals("RouterPassthrough").isEmpty())
    }

    @Test
    fun `builds page and view suffixes for nested helper arguments`() {
        val candidates = ComponentHints.suffixCandidates("Accounts/Index")

        assertEquals("Accounts/Index.vue", candidates.first())
        assertTrue("Pages/Accounts/Index.vue" in candidates)
        assertTrue("Views/Accounts/Index.vue" in candidates)
    }

    @Test
    fun `prefers conventional folders for bare names`() {
        val candidates = ComponentHints.suffixCandidates("Dashboard")

        assertEquals("Pages/Dashboard.vue", candidates.first())
        assertTrue("Dashboard.vue" in candidates)
    }

    @Test
    fun `ignores non component literals`() {
        assertTrue(ComponentHints.literals("'vue'").isEmpty())
        assertTrue(ComponentHints.literals("'vue-router'").isEmpty())
        assertFalse(ComponentHints.literals("'Layout'").isEmpty())
    }

    @Test
    fun `strips extensions and quotes from candidates`() {
        val candidates = ComponentHints.suffixCandidates("Pages/Accounts/Index.vue")

        assertEquals("Pages/Accounts/Index.vue", candidates.first())
        assertFalse(candidates.any { it.endsWith(".vue.vue") })
    }

    @Test
    fun `normalizes alias and relative fallbacks`() {
        assertTrue("views/Users/Create.vue" in ComponentHints.suffixCandidates("@/views/Users/Create.vue"))
        assertTrue("Pages/Accounts/Index.vue" in ComponentHints.suffixCandidates("../../Pages/Accounts/Index.vue"))
    }
}
