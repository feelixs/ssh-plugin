package com.github.feelixs.sshplugin.toolWindow

import com.github.feelixs.sshplugin.model.SshConnectionData
import com.github.feelixs.sshplugin.services.SshConnectionExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList

/**
 * Custom renderer for SSH connections in the list.
 */
class SshConnectionListCellRenderer : ColoredListCellRenderer<SshConnectionData>() {
    
    // Define custom attributes for active connections
    private val ACTIVE_CONNECTION_ATTRIBUTES = SimpleTextAttributes(
        SimpleTextAttributes.STYLE_BOLD,
        JBColor.GREEN
    )
    
    override fun customizeCellRenderer(
        list: JList<out SshConnectionData>,
        value: SshConnectionData,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean
    ) {
        // Set icon based on OS type
        val icon = when (value.osType) {
            com.github.feelixs.sshplugin.model.OsType.LINUX -> AllIcons.RunConfigurations.Application
            com.github.feelixs.sshplugin.model.OsType.WINDOWS -> AllIcons.FileTypes.Any_type
        }
        setIcon(icon)
        
        // Check if this connection has active sessions
        val activeSessionCount = getActiveSessionCount(value.id)
        
        // Add asterisk indicator for active connections
        if (activeSessionCount > 0) {
            // Show asterisk and session count for active connections
            append("* ", ACTIVE_CONNECTION_ATTRIBUTES)
            
            // Display the connection alias as the main text in bold
            append(value.alias, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            
            // Show active session count if more than one
            if (activeSessionCount > 1) {
                append(" (${activeSessionCount} sessions)", ACTIVE_CONNECTION_ATTRIBUTES)
            }
        } else {
            // Display the connection alias as the main text
            append(value.alias, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
        
        // Show host and username as secondary information
        append(" (${value.username}@${value.host})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        
        // Show authentication type
        if (value.useKey) {
            append(" [key]", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
        }
        
        // Show sudo indicator if applicable
        if (value.useSudo) {
            append(" [auto-sudo]", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
        }
    }
    
    /**
     * Gets the count of active sessions for a connection ID
     * 
     * @param connectionId The ID of the connection to check
     * @return The number of active sessions, or 0 if none
     */
    private fun getActiveSessionCount(connectionId: String): Int {
        // Try to get the executor service from the first open project
        // This is a limitation, but should work in most cases
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return 0
        val executor = project.getService(SshConnectionExecutor::class.java) ?: return 0
        
        return executor.getTerminalCount(connectionId)
    }
}