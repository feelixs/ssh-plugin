package com.github.feelixs.sshplugin.toolWindow

// Remove duplicate imports
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

// Factory responsible for creating the SSH Connections tool window UI.
// Implement DumbAware to allow the tool window to be opened during indexing.
class SshToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Create our custom panel
        val sshToolWindowPanel = SshToolWindowPanel(project)

        // Get the content factory
        val contentFactory = ContentFactory.getInstance()

        // Create content and add it to the tool window
        val content = contentFactory.createContent(sshToolWindowPanel, "", false) // Title is set in plugin.xml
        content.setDisposer(sshToolWindowPanel) // Register for proper cleanup
        toolWindow.contentManager.addContent(content)
    }
}
