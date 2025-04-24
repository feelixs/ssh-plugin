package com.github.feelixs.sshplugin.actions

import com.github.feelixs.sshplugin.services.SshConnectionStorageService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import java.awt.datatransfer.StringSelection

// Action to copy the sudo password of the highlighted connection to clipboard.
class CopySudoPasswordAction : AnAction(AllIcons.Actions.Copy), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val selectedConnection = e.getDataContext().getData(PluginDataKeys.SELECTED_SSH_CONNECTION)
        var passwordType = "Empty"
        
        // If no connection is selected, just copy an empty string
        if (selectedConnection == null) {
            val emptySelection = StringSelection("")
            CopyPasteManager.getInstance().setContents(emptySelection)
            showNotification(project, "No connection selected", NotificationType.INFORMATION)
            return
        }
        
        // Get the connection with decrypted passwords
        val connectionWithPasswords = SshConnectionStorageService.instance
            .getConnectionWithPlainPasswords(selectedConnection.id)
        
        if (connectionWithPasswords == null) {
            val emptySelection = StringSelection("")
            CopyPasteManager.getInstance().setContents(emptySelection)
            showNotification(project, "Failed to get connection data", NotificationType.ERROR)
            return
        }
        
        // First try to use sudo password if available and not empty
        val sudoPassword = connectionWithPasswords.encodedSudoPassword
        if (!sudoPassword.isNullOrBlank()) {
            val stringSelection = StringSelection(sudoPassword)
            CopyPasteManager.getInstance().setContents(stringSelection)
            passwordType = "Sudo password"
        } else {
            // Fall back to user password if sudo password is null or empty
            val userPassword = connectionWithPasswords.encodedPassword ?: ""
            val stringSelection = StringSelection(userPassword)
            CopyPasteManager.getInstance().setContents(stringSelection)
            passwordType = if (userPassword.isBlank()) "Empty password" else "User password"
        }
        
        // Show success notification
        showNotification(
            project,
            "$passwordType for '${connectionWithPasswords.alias}' copied to clipboard",
            NotificationType.INFORMATION
        )
    }
    
    private fun showNotification(project: com.intellij.openapi.project.Project?, message: String, type: NotificationType) {
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("SSH Plugin Notifications")
        
        notificationGroup.createNotification(message, type)
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        // Always enable the action
        e.presentation.isEnabled = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}