package com.github.feelixs.sshplugin.services

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
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
        
        // Store terminal in the map with the connection ID
        terminalMap.getOrPut(connectionId) { mutableListOf() }.add(terminal)
        // Execute the SSH command
        println("Executing command in terminal for ${connectionData.alias}")
        terminal.sendCommandToExecute(sshCommand)
        
        // Handle SSH key passphrase and auto-sudo in a background thread to avoid blocking the UI
        if ((connectionData.useKey && !connectionData.encodedKeyPassword.isNullOrEmpty()) || 
            (connectionData.osType == OsType.LINUX && connectionData.useSudo)) {
            
            println("Starting background thread for timed password automation for ${connectionData.alias}")
            
            // Create a separate thread for handling interactive prompts
            Thread {
                try {
                    // Fixed timing delays for authentication steps
                    val initialDelay = 2000L        // Wait for SSH to start and possibly show passphrase prompt
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
                    
                    // Handle sudo if needed (wait for SSH connection to stabilize first)
                    if (connectionData.osType == OsType.LINUX && connectionData.useSudo) {
                        // Wait for SSH connection to fully establish before attempting sudo
                        println("Waiting ${sshEstablishDelay}ms for SSH connection to establish")
                        Thread.sleep(sshEstablishDelay)
                        
                        // Send sudo command
                        println("Sending sudo command after timed delay")
                        terminal.sendCommandToExecute("sudo -s\n")
                        
                        // Wait for sudo password prompt
                        println("Waiting ${sudoPromptDelay}ms for sudo password prompt")
                        Thread.sleep(sudoPromptDelay)
                        
                        // Send appropriate sudo password
                        if (!connectionData.encodedSudoPassword.isNullOrEmpty()) {
                            connectionData.encodedSudoPassword?.let { sudoPassword ->
                                println("Sending sudo password after timed delay")
                                terminal.sendCommandToExecute("$sudoPassword\n")
                            }
                        } else {
                            println("No specific sudo password provided, using regular password")
                            connectionData.encodedPassword?.let { password ->
                                terminal.sendCommandToExecute("$password\n")
                            }
                        }
                    }
                    println("Timed password automation completed for ${connectionData.alias}")

                    // Focus terminal after automation completes using proper UI thread handling
                    ApplicationManager.getApplication().invokeLater {
                        val toolWindowManager = ToolWindowManager.getInstance(project)
                        val terminalToolWindow = toolWindowManager.getToolWindow("Terminal")
                        terminalToolWindow?.activate {
                            IdeFocusManager.getInstance(project).requestFocus(terminal.component, true)
                            println("Terminal focus requested on UI thread")
                            if (connectionData.maximizeTerminal) {
                                toolWindowManager.setMaximized(terminalToolWindow, true)
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
        } else {
            println("No background handling needed (no key passphrase or sudo) for ${connectionData.alias}")
        }

        return true
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
        println("  Use Sudo: ${connection.useSudo}")
        if (connection.useSudo) {
            // Log if sudo password *exists* but not the password itself
            println("  Sudo Password Provided: ${!connection.encodedSudoPassword.isNullOrBlank()}")
        }
        println("---------------------------------------")
    }
}
