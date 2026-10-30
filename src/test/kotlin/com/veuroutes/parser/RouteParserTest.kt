package com.veuroutes.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RouteParserTest {

    @Test
    fun `parses a typical vue router 4 configuration`() {
        val source = """
            import { createRouter, createWebHistory } from 'vue-router'
            import HomeView from '@/views/HomeView.vue'

            const routes: RouteRecordRaw[] = [
              {
                path: '/',
                name: 'home',
                component: HomeView,
              },
              {
                path: '/about',
                name: 'about',
                component: () => import(/* webpackChunkName: "about" */ '../views/AboutView.vue'),
                meta: { title: 'About us' },
              },
              {
                path: '/login',
                name: 'login',
                redirect: '/auth',
                alias: '/signin',
              },
            ]

            const router = createRouter({
              history: createWebHistory(import.meta.env.BASE_URL),
              routes,
            })

            export default router
        """.trimIndent()

        val routes = RouteParser.parse(source)

        assertEquals(3, routes.size)

        assertEquals("/", routes[0].path)
        assertEquals("home", routes[0].name)
        assertEquals("HomeView", routes[0].component?.identifier)
        assertNull(routes[0].component?.importPath)

        assertEquals("/about", routes[1].path)
        assertEquals("about", routes[1].name)
        assertEquals("../views/AboutView.vue", routes[1].component?.importPath)
        assertEquals("About us", routes[1].metaTitle)

        assertEquals("/login", routes[2].path)
        assertEquals("/auth", routes[2].redirect)
        assertEquals("/signin", routes[2].alias)
        assertNull(routes[2].component)

        assertTrue(routes.all { it.line > 0 && it.column > 0 })
    }

    @Test
    fun `keeps nested children nested with their own paths`() {
        val source = """
            const routes = [
              {
                path: '/admin',
                component: AdminLayout,
                children: [
                  {
                    path: '',
                    name: 'admin-home',
                    component: () => import('@/views/admin/Home.vue'),
                  },
                  {
                    path: 'users/:id',
                    name: 'admin-user',
                    component: UserDetail,
                  },
                  {
                    path: '/login',
                    redirect: '/auth',
                  },
                ],
              },
            ]
        """.trimIndent()

        val routes = RouteParser.parse(source)

        assertEquals(1, routes.size)
        val admin = routes.single()
        assertEquals("/admin", admin.path)
        assertEquals(3, admin.children.size)
        assertEquals("", admin.children[0].path)
        assertEquals("admin-home", admin.children[0].name)
        assertEquals("@/views/admin/Home.vue", admin.children[0].component?.importPath)
        assertEquals("users/:id", admin.children[1].path)
        assertEquals("UserDetail", admin.children[1].component?.identifier)
        assertEquals("/login", admin.children[2].path)
        assertEquals("/auth", admin.children[2].redirect)
    }

    @Test
    fun `expands local spread arrays`() {
        val source = """
            const publicRoutes = [
              { path: '/login', name: 'login', component: Login },
            ]

            const routes = [
              ...publicRoutes,
              { path: '/', name: 'home', component: Home },
            ]
        """.trimIndent()

        val routes = RouteParser.parse(source)

        assertEquals(listOf("/login", "/"), routes.map { it.path })
        assertEquals(listOf("login", "home"), routes.map { it.name })
    }

    @Test
    fun `follows a routes identifier passed to createRouter`() {
        val source = """
            const publicRoutes = [
              { path: '/login', name: 'login', component: Login },
            ]

            const appRoutes = [
              ...publicRoutes,
              { path: '/', name: 'home', component: Home },
            ]

            export default createRouter({ history: createWebHistory(), routes: appRoutes })
        """.trimIndent()

        val routes = RouteParser.parse(source)

        assertEquals(listOf("/login", "/"), routes.map { it.path })
        assertEquals(listOf("login", "home"), routes.map { it.name })
    }

    @Test
    fun `expands a children array stored in a variable`() {
        val source = """
            const childRoutes = [
              { path: 'settings', name: 'settings', component: Settings },
              { path: 'profile', name: 'profile', component: Profile },
            ]

            const routes = [
              { path: '/account', component: AccountLayout, children: childRoutes },
            ]
        """.trimIndent()

        val admin = RouteParser.parse(source).single()
        assertEquals("/account", admin.path)
        assertEquals(2, admin.children.size)
        assertEquals(listOf("settings", "profile"), admin.children.map { it.path })
    }

    @Test
    fun `ignores comments and strings that look like routes`() {
        val source = """
            const note = "{ path: '/fake', component: Fake }"
            const routes = [
              // { path: '/also-fake', component: Fake },
              /* { path: '/block-fake', component: Fake }, */
              { path: '/real', name: 'real', component: () => import('@/views/Real.vue') },
            ]
        """.trimIndent()

        val routes = RouteParser.parse(source)

        assertEquals(1, routes.size)
        assertEquals("/real", routes.single().path)
        assertEquals("@/views/Real.vue", routes.single().component?.importPath)
    }

    @Test
    fun `parses vue router 3 style configuration`() {
        val source = """
            import VueRouter from 'vue-router'

            export default new VueRouter({
              mode: 'history',
              routes: [
                { path: '/', component: Home },
                { path: '/about', component: About },
              ],
            })
        """.trimIndent()

        val routes = RouteParser.parse(source)

        assertEquals(listOf("/", "/about"), routes.map { it.path })
        assertEquals(listOf("Home", "About"), routes.map { it.component?.identifier })
    }

    @Test
    fun `reads default component from components object`() {
        val source = """
            const routes = [
              {
                path: '/dashboard',
                components: {
                  default: () => import('@/views/Dashboard.vue'),
                  sidebar: Sidebar,
                },
              },
            ]
        """.trimIndent()

        val routes = RouteParser.parse(source)

        assertEquals(1, routes.size)
        assertEquals("@/views/Dashboard.vue", routes.single().component?.importPath)
    }

    @Test
    fun `supports a bare exported route array`() {
        val source = """
            export default [
              { path: '/', component: () => import('@/views/Home.vue') },
              { path: '/404', name: 'not-found', component: NotFound },
            ]
        """.trimIndent()

        val routes = RouteParser.parse(source)

        assertEquals(listOf("/", "/404"), routes.map { it.path })
        assertEquals("@/views/Home.vue", routes[0].component?.importPath)
        assertEquals("NotFound", routes[1].component?.identifier)
    }

    @Test
    fun `does not parse unrelated objects`() {
        val source = """
            const config = [
              { path: '/tmp', component: TempWidget },
            ]
        """.trimIndent()

        assertTrue(RouteParser.parse(source).isEmpty())
    }

    @Test
    fun `keeps metadata and route fields after trailing commas`() {
        val source = """
            const routes = [
              {
                path: '/settings',
                name: 'settings',
                component: SettingsView,
                alias: ['/preferences', '/options'],
                meta: {
                  requiresAuth: true,
                  title: 'Account settings',
                },
              },
            ]
        """.trimIndent()

        val routes = RouteParser.parse(source)

        assertEquals(1, routes.size)
        val route = routes.single()
        assertEquals("settings", route.name)
        assertEquals("SettingsView", route.component?.identifier)
        assertEquals("/preferences", route.alias?.substringBefore(","))
        assertEquals("Account settings", route.metaTitle)
    }

    @Test
    fun `parses lazy imports with a leading comment`() {
        val source = """
            const routes = [
              {
                path: '/reports',
                component: () => import(/* webpackChunkName: "reports" */ '@/views/Reports.vue'),
              },
            ]
        """.trimIndent()

        val route = RouteParser.parse(source).single()
        assertEquals("@/views/Reports.vue", route.component?.importPath)
    }

    @Test
    fun `uses the path token position for navigation`() {
        val source = """
            const routes = [
              {
                name: 'line-check',
                path: '/line-check',
                component: Check,
              },
            ]
        """.trimIndent()

        val route = RouteParser.parse(source).single()
        assertNotNull(route.component)
        assertEquals(4, route.line)
    }
}
