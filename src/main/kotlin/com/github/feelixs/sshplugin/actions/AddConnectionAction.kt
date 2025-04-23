package com.github.feelixs.sshplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.util.IconUtil

// Action to add a new SSH connection configuration.
class AddConnectionAction : AnAction(IconUtil.getAddIcon()), DumbAware {

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
