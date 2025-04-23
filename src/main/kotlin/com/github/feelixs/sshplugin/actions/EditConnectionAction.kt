package com.github.feelixs.sshplugin.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

// Action to edit the selected SSH connection configuration.
class EditConnectionAction : AnAction(AllIcons.Actions.Edit), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val panel = e.getData(PluginDataKeys.SSH_TOOL_WINDOW_PANEL)
        panel?.editConnection()
    }

    override fun update(e: AnActionEvent) {
        // Enabled only if the panel is present and a connection is selected.
        val panel = e.getData(PluginDataKeys.SSH_TOOL_WINDOW_PANEL)
        val selectedConnection = e.getData(PluginDataKeys.SELECTED_SSH_CONNECTION)
        e.presentation.isEnabled = panel != null && selectedConnection != null
    }
}
