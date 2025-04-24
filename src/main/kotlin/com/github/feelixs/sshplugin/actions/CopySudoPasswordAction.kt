package com.github.feelixs.sshplugin.actions

import com.github.feelixs.sshplugin.services.SshConnectionStorageService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import java.awt.datatransfer.StringSelection

// Action to copy the sudo password of the highlighted connection to clipboard.
class CopySudoPasswordAction : AnAction(AllIcons.Actions.Copy), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val selectedConnection = e.getDataContext().getData(PluginDataKeys.SELECTED_SSH_CONNECTION)
        if (selectedConnection != null) {
            // Get the connection with decrypted passwords
            val connectionWithPasswords = SshConnectionStorageService.instance
                .getConnectionWithPlainPasswords(selectedConnection.id)
                
            // Copy the sudo password to clipboard if it exists
            connectionWithPasswords?.encodedSudoPassword?.let { sudoPassword ->
                if (sudoPassword.isNotBlank()) {
                    val stringSelection = StringSelection(sudoPassword)
                    CopyPasteManager.getInstance().setContents(stringSelection)
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        // Enable only if a connection is selected and it uses sudo
        val selectedConnection = e.dataContext.getData(PluginDataKeys.SELECTED_SSH_CONNECTION)
        
        // Action is enabled only when a connection is selected and uses sudo
        val isEnabled = selectedConnection != null && 
                        selectedConnection.useSudo
        
        e.presentation.isEnabled = isEnabled
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}