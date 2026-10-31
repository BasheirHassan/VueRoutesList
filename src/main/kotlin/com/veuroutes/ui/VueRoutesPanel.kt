package com.veuroutes.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.veuroutes.model.VueRoute
import com.veuroutes.search.RouteSearch
import com.veuroutes.service.VueRoutesService
import com.veuroutes.settings.VueRoutesConfigurable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.datatransfer.StringSelection
import javax.swing.AbstractAction
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.TableRowSorter

class VueRoutesPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val service = project.getService(VueRoutesService::class.java)
    private val tableModel = RouteTableModel()
    private val sorter = TableRowSorter(tableModel)
    private val table = JBTable(tableModel)
    private val searchField = SearchTextField()
    private val statusLabel = JBLabel()
    private val hintLabel = JBLabel(
        "Paste /dashboard/employees or a full URL · Enter opens · Ctrl+F focuses search",
        SwingConstants.LEFT,
    )

    private val refreshAction = RefreshAction()
    private val openRouteAction = OpenRouteAction()
    private val openComponentAction = OpenComponentAction()
    private val openParentComponentAction = OpenParentComponentAction()
    private val openBaseComponentAction = OpenBaseComponentAction()
    private val copyPathAction = CopyPathAction()
    private val settingsAction = SettingsAction()

    private lateinit var toolbar: ActionToolbar
    private var listenerDisposable: Disposable? = null
    private var lastSnapshot: VueRoutesService.Snapshot? = null
    private var allRoutes: List<VueRoute> = emptyList()
    private var loading = true

    init {
        setupTable()
        setupHeader()
        setupFooter()
        setupKeyboardShortcuts()
        setupPopupMenu()

        listenerDisposable = service.addListener(
            object : VueRoutesService.Listener {
                override fun onScanStarted() {
                    setLoading(true)
                }

                override fun onScanFinished(snapshot: VueRoutesService.Snapshot) {
                    setLoading(false)
                    applySnapshot(snapshot)
                }
            },
        )
        service.ensureLoaded()
    }

    private fun setupTable() {
        table.autoCreateRowSorter = false
        table.rowSorter = sorter
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.setShowGrid(false)
        table.setShowVerticalLines(false)
        table.setShowHorizontalLines(false)
        table.intercellSpacing = Dimension(0, 0)
        table.rowHeight = 26
        table.fillsViewportHeight = true
        table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        table.tableHeader.reorderingAllowed = false
        table.emptyText.text = "Loading Vue Router routes…"

        val width = intArrayOf(260, 150, 340, 210)
        val minimum = intArrayOf(160, 90, 160, 140)
        for (column in 0 until tableModel.columnCount) {
            table.columnModel.getColumn(column).apply {
                preferredWidth = width[column]
                minWidth = minimum[column]
            }
        }

        val renderer = RouteCellRenderer(tableModel)
        for (column in 0 until tableModel.columnCount) {
            table.columnModel.getColumn(column).cellRenderer = renderer
        }

        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting && ::toolbar.isInitialized) {
                toolbar.updateActionsImmediately()
            }
        }

        table.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    selectRowForPopup(event)
                }

                override fun mouseReleased(event: MouseEvent) {
                    selectRowForPopup(event)
                }

                override fun mouseClicked(event: MouseEvent) {
                    if (event.clickCount != 2 || !SwingUtilities.isLeftMouseButton(event)) return
                    val viewRow = table.rowAtPoint(event.point)
                    if (viewRow < 0) return
                    table.setRowSelectionInterval(viewRow, viewRow)
                    val route = selectedRoute() ?: return
                    val viewColumn = table.columnAtPoint(event.point)
                    val modelColumn = if (viewColumn >= 0) {
                        table.convertColumnIndexToModel(viewColumn)
                    } else {
                        0
                    }
                    if (modelColumn == 2 && route.componentFile != null) {
                        openComponent(route)
                    } else {
                        openRoute(route)
                    }
                }

                private fun selectRowForPopup(event: MouseEvent) {
                    if (!event.isPopupTrigger) return
                    val viewRow = table.rowAtPoint(event.point)
                    if (viewRow < 0 || table.isRowSelected(viewRow)) return
                    table.setRowSelectionInterval(viewRow, viewRow)
                }
            },
        )

        table.addKeyListener(
            object : KeyAdapter() {
                override fun keyTyped(event: KeyEvent) {
                    if (event.isControlDown || event.isAltDown || event.isMetaDown) return
                    val typed = event.keyChar
                    if (typed.isLetterOrDigit() || typed in "/-_:.@~?&=#") {
                        searchField.requestFocusInWindow()
                        searchField.text = searchField.text + typed
                        searchField.textEditor.caretPosition = searchField.text.length
                        event.consume()
                    }
                }
            },
        )

        val scrollPane = JBScrollPane(table)
        scrollPane.border = JBUI.Borders.empty()
        add(scrollPane, BorderLayout.CENTER)
    }

    private fun setupHeader() {
        val toolbarGroup = DefaultActionGroup(refreshAction, openComponentAction, settingsAction)
        toolbar = ActionManager.getInstance().createActionToolbar("VueRoutesToolbar", toolbarGroup, true)
        toolbar.targetComponent = this

        statusLabel.border = JBUI.Borders.emptyRight(4)

        val topRow = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 4, 0, 8)
            add(toolbar.component, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        searchField.textEditor.emptyText.text =
            "Search — paste /dashboard/employees, a URL, a name, or a component…"
        searchField.toolTipText = """
            <html>
            <b>Smart path search</b><br/>
            /dashboard/employees — exact route first, then its children<br/>
            /dashboard/employees/42 — matches /dashboard/employees/:id<br/>
            https://app.com/#/dashboard/employees — URLs and hash mode<br/>
            path: '/dashboard/employees' — quotes and path: prefix are ignored<br/>
            dashboard create — every word must match<br/>
            </html>
        """.trimIndent()
        searchField.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(event: DocumentEvent) = updateFilter()

                override fun removeUpdate(event: DocumentEvent) = updateFilter()

                override fun changedUpdate(event: DocumentEvent) = updateFilter()
            },
        )

        val searchRow = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8)
            add(searchField, BorderLayout.CENTER)
        }

        val header = JPanel(BorderLayout()).apply {
            add(topRow, BorderLayout.NORTH)
            add(searchRow, BorderLayout.CENTER)
        }
        add(header, BorderLayout.NORTH)
    }

    private fun setupFooter() {
        hintLabel.foreground = UIUtil.getInactiveTextColor()
        hintLabel.border = JBUI.Borders.empty(4, 8, 6, 8)
        add(hintLabel, BorderLayout.SOUTH)
    }

    private fun setupKeyboardShortcuts() {
        val focusMap = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val focusAction = object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                searchField.requestFocusInWindow()
                searchField.selectText()
            }
        }
        focusMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "focusVueRoutesSearch")
        focusMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.META_DOWN_MASK), "focusVueRoutesSearch")
        actionMap.put("focusVueRoutesSearch", focusAction)

        val clearSearch = object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                if (searchField.text.isNotEmpty()) {
                    searchField.text = ""
                } else {
                    table.clearSelection()
                }
            }
        }
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearVueRoutesSearch")
        table.actionMap.put("clearVueRoutesSearch", clearSearch)

        searchField.textEditor.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            "clearVueRoutesSearch",
        )
        searchField.textEditor.actionMap.put("clearVueRoutesSearch", clearSearch)

        val openFirstResult = object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                if (tableModel.rowCount == 0) return
                if (table.selectedRow < 0) table.setRowSelectionInterval(0, 0)
                selectedRoute()?.let { openRoute(it) }
            }
        }
        searchField.textEditor.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            "openFirstVueRoute",
        )
        searchField.textEditor.actionMap.put("openFirstVueRoute", openFirstResult)

        val focusResults = object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                if (tableModel.rowCount == 0) return
                if (table.selectedRow < 0) table.setRowSelectionInterval(0, 0)
                table.requestFocusInWindow()
            }
        }
        searchField.textEditor.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
            "focusVueRoutesResults",
        )
        searchField.textEditor.actionMap.put("focusVueRoutesResults", focusResults)

        val openRoute = object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                selectedRoute()?.let { openRoute(it) }
            }
        }
        table.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openVueRoute")
        table.actionMap.put("openVueRoute", openRoute)

        val openComponentShortcut = object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                selectedRoute()?.let { route ->
                    if (route.componentFile != null) openComponent(route)
                }
            }
        }
        table.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            "openVueRouteComponent",
        )
        table.actionMap.put("openVueRouteComponent", openComponentShortcut)
    }

    private fun setupPopupMenu() {
        val popupGroup = DefaultActionGroup(
            openRouteAction,
            openComponentAction,
            openParentComponentAction,
            openBaseComponentAction,
            copyPathAction,
        )
        val popup = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.TOOLWINDOW_POPUP, popupGroup)
        table.componentPopupMenu = popup.component
    }

    private fun setLoading(loading: Boolean) {
        this.loading = loading
        table.setPaintBusy(loading)
        if (loading) {
            statusLabel.foreground = UIUtil.getLabelForeground()
            statusLabel.text = "Scanning…"
        }
        updateFilter()
        toolbar.updateActionsImmediately()
    }

    private fun applySnapshot(snapshot: VueRoutesService.Snapshot) {
        lastSnapshot = snapshot
        allRoutes = snapshot.routes
        updateFilter()
    }

    private fun updateFilter() {
        val query = searchField.text.trim()
        val selectedKey = selectedRoute()?.selectionKey()

        val filtered = if (query.isEmpty()) {
            allRoutes
        } else {
            if (sorter.sortKeys.isNotEmpty()) sorter.sortKeys = emptyList()
            RouteSearch.filter(query, allRoutes)
        }

        tableModel.setRoutes(filtered)
        restoreSelection(selectedKey)
        updateStatus()
        updateEmptyText()
        if (::toolbar.isInitialized) toolbar.updateActionsImmediately()
    }

    private fun restoreSelection(key: String?) {
        if (key == null) return
        val modelRow = (0 until tableModel.rowCount)
            .firstOrNull { tableModel.routeAt(it).selectionKey() == key }
        if (modelRow == null) {
            table.clearSelection()
            return
        }
        val viewRow = table.convertRowIndexToView(modelRow)
        if (viewRow >= 0) table.setRowSelectionInterval(viewRow, viewRow)
    }

    private fun VueRoute.selectionKey(): String =
        "${sourceFile.path}#$sourceLine#$sourceColumn#$path"

    private fun updateStatus() {
        val snapshot = lastSnapshot
        val error = snapshot?.error
        if (loading) {
            statusLabel.foreground = UIUtil.getLabelForeground()
            statusLabel.text = "Scanning…"
            return
        }
        if (error != null) {
            statusLabel.foreground = JBColor.RED
            statusLabel.text = error
            return
        }

        statusLabel.foreground = UIUtil.getLabelForeground()
        val total = allRoutes.size
        val visible = tableModel.rowCount
        val query = searchField.text.trim()
        statusLabel.text = when {
            total == 0 -> "0 routes"
            query.isNotEmpty() -> "$visible of $total routes"
            else -> {
                val files = snapshot?.routerFileCount ?: 0
                "$total routes · $files ${if (files == 1) "file" else "files"}"
            }
        }
    }

    private fun updateEmptyText() {
        val snapshot = lastSnapshot
        val error = snapshot?.error
        table.emptyText.text = when {
            loading -> "Scanning project for Vue Router routes…"
            error != null -> "Routes could not be loaded\n$error"
            allRoutes.isEmpty() -> "No Vue Router routes found\n" +
                "Open a project with a Vue Router configuration, then click Refresh."
            tableModel.rowCount == 0 -> "No routes match your search."
            else -> ""
        }
    }

    private fun selectedRoute(): VueRoute? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        val modelRow = table.convertRowIndexToModel(viewRow)
        return if (modelRow in 0 until tableModel.rowCount) tableModel.routeAt(modelRow) else null
    }

    private fun openRoute(route: VueRoute) {
        navigate(route.sourceFile, route.sourceLine, route.sourceColumn)
    }

    private fun openComponent(route: VueRoute) {
        val file = route.componentFile ?: return
        navigate(file, 1, 1)
    }

    private fun openParentComponent(route: VueRoute) {
        val file = route.parentComponentFile ?: return
        navigate(file, 1, 1)
    }

    private fun openBaseComponent(route: VueRoute) {
        val file = route.baseComponentFile ?: return
        navigate(file, 1, 1)
    }

    private fun navigate(file: VirtualFile, line: Int, column: Int) {
        if (!file.isValid) {
            statusLabel.foreground = JBColor.RED
            statusLabel.text = "File no longer exists: ${file.path}"
            return
        }
        OpenFileDescriptor(
            project,
            file,
            (line - 1).coerceAtLeast(0),
            (column - 1).coerceAtLeast(0),
        ).navigate(true)
    }

    private fun refresh() {
        service.refresh()
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, VueRoutesConfigurable::class.java)
    }

    private fun copySelectedPath() {
        val route = selectedRoute() ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(route.path))
    }

    override fun dispose() {
        listenerDisposable?.dispose()
    }

    private inner class RefreshAction : DumbAwareAction(
        "Refresh",
        "Rescan the project for Vue Router routes",
        AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(event: AnActionEvent) = refresh()

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !loading
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class OpenRouteAction : DumbAwareAction(
        "Open Route Definition",
        "Jump to the route declaration in the router file",
        AllIcons.Actions.EditSource,
    ) {
        override fun actionPerformed(event: AnActionEvent) {
            selectedRoute()?.let { openRoute(it) }
        }

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedRoute() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class OpenComponentAction : DumbAwareAction(
        "Open Component",
        "Open the Vue component used by this route",
        AllIcons.Nodes.Class,
    ) {
        override fun actionPerformed(event: AnActionEvent) {
            selectedRoute()?.let { openComponent(it) }
        }

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedRoute()?.componentFile != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class OpenParentComponentAction : DumbAwareAction(
        "Open Parent Component",
        "Open the layout component declared by the closest parent route",
        AllIcons.Nodes.Package,
    ) {
        override fun actionPerformed(event: AnActionEvent) {
            selectedRoute()?.let { openParentComponent(it) }
        }

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedRoute()?.parentComponentFile != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class OpenBaseComponentAction : DumbAwareAction(
        "Open Base Component",
        "Open the layout component declared by the topmost parent route (the base layout)",
        AllIcons.Nodes.UpLevel,
    ) {
        override fun actionPerformed(event: AnActionEvent) {
            selectedRoute()?.let { openBaseComponent(it) }
        }

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedRoute()?.showsBaseComponent == true
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class CopyPathAction : DumbAwareAction(
        "Copy Route Path",
        "Copy the full route path to the clipboard",
        AllIcons.Actions.Copy,
    ) {
        override fun actionPerformed(event: AnActionEvent) = copySelectedPath()

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedRoute() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class SettingsAction : DumbAwareAction(
        "Vue Routes Settings",
        "Configure scanning and auto-refresh",
        AllIcons.General.GearPlain,
    ) {
        override fun actionPerformed(event: AnActionEvent) = openSettings()

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }
}
