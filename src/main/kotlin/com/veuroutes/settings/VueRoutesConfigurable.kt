package com.veuroutes.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.veuroutes.service.VueRoutesService
import javax.swing.JComponent
import javax.swing.JPanel

class VueRoutesConfigurable : SearchableConfigurable {

    private val autoRefresh = JBCheckBox(
        "Automatically rescan when router or route files change",
    )
    private val excludedFolders = JBTextField(60)
    private val maxFileSize = JBIntSpinner(
        VueRoutesSettings.DEFAULT_MAX_FILE_SIZE_KB,
        VueRoutesSettings.MIN_FILE_SIZE_KB,
        VueRoutesSettings.MAX_FILE_SIZE_KB,
    )
    private var panel: JPanel? = null

    override fun getId(): String = "com.veuroutes.settings"

    override fun getDisplayName(): String = "Vue Routes List"

    override fun createComponent(): JComponent {
        return panel ?: FormBuilder.createFormBuilder()
            .addComponent(autoRefresh)
            .addLabeledComponent("Excluded folders:", excludedFolders)
            .addTooltip("Comma-separated folder names. Matching is case-insensitive and applies to every path segment.")
            .addLabeledComponent("Maximum file size (KB):", maxFileSize)
            .addTooltip("Router files larger than this limit are skipped.")
            .addComponent(
                JBLabel(
                    "<html><body style='width:520px'>" +
                        "Vue Routes List scans JavaScript and TypeScript files for Vue Router arrays. " +
                        "Component imports are resolved through tsconfig/jsconfig paths, the " +
                        "conventional <code>@/</code> and <code>~/</code> aliases, and relative paths." +
                        "</body></html>",
                ),
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .also { panel = it }
    }

    override fun isModified(): Boolean {
        val settings = VueRoutesSettings.getInstance()
        return autoRefresh.isSelected != settings.autoRefresh ||
            excludedFolders.text.trim() != settings.excludedFolders ||
            maxFileSize.number != settings.maxFileSizeKb
    }

    override fun apply() {
        val settings = VueRoutesSettings.getInstance()
        settings.update(
            autoRefresh = autoRefresh.isSelected,
            excludedFolders = excludedFolders.text,
            maxFileSizeKb = maxFileSize.number,
        )

        ProjectManager.getInstance().openProjects.forEach { project ->
            if (!project.isDisposed && !project.isDefault) {
                project.getServiceIfCreated(VueRoutesService::class.java)?.refresh()
            }
        }
    }

    override fun reset() {
        val settings = VueRoutesSettings.getInstance()
        autoRefresh.isSelected = settings.autoRefresh
        excludedFolders.text = settings.excludedFolders
        maxFileSize.number = settings.maxFileSizeKb
    }

    override fun disposeUIResources() {
        panel = null
    }
}
