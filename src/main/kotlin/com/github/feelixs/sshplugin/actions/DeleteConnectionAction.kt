package com.github.feelixs.sshplugin.actions

import com.intellij.icons.AllIcons // Ensure AllIcons is imported
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import javax.swing.Icon

// Action to delete the selected SSH connection configuration.
class DeleteConnectionAction : AnAction, DumbAware {

    // Use secondary constructor to pass the icon
    constructor() : super(AllIcons.General.Remove)

    override fun actionPerformed(e: AnActionEvent) {
        val panel = e.getDataContext().getData(PluginDataKeys.SSH_TOOL_WINDOW_PANEL)
        panel?.deleteConnection()
    }

    override fun update(e: AnActionEvent) {
        // Enabled only if the panel is present and a connection is selected.
        val dataContext = e.dataContext
        val panel = dataContext.getData(PluginDataKeys.SSH_TOOL_WINDOW_PANEL)
        val selectedConnection = dataContext.getData(PluginDataKeys.SELECTED_SSH_CONNECTION)
        e.presentation.isEnabled = panel != null && selectedConnection != null
    }
}
