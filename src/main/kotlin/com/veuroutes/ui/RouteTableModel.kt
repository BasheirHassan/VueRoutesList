package com.veuroutes.ui

import com.veuroutes.model.VueRoute
import javax.swing.table.AbstractTableModel

class RouteTableModel : AbstractTableModel() {

    private val routes = ArrayList<VueRoute>()

    fun setRoutes(newRoutes: List<VueRoute>) {
        if (routes == newRoutes) return
        routes.clear()
        routes.addAll(newRoutes)
        fireTableDataChanged()
    }

    fun routeAt(modelRow: Int): VueRoute = routes[modelRow]

    fun routes(): List<VueRoute> = routes

    override fun getRowCount(): Int = routes.size

    override fun getColumnCount(): Int = 4

    override fun getColumnName(column: Int): String = when (column) {
        0 -> "Path"
        1 -> "Name"
        2 -> "Component"
        3 -> "Source"
        else -> ""
    }

    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val route = routes[rowIndex]
        return when (columnIndex) {
            0 -> route.path
            1 -> route.name ?: "—"
            2 -> route.componentLabel.ifBlank { "—" }
            3 -> "${route.sourceDisplay}:${route.sourceLine}"
            else -> ""
        }
    }
}
