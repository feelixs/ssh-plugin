package com.github.feelixs.sshplugin.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel
import javax.swing.JLabel

// Factory responsible for creating the SSH Connections tool window UI.
class SshToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Create the main panel for the tool window
        val toolWindowPanel = JPanel() // Using a simple JPanel for now
        toolWindowPanel.add(JLabel("SSH Connections List Placeholder")) // Add placeholder content

        // Get the content factory
        val contentFactory = ContentFactory.getInstance()

        // Create content and add it to the tool window
        val content = contentFactory.createContent(toolWindowPanel, "", false) // Title is set in plugin.xml
        toolWindow.contentManager.addContent(content)
    }
}
