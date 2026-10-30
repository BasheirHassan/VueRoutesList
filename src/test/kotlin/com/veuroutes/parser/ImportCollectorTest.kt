package com.veuroutes.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImportCollectorTest {

    @Test
    fun `collects default, named, alias, and lazy imports`() {
        val source = """
            import HomeView from '@/views/HomeView.vue'
            import { default as AdminLayout, Sidebar as SideBar } from '@/layouts'
            import { Foo } from '@/foo'
            import type { RouteRecordRaw } from 'vue-router'

            const LazyView = () => import('@/views/LazyView.vue')
            const AsyncView = defineAsyncComponent(() => import(/* chunk */ '@/views/AsyncView.vue'))
            const RequiredView = require('../views/RequiredView.vue')
        """.trimIndent()

        val imports = ImportCollector.collect(source)

        assertEquals("@/views/HomeView.vue", imports["HomeView"])
        assertEquals("@/layouts", imports["AdminLayout"])
        assertEquals("@/layouts", imports["SideBar"])
        assertEquals("@/foo", imports["Foo"])
        assertEquals("@/views/LazyView.vue", imports["LazyView"])
        assertEquals("@/views/AsyncView.vue", imports["AsyncView"])
        assertEquals("../views/RequiredView.vue", imports["RequiredView"])
    }
}
