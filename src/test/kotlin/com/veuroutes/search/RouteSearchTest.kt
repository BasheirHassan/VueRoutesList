package com.veuroutes.search

import com.veuroutes.model.SearchableRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RouteSearchTest {

    private data class FakeRoute(
        override val path: String,
        override val name: String? = null,
        override val componentLabel: String = "—",
        override val componentRaw: String? = null,
        override val redirect: String? = null,
        override val alias: String? = null,
        override val metaTitle: String? = null,
        override val sourceDisplay: String = "src/router/index.ts",
    ) : SearchableRoute

    private val routes = listOf(
        FakeRoute("/dashboard", "dashboard", "src/views/DashboardView.vue"),
        FakeRoute("/dashboard/employees", "dashboard-employees", "src/views/dashboard/EmployeesView.vue"),
        FakeRoute(
            "/dashboard/employees/create",
            "dashboard-employees-create",
            "src/views/dashboard/EmployeeCreate.vue",
        ),
        FakeRoute(
            "/dashboard/employees/:id",
            "dashboard-employee-details",
            "src/views/dashboard/EmployeeDetails.vue",
        ),
        FakeRoute(
            "/dashboard/employees/:id/edit",
            "dashboard-employee-edit",
            "src/views/dashboard/EmployeeEdit.vue",
        ),
        FakeRoute("/dashboard/:section", "dashboard-section", "src/views/dashboard/SectionView.vue"),
        FakeRoute("/login", "login", alias = "/signin"),
    )

    private fun paths(query: String): List<String> =
        RouteSearch.filter(query, routes).map { it.path }

    @Test
    fun `exact path is ranked before its children and dynamic siblings`() {
        val result = paths("/dashboard/employees")

        assertEquals("/dashboard/employees", result.first())
        assertTrue("/dashboard/employees/create" in result)
        assertTrue("/dashboard/employees/:id" in result)
        assertTrue(result.indexOf("/dashboard/employees/create") < result.indexOf("/dashboard/:section"))
    }

    @Test
    fun `trailing slash and casing are ignored`() {
        assertEquals("/dashboard/employees", paths("/DASHBOARD/Employees/").first())
    }

    @Test
    fun `full url ignores origin query string and fragment`() {
        assertEquals(
            "/dashboard/employees/create",
            paths("https://app.example.com/dashboard/employees/create?tab=users#form").first(),
        )
    }

    @Test
    fun `hash mode url and numeric parameters are supported`() {
        assertEquals(
            "/dashboard/employees/:id",
            paths("http://localhost:5173/#/dashboard/employees/42").first(),
        )
    }

    @Test
    fun `quoted path property is understood`() {
        assertEquals(
            "/dashboard/employees/create",
            paths("path: '/dashboard/employees/create'").first(),
        )
    }

    @Test
    fun `concrete segments match route parameters`() {
        assertEquals("/dashboard/employees/:id", paths("/dashboard/employees/42").first())
        assertEquals("/dashboard/employees/:id/edit", paths("/dashboard/employees/42/edit").first())
    }

    @Test
    fun `a path fragment in the middle of the route is matched`() {
        val result = paths("/employees")

        assertEquals("/dashboard/employees", result.first())
        assertTrue("/dashboard/employees/create" in result)
    }

    @Test
    fun `multiple words must all match`() {
        val result = paths("employee create")

        assertEquals("/dashboard/employees/create", result.first())
        assertEquals(1, result.size)
    }

    @Test
    fun `names and component files are searchable`() {
        assertEquals(
            "/dashboard/employees/create",
            paths("dashboard-employees-create").first(),
        )
        assertEquals(
            "/dashboard/employees/create",
            paths("EmployeeCreate").first(),
        )
    }

    @Test
    fun `aliases are matched like paths`() {
        assertEquals("/login", paths("/signin").first())
    }

    @Test
    fun `unknown text returns no routes`() {
        assertTrue(paths("zzzz-not-a-route").isEmpty())
    }

    @Test
    fun `root query keeps every route`() {
        assertEquals(routes.size, paths("/").size)
    }

    @Test
    fun `root query lists catch all last`() {
        val withFallback = routes + FakeRoute("/:pathMatch(.*)*", "not-found")

        val result = RouteSearch.filter("/", withFallback).map { it.path }

        assertEquals(withFallback.size, result.size)
        assertEquals("/:pathMatch(.*)*", result.last())
    }

    @Test
    fun `optional parameters and catch all segments are supported`() {
        val optional = listOf(FakeRoute("/users"), FakeRoute("/users/:id?"))
        assertEquals("/users", RouteSearch.filter("/users", optional).first().path)

        val files = listOf(FakeRoute("/files"), FakeRoute("/files/:pathMatch(.*)*"))
        assertEquals(
            "/files/:pathMatch(.*)*",
            RouteSearch.filter("/files/a/b.txt", files).first().path,
        )
    }

    @Test
    fun `catch all route does not outrank specific routes`() {
        val withFallback = routes + FakeRoute("/:pathMatch(.*)*", "not-found")

        val result = RouteSearch.filter("/dashboard/employees/create", withFallback)

        assertEquals("/dashboard/employees/create", result.first().path)
        assertEquals("/:pathMatch(.*)*", result.last().path)
    }

    @Test
    fun `custom parameter patterns win over generic parameters`() {
        val orders = listOf(FakeRoute("/orders/:slug"), FakeRoute("/orders/:id(\\d+)"))
        assertEquals(
            "/orders/:id(\\d+)",
            RouteSearch.filter("/orders/42", orders).first().path,
        )
    }
}
