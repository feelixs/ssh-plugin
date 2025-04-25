package com.github.feelixs.sshplugin.toolWindow

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.github.feelixs.sshplugin.services.SshConnectionExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
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

        // Check if this connection has active sessions by looking for terminal tabs with the expected title
        val activeSessionCount = getActiveSessionCount(value)

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
     * Gets the count of active terminal sessions associated with the given connection data.
     * It checks for terminal tabs whose titles start with "SSH: [connection_alias]".
     *
     * @param connectionData The connection data to check.
     * @return The number of active terminal sessions found, or 0.
     */
    private fun getActiveSessionCount(connectionData: SshConnectionData): Int {
        // Find the project associated with the list component, if possible.
        // Fallback to iterating open projects, but this is less reliable if multiple projects are open.
        val project = findProjectForList(list) ?: ProjectManager.getInstance().openProjects.firstOrNull() ?: return 0
        
        val executor = project.getService(SshConnectionExecutor::class.java) ?: return 0
        
        // Construct the expected title prefix
        val titlePrefix = SshConnectionExecutor.TERMINAL_TITLE_PREFIX + connectionData.alias
        
        // Find terminals matching the title prefix
        return executor.findTerminalWidgetsByTitlePrefix(titlePrefix).size
    }

    /**
     * Helper function to find the Project associated with the JList component.
     * This is a bit of a workaround, as renderers don't have direct project context.
     */
    private fun findProjectForList(list: JList<*>): Project? {
        // Try to find the project from the component hierarchy
        var parent = list.parent
        while (parent != null) {
            if (parent is com.intellij.openapi.ui.ComponentWithBrowseButton<*>) {
                 // Could potentially get project context here if the component provides it
            }
            // Add more checks if needed based on where the list is embedded
            parent = parent.parent
        }
        // Fallback if project cannot be determined from component hierarchy
        return ProjectManager.getInstance().openProjects.firstOrNull()
    }
}
