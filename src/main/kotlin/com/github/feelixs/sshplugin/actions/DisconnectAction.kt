package com.github.feelixs.sshplugin.actions

import com.github.feelixs.sshplugin.services.SshConnectionExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

class DisconnectAction : AnAction(AllIcons.Actions.Suspend), DumbAware { // Suspend icon visually fits "disconnect"

    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedConnection = e.getDataContext().getData(PluginDataKeys.SELECTED_SSH_CONNECTION) ?: return
        val executor = project.getService(SshConnectionExecutor::class.java) ?: return

        val titlePrefix = SshConnectionExecutor.TERMINAL_TITLE_PREFIX + selectedConnection.alias
        val terminals = executor.findTerminalWidgetsByTitlePrefix(titlePrefix)
        val terminalCount = terminals.size

        if (terminalCount > 0) {
            logger.info("Disconnecting $terminalCount session(s) for ${selectedConnection.alias} by sending EOF")
            terminals.forEach { terminal ->
                executor.sendEofToTerminal(terminal)
                // Optionally, could try closing the tab directly after a delay,
                // but sending EOF is generally safer.
                // Example: TerminalView.getInstance(project).closeTab(terminal.content)
            }
        } else {
            logger.info("No active terminals found with title prefix '$titlePrefix' for connection: ${selectedConnection.alias}")
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val selectedConnection = e.getDataContext().getData(PluginDataKeys.SELECTED_SSH_CONNECTION)
        
        e.presentation.isEnabled = false // Default to disabled
        e.presentation.text = "Disconnect" // Default text
        e.presentation.description = "Disconnect active SSH session(s)" // Default description

        if (project != null && selectedConnection != null) {
            val executor = project.getService(SshConnectionExecutor::class.java)
            if (executor != null) {
                val titlePrefix = SshConnectionExecutor.TERMINAL_TITLE_PREFIX + selectedConnection.alias
                val terminals = executor.findTerminalWidgetsByTitlePrefix(titlePrefix)
                val terminalCount = terminals.size
                val hasActiveTerminals = terminalCount > 0

                e.presentation.isEnabled = hasActiveTerminals

                if (hasActiveTerminals) {
                    val sessionText = if (terminalCount == 1) "session" else "sessions"
                    e.presentation.description = "Disconnect $terminalCount active $sessionText for ${selectedConnection.alias} (Sends EOF)"
                    e.presentation.text = if (terminalCount > 1) "Disconnect [$terminalCount]" else "Disconnect"
                } else {
                    e.presentation.description = "No active sessions found for ${selectedConnection.alias}"
                }
            } else {
                 e.presentation.description = "Cannot get SSH executor service"
            }
        } else if (selectedConnection == null) {
            e.presentation.description = "Select a connection to disconnect"
        } else {
             e.presentation.description = "Project context not available"
        }
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}
