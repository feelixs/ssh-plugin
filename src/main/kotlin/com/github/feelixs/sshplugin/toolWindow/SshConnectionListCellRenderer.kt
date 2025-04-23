package com.github.feelixs.sshplugin.toolWindow

import com.github.feelixs.sshplugin.model.SshConnectionData
import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList

/**
 * Custom renderer for SSH connections in the list.
 */
class SshConnectionListCellRenderer : ColoredListCellRenderer<SshConnectionData>() {
    
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
        
        // Display the connection alias as the main text
        append(value.alias, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        
        // Show host and username as secondary information
        append(" (${value.username}@${value.host})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        
        // Show sudo indicator if applicable
        if (value.useSudo) {
            append(" [sudo]", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
        }
    }
}