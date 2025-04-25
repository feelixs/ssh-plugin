package com.github.feelixs.sshplugin.services

import com.github.feelixs.sshplugin.model.SshConnectionData
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import org.jetbrains.plugins.terminal.TerminalView

/**
 * Executes SSH connections in the IntelliJ terminal.
 * Maintains a mapping of connection IDs to their terminal instances.
 */
@Service(Service.Level.PROJECT)
class SshConnectionExecutor(private val project: Project) {

    private val logger = thisLogger()
    
    // Prefix used for terminal tab titles to identify managed SSH sessions
    companion object {
        const val TERMINAL_TITLE_PREFIX = "SSH: "
    }

    /**
     * Generates the title for a terminal tab based on the connection alias.
     */
    private fun generateTerminalTitle(connectionAlias: String): String {
        return "$TERMINAL_TITLE_PREFIX$connectionAlias"
    }

    /**
     * Open a terminal tab and execute the SSH command for the given connection.
     * 
     * @param connectionId The ID of the SSH connection to use
     * Open a terminal tab and execute the SSH command for the given connection using TerminalView.
     * Relies on the underlying ssh client for authentication (keys, agent, interactive prompt).
     *
     * @param connectionId The ID of the SSH connection to use
     * @return true if the connection command was sent to a new terminal, false otherwise
     */
    fun executeConnection(connectionId: String): Boolean {
        logger.info("Attempting to execute connection with ID: $connectionId using TerminalView")

        // Get connection data (passwords are only needed for command generation if using sshpass, which we avoid now)
        val connectionData = SshConnectionStorageService.instance.getConnectionWithPlainPasswords(connectionId)
        if (connectionData == null) {
            logger.warn("Connection data not found for ID: $connectionId")
            return false
        }

        // Generate the basic SSH command (without password injection)
        val sshCommand = SshConnectionStorageService.instance.generateSshCommand(connectionId)
        if (sshCommand == null) {
            logger.error("Failed to generate SSH command for connection ID: $connectionId")
            return false
        }
        logger.info("Generated SSH command for ${connectionData.alias}: $sshCommand")

        try {
            // Get TerminalView service
            val terminalView = TerminalView.getInstance(project)
            val tabName = generateTerminalTitle(connectionData.alias)

            // Create a new terminal tab using TerminalView
            // Use project base path or a default path if null
            val workingDirectory = project.basePath ?: System.getProperty("user.home")
            val terminalWidget = terminalView.createLocalShellWidget(workingDirectory, tabName)

            // Execute the SSH command in the new terminal widget
            terminalWidget.executeCommand(sshCommand)

            // Activate the Terminal tool window to make the new tab visible
            ApplicationManager.getApplication().invokeLater {
                ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.activate(null)
                // Optional: Focus the new terminal widget if needed, though activate usually does this.
                // IdeFocusManager.getInstance(project).requestFocus(terminalWidget.component, true)
                
                // Optional: Maximize terminal if configured
                if (connectionData.maximizeTerminal) {
                    val toolWindowManager = ToolWindowManager.getInstance(project)
                    val terminalToolWindow = toolWindowManager.getToolWindow("Terminal")
                    if (terminalToolWindow != null) {
                         toolWindowManager.setMaximized(terminalToolWindow, true)
                    }
                }
            }

            logger.info("SSH command executed in new terminal tab: $tabName")
            return true
        } catch (e: Exception) {
            logger.error("Failed to create or execute command in terminal for connection ID: $connectionId", e)
            return false
        }
    }

    /**
     * Finds all active terminal widgets whose tab title starts with the given prefix.
     *
     * @param titlePrefix The prefix to match against terminal tab titles.
     * @return A list of matching TerminalWidget instances.
     */
    fun findTerminalWidgetsByTitlePrefix(titlePrefix: String): List<TerminalWidget> {
        val terminalManager = TerminalToolWindowManager.getInstance(project)
        // Access widgets directly from the manager
        return terminalManager.terminalWidgets.filter { widget ->
            // Check if the widget's disposable hasn't been disposed and title matches
            !widget.disposable.isDisposed && widget.tabName.startsWith(titlePrefix)
        }
    }
    
    /**
     * Sends the End-of-File (EOF) character (Ctrl+D) to a terminal widget.
     * This typically closes the shell or logs out of the SSH session.
     *
     * @param widget The TerminalWidget to send EOF to.
     */
    fun sendEofToTerminal(widget: TerminalWidget) {
        try {
            // \u0004 is the ASCII code for EOT (End of Transmission), often interpreted as EOF by shells.
            widget.sendCommandToExecute("\u0004")
            logger.info("Sent EOF (Ctrl+D) to terminal: ${widget.tabName}")
        } catch (e: Exception) {
            logger.error("Failed to send EOF to terminal: ${widget.tabName}", e)
        }
    }
}
