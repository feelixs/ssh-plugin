package com.github.feelixs.sshplugin.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Action to duplicate the selected SSH connection configuration.
 */
class DuplicateConnectionAction : AnAction(AllIcons.General.CopyHovered), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val panel = e.getDataContext().getData(PluginDataKeys.SSH_TOOL_WINDOW_PANEL)
        panel?.duplicateConnection()
    }

    override fun update(e: AnActionEvent) {
        // Enabled only if the panel is present and a connection is selected.
        val dataContext = e.dataContext
        val panel = dataContext.getData(PluginDataKeys.SSH_TOOL_WINDOW_PANEL)
        val selectedConnection = dataContext.getData(PluginDataKeys.SELECTED_SSH_CONNECTION)
        e.presentation.isEnabled = panel != null && selectedConnection != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}