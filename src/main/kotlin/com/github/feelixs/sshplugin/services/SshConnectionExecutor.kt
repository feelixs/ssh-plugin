package com.github.feelixs.sshplugin.services

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Executes SSH connections in the IntelliJ terminal.
 * Maintains a mapping of connection IDs to their terminal instances.
 */
@Service
class SshConnectionExecutor(private val project: Project) {

    // Map of connection IDs to their terminal widgets (supporting multiple terminals per connection)
    private val terminalMap = mutableMapOf<String, MutableList<TerminalWidget>>()
    private val logger = thisLogger()

    /**
     * Gets the first/primary terminal instance for a specific connection ID
     * 
     * @param connectionId The ID of the connection to get the terminal for.
     *                     If null, returns the most recently used terminal.
     * @return The primary terminal widget for the specified connection or null if not found
     */
    fun getTerminal(connectionId: String? = null): TerminalWidget? {
        return if (connectionId != null) {
            terminalMap[connectionId]?.firstOrNull()
        } else {
            // If no specific ID is provided, return the first terminal of the most recently used connection
            terminalMap.values.lastOrNull()?.firstOrNull()
        }
    }
    
    /**
     * Gets all terminal instances for a specific connection ID
     * 
     * @param connectionId The ID of the connection to get terminals for
     * @return List of terminal widgets for the connection or empty list if none found
     */
    fun getAllTerminalsForConnection(connectionId: String): List<TerminalWidget> {
        return terminalMap[connectionId] ?: emptyList()
    }
    
    /**
     * Gets all active terminal instances
     * 
     * @return Map of connection IDs to lists of terminal widgets
     */
    fun getAllTerminals(): Map<String, List<TerminalWidget>> {
        return terminalMap.toMap()
    }
    
    /**
     * Counts the number of active terminals for a connection
     * 
     * @param connectionId The ID of the connection to count terminals for
     * @return The number of active terminals
     */
    fun getTerminalCount(connectionId: String): Int {
        return terminalMap[connectionId]?.size ?: 0
    }

    fun removeTerminal(connectionId: String): Boolean {
        try {
            terminalMap.remove(connectionId)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Removes all terminals for a connection from the map
     * 
     * @param connectionId The ID of the connection to remove terminals for
     * @return The number of terminals that were removed
     */
    fun removeAllTerminals(connectionId: String): Int {
        val count = terminalMap[connectionId]?.size ?: 0
        terminalMap.remove(connectionId)
        println("Removed $count terminals for connection ID: $connectionId")
        return count
    }

    private fun showNotification(project: com.intellij.openapi.project.Project?, message: String, type: NotificationType) {
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("SSH Plugin Notifications")

        notificationGroup.createNotification(message, type)
            .notify(project)
    }

    /**
     * Open a terminal tab and execute the SSH command for the given connection.
     * 
     * @param connectionId The ID of the SSH connection to use
     * @return true if the connection was initiated successfully, false otherwise
     */
    fun executeConnection(connectionId: String): Boolean {
        println("Attempting to execute connection with ID: $connectionId")
        // Get connection data with decrypted passwords
        val connectionData = SshConnectionStorageService.instance.getConnectionWithPlainPasswords(connectionId)
        if (connectionData == null) {
            logger.warn("Connection data not found for ID: $connectionId")
            return false
        }
        
        // Ensure we have properly decrypted passwords
        println("Verifying connection passwords are properly decrypted")

        // Log connection details (masking sensitive info)
        logConnectionDetails(connectionData)

        // Generate the SSH command
        val sshCommand = SshConnectionStorageService.instance.generateSshCommand(connectionId)
        if (sshCommand == null) {
            logger.error("Failed to generate SSH command for connection ID: $connectionId")
            return false
        }
        println("Generated SSH command for ${connectionData.alias}: $sshCommand")

        // Create a terminal tab for the connection
        val terminalManager = TerminalToolWindowManager.getInstance(project)
        val tabName = connectionData.alias
        val terminal = terminalManager.createShellWidget(project.basePath ?: "", tabName, false, false)
        
        // Register a disposable listener to detect when the terminal is closed
        Disposer.register(terminal, {
            handleTerminalClosed(connectionId, terminal)
        })
        
        // Store terminal in the map with the connection ID
        terminalMap.getOrPut(connectionId) { mutableListOf() }.add(terminal)

        val toolWindowManager = ToolWindowManager.getInstance(project)
        val terminalToolWindow = toolWindowManager.getToolWindow("Terminal")
        terminalToolWindow?.activate {
            println("Terminal focus requested on UI thread : ${terminal.hasFocus()}")
        }
        // this will obscure the actual shh automation so that the user can't interfere accidentally
        val temp = terminalManager.createShellWidget(project.basePath ?: "", "SSH", false, false)

        // Execute the SSH command
        println("Executing command in terminal for ${connectionData.alias}")
        terminal.sendCommandToExecute(sshCommand)
        temp.sendCommandToExecute("printf \"\\033[32mSSH \n\n\n\n\n\n\n\n\n\n\n\n\n\nInitializing SSH for: ${connectionData.alias} - please wait...\n\n\n\\033[0m\\n\"")
        // Handle SSH key passphrase and custom commands in a background thread to avoid blocking the UI
        if ((connectionData.useKey && !connectionData.encodedKeyPassword.isNullOrEmpty()) || 
            (connectionData.runCommands && connectionData.commands.isNotBlank())) {
            
            println("Starting background thread for timed password automation for ${connectionData.alias}")

            // Create a separate thread for handling interactive prompts
            Thread {
                try {
                    // Fixed timing delays for authentication steps
                    val initialDelay = 3000L        // Wait for SSH to start and possibly show passphrase prompt
                    val sshEstablishDelay = 3000L   // Wait for SSH connection to establish
                    val sudoPromptDelay = 1500L     // Wait for sudo prompt to appear
                    
                    // Handle key passphrase if needed (enter after initial delay)
                    if (connectionData.useKey && !connectionData.encodedKeyPassword.isNullOrEmpty()) {
                        println("Waiting ${initialDelay}ms for potential key passphrase prompt")
                        Thread.sleep(initialDelay)
                        
                        connectionData.encodedKeyPassword?.let { keyPassword ->
                            println("Sending key passphrase after timed delay")
                            terminal.sendCommandToExecute("$keyPassword\n")
                        }
                    }
                    
                    // Handle commands to run after successful connection
                    if (connectionData.runCommands && connectionData.commands.isNotBlank()) {
                        // Wait for SSH connection to fully establish before running commands
                        println("Waiting ${sshEstablishDelay}ms for SSH connection to establish")
                        Thread.sleep(sshEstablishDelay)
                    }
                    Thread.sleep(sudoPromptDelay)
                    println("Timed password automation completed for ${connectionData.alias}")
                    if (connectionData.maximizeTerminal) {
                        terminalToolWindow?.activate {
                            toolWindowManager.setMaximized(terminalToolWindow, true)
                        }
                    }
                    IdeFocusManager.getInstance(project).requestFocus(temp.component, true)
                    temp.sendCommandToExecute("\u0004")  //exit temp window
                    Thread.sleep(sudoPromptDelay)

                    // Execute each command line by line
                    if (connectionData.commands.isNotBlank()) {
                        showNotification(project, "Sending user-defined startup commands...", NotificationType.INFORMATION)
                        val commands = connectionData.commands.split("\n")
                        var shouldbreak = false
                        commands.forEach { command ->
                            if (command.isNotBlank()) {
                                // Skip comment lines
                                if (!command.startsWith("#") && !shouldbreak) {
                                    println("Executing command: $command")
                                    terminal.sendCommandToExecute(command)

                                    // If this is a sudo command, it may need password
                                    if (command.trim().startsWith("sudo") && connectionData.osType == OsType.LINUX) {
                                        // Send appropriate sudo password if we have one
                                        println(!connectionData.encodedSudoPassword.isNullOrEmpty())
                                        println(connectionData.useUserPasswordForSudo)
                                        println(!connectionData.encodedPassword.isNullOrEmpty())
                                        if (!connectionData.encodedSudoPassword.isNullOrEmpty() || (connectionData.useUserPasswordForSudo && !connectionData.encodedPassword.isNullOrEmpty())) {
                                            println("Waiting ${sudoPromptDelay}ms for potential sudo password prompt")
                                            Thread.sleep(sudoPromptDelay)
                                            println("Sending sudo password")

                                            var pswd = connectionData.encodedSudoPassword
                                            if (connectionData.useUserPasswordForSudo) {
                                                pswd = connectionData.encodedPassword
                                            }
                                            terminal.sendCommandToExecute("${pswd}\n")
                                            Thread.sleep(sudoPromptDelay)
                                        } else {
                                            println("No passwords provided")
                                            showNotification(
                                                project,
                                                "No SUDO password provided, but a SUDO command was defined in startup commands.\nCancelling the rest...",
                                                NotificationType.WARNING
                                            )
                                            shouldbreak = true
                                        }
                                    } else {
                                        // Short delay between commands for better reliability
                                        Thread.sleep(1000L)
                                    }
                                }
                            }
                        }
                    }
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt() // Restore interrupt status
                    logger.warn("Background handling thread interrupted for ${connectionData.alias}", ie)
                } catch (e: Exception) {
                    // Log any errors that occur during the async process
                    logger.error("Error in background handling thread for ${connectionData.alias}", e)
                }
            }.start()

            showNotification(
                project,
                "SSH for ${connectionData.alias}: successful",
                NotificationType.INFORMATION
            )
        } else {
            println("No background handling needed (no key passphrase or sudo) for ${connectionData.alias}")
        }

        return true
    }

    /**
     * Handles cleanup when a terminal is closed by the user
     * 
     * @param connectionId The ID of the connection associated with the terminal
     * @param terminal The terminal widget that was closed
     */
    private fun handleTerminalClosed(connectionId: String, terminal: TerminalWidget) {
        logger.info("Terminal closed for connection ID: $connectionId")
        
        // Remove this specific terminal from the map
        val terminals = terminalMap[connectionId]
        terminals?.remove(terminal)
        
        // Log the terminal count after removal
        logger.info("Terminals remaining for connection $connectionId: ${terminals?.size ?: 0}")
        
        // If this was the last terminal for this connection, remove the connection entry
        if (terminals?.isEmpty() == true) {
            terminalMap.remove(connectionId)
            logger.info("Removed last terminal for connection ID: $connectionId")
            
            // You can add additional cleanup logic here
            // For example, notify other components that the connection is fully closed
        }
        
        // Force refresh of any components that might be using terminal count
        notifyTerminalCountChanged(connectionId)
    }
    
    /**
     * Notifies other components that terminal count has changed
     * Override this or implement event system if needed
     */
    private fun notifyTerminalCountChanged(connectionId: String) {
        // This is a placeholder for an event system
        // You might want to implement a proper event publishing mechanism
        println("Terminal count changed for connection ID: $connectionId, count: ${getTerminalCount(connectionId)}")
    }

    private fun logConnectionDetails(connection: SshConnectionData) {
        println("--- Connection Details (${connection.alias}) ---")
        println("  ID: ${connection.id}")
        println("  Host: ${connection.host}")
        println("  Port: ${connection.port}")
        println("  Username: ${connection.username}")
        println("  OS Type: ${connection.osType}")
        println("  Use Key: ${connection.useKey}")
        if (connection.useKey) {
            println("  Key Path: ${connection.keyPath}")
            // Log if passphrase *exists* but not the passphrase itself
            println("  Key Passphrase Provided: ${!connection.encodedKeyPassword.isNullOrBlank()}")
        } else {
            // Log if password *exists* but not the password itself
            println("  Password Provided: ${!connection.encodedPassword.isNullOrBlank()}")
        }
        println("  Run Commands: ${connection.runCommands}")
        if (connection.runCommands) {
            println("  Commands Count: ${connection.commands.split("\n").count { it.isNotBlank() }}")
            // Log if sudo password *exists* but not the password itself
            println("  Sudo Password Provided: ${!connection.encodedSudoPassword.isNullOrBlank()}")
        }
        println("---------------------------------------")
    }
}
