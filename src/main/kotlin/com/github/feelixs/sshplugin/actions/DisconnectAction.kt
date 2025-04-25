package com.github.feelixs.sshplugin.actions

import com.github.feelixs.sshplugin.model.SshConnectionData
import com.github.feelixs.sshplugin.services.SshConnectionExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

class DisconnectAction : AnAction(AllIcons.Actions.Suspend), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        // Get the selected connection
        val selectedConnection = e.getDataContext().getData(PluginDataKeys.SELECTED_SSH_CONNECTION)
        
        // Get the SshConnectionExecutor service
        val executor = project.getService(SshConnectionExecutor::class.java)
        
        if (selectedConnection != null) {
            // Get all terminals for this connection
            val terminals = executor.getAllTerminalsForConnection(selectedConnection.id)
            val terminalCount = terminals.size
            
            if (terminalCount > 0) {
                println("Disconnecting ${terminalCount} session(s) for ${selectedConnection.alias}")
                
                // Send Ctrl+D (EOF) to each terminal
                terminals.forEach { terminal ->
                    terminal.sendCommandToExecute("\u0004") // disconnect from ssh
                    terminal.sendCommandToExecute("\u0004") // close terminal window
                }
                
                // Remove all terminals from the map
                executor.removeAllTerminals(selectedConnection.id)
            } else {
                println("No active terminals found for connection: ${selectedConnection.alias}")
            }
        }
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project != null) {
            // Get the executor service
            val executor = project.getService(SshConnectionExecutor::class.java)
            
            // Get the selected connection
            val selectedConnection = e.getDataContext().getData(PluginDataKeys.SELECTED_SSH_CONNECTION) as? SshConnectionData
            
            if (selectedConnection != null) {
                // Count active terminals for this connection
                val terminalCount = executor.getTerminalCount(selectedConnection.id)
                val hasActiveTerminals = terminalCount > 0
                
                // Only enable if the selected connection has active terminals
                e.presentation.isEnabled = hasActiveTerminals
                
                // Update tooltip to show status with session count
                if (hasActiveTerminals) {
                    val sessionText = if (terminalCount == 1) "session" else "sessions"
                    e.presentation.description = "Disconnect ${terminalCount} active $sessionText for ${selectedConnection.alias}"
                    
                    // Update button text to reflect multiple sessions if applicable
                    e.presentation.text = if (terminalCount > 1) {
                        "Disconnect [$terminalCount]"
                    } else {
                        "Disconnect"
                    }
                } else {
                    e.presentation.description = "No active sessions for ${selectedConnection.alias}"
                    e.presentation.text = "End Session(s)"
                }
            } else {
                // No connection is selected, so disable the action
                e.presentation.isEnabled = false
                e.presentation.description = "Select a connection with an active terminal to disconnect"
                e.presentation.text = "End Session(s)"
            }
        } else {
            e.presentation.isEnabled = false
        }
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}
