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
            // Disconnect the specific selected connection
            val terminal = executor.getTerminal(selectedConnection.id)
            if (terminal != null) {
                println("Disconnecting from ${selectedConnection.alias}")
                terminal.sendCommandToExecute("exit\n")
                // Remove the terminal from the map after sending exit command
                executor.removeTerminal(selectedConnection.id)
            } else {
                println("No active terminal found for connection: ${selectedConnection.alias}")
            }
        } else {
            // Fallback: disconnect the last used terminal if no connection is selected
            val allTerminals = executor.getAllTerminals()
            if (allTerminals.isNotEmpty()) {
                // Get the last used terminal entry
                val lastEntry = allTerminals.entries.last()
                val terminal = lastEntry.value
                val connectionId = lastEntry.key
                
                println("Disconnecting from last active terminal (connection ID: $connectionId)")
                terminal.sendCommandToExecute("exit\n")
                
                // Remove the terminal from the map after sending exit command
                executor.removeTerminal(connectionId)
            } else {
                println("No active terminals found")
            }
        }
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project != null) {
            // Get the executor service
            val executor = project.getService(SshConnectionExecutor::class.java)
            
            // Enable action if there are any active terminals or a selected connection with a terminal
            val selectedConnection = e.getDataContext().getData(PluginDataKeys.SELECTED_SSH_CONNECTION) as? SshConnectionData
            
            // Check if the selected connection has an active terminal
            val hasSelectedTerminal = selectedConnection != null && executor.getTerminal(selectedConnection.id) != null
            
            // Enable if there's a terminal for the selected connection or any terminal at all
            e.presentation.isEnabled = hasSelectedTerminal || executor.getAllTerminals().isNotEmpty()
        } else {
            e.presentation.isEnabled = false
        }
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}
