package com.veuroutes.ui

import com.intellij.util.ui.UIUtil
import java.awt.Component
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer

class RouteCellRenderer(private val model: RouteTableModel) : DefaultTableCellRenderer() {

    private val inactiveColor: java.awt.Color = UIUtil.getInactiveTextColor()

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        val component = super.getTableCellRendererComponent(
            table,
            value,
            isSelected,
            hasFocus,
            row,
            column,
        ) as JLabel

        val modelRow = table.convertRowIndexToModel(row)
        val modelColumn = table.convertColumnIndexToModel(column)
        val route = model.routeAt(modelRow)

        component.text = when (modelColumn) {
            0 -> route.path
            1 -> route.name ?: "—"
            2 -> route.componentLabel.ifBlank { "—" }
            3 -> "${route.sourceDisplay}:${route.sourceLine}"
            else -> ""
        }
        component.toolTipText = route.tooltipHtml()
        component.icon = null

        if (!isSelected) {
            component.foreground = when {
                modelColumn == 3 -> inactiveColor
                modelColumn == 2 && route.isRedirectOnly -> inactiveColor
                modelColumn == 1 && route.name == null -> inactiveColor
                else -> UIUtil.getLabelForeground()
            }
            component.font = table.font
        }

        return component
    }
}
