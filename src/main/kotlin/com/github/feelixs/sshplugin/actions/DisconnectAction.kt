package com.github.feelixs.sshplugin.actions

import com.github.feelixs.sshplugin.services.SshConnectionExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

class DisconnectAction : AnAction(AllIcons.Actions.Suspend), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        // Get the SshConnectionExecutor service
        val executor = project.getService(SshConnectionExecutor::class.java)
        val terminal = executor.getTerminal()
        
        if (terminal == null) {
            println("Terminal was null - no active connection to disconnect")
        } else {
            println("Sending exit command to terminal")
            terminal.sendCommandToExecute("exit\n")
        }
    }
    
    override fun update(e: AnActionEvent) {
        // Only enable the action if we have a project
        val project = e.project
        e.presentation.isEnabled = project != null
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}
