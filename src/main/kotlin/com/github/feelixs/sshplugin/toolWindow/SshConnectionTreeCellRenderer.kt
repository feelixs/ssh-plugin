package com.github.feelixs.sshplugin.toolWindow

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.github.feelixs.sshplugin.model.SshFolder
import com.github.feelixs.sshplugin.services.SshConnectionExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Renders both folder and connection nodes in the SSH tool window tree.
 * Connection rendering matches the previous list cell renderer (active session
 * asterisk, host info, [key], [auto-sudo] markers, OS-type icon).
 */
class SshConnectionTreeCellRenderer : ColoredTreeCellRenderer() {

    private val ACTIVE_CONNECTION_ATTRIBUTES = SimpleTextAttributes(
        SimpleTextAttributes.STYLE_BOLD,
        JBColor.GREEN
    )

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val userObject = node.userObject) {
            is SshFolder -> renderFolder(userObject)
            is SshConnectionData -> renderConnection(userObject)
            else -> { /* root node, render nothing */ }
        }
    }

    private fun renderFolder(folder: SshFolder) {
        icon = AllIcons.Nodes.Folder
        append(folder.name.ifBlank { "(unnamed folder)" }, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }

    private fun renderConnection(value: SshConnectionData) {
        val osIcon = when (value.osType) {
            OsType.LINUX -> AllIcons.RunConfigurations.Application
            OsType.WINDOWS -> AllIcons.FileTypes.Any_type
        }
        icon = osIcon

        val activeSessionCount = getActiveSessionCount(value.id)
        if (activeSessionCount > 0) {
            append("* ", ACTIVE_CONNECTION_ATTRIBUTES)
            append(value.alias, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (activeSessionCount > 1) {
                append(" (${activeSessionCount} sessions)", ACTIVE_CONNECTION_ATTRIBUTES)
            }
        } else {
            append(value.alias, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }

        append(" (${value.username}@${value.host})", SimpleTextAttributes.GRAYED_ATTRIBUTES)

        if (value.useKey) {
            append(" [key]", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
        }
        @Suppress("DEPRECATION")
        if (value.useSudo) {
            append(" [auto-sudo]", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
        }
    }

    private fun getActiveSessionCount(connectionId: String): Int {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return 0
        val executor = project.getService(SshConnectionExecutor::class.java) ?: return 0
        return executor.getTerminalCount(connectionId)
    }
}
