package com.github.feelixs.sshplugin.actions

import com.intellij.icons.AllIcons // Import AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

// Action to add a new SSH connection configuration.
class AddConnectionAction : AnAction(AllIcons.Actions.Add), DumbAware { // Use AllIcons.Actions.Add

    override fun actionPerformed(e: AnActionEvent) {
        val panel = e.getData(PluginDataKeys.SSH_TOOL_WINDOW_PANEL)
        panel?.addConnection()
    }

    override fun update(e: AnActionEvent) {
        // Action is always enabled if the panel is present.
        val panel = e.getData(PluginDataKeys.SSH_TOOL_WINDOW_PANEL)
        e.presentation.isEnabled = panel != null
    }
}
