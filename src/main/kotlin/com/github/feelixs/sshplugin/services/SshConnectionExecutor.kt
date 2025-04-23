package com.github.feelixs.sshplugin.services

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import org.jetbrains.plugins.terminal.ShellTerminalWidget

/**
 * Executes SSH connections in the IntelliJ terminal.
 */
class SshConnectionExecutor(private val project: Project) {

    /**
     * Open a terminal tab and execute the SSH command for the given connection.
     * 
     * @param connectionId The ID of the SSH connection to use
     * @return true if the connection was initiated successfully, false otherwise
     */
    fun executeConnection(connectionId: String): Boolean {
        // Get connection data with decrypted passwords
        val connectionData = SshConnectionStorageService.instance
            .getConnectionWithPlainPasswords(connectionId) ?: return false
        
        // Generate the SSH command
        val sshCommand = SshConnectionStorageService.instance.generateSshCommand(connectionId) ?: return false
        
        // Create a terminal tab for the connection
        val terminalManager = TerminalToolWindowManager.getInstance(project)
        
        // Create a terminal widget with the SSH connection name
        val tabName = "SSH: ${connectionData.alias}"
        val terminalWidget = terminalManager.createLocalShellWidget(null, tabName) as? ShellTerminalWidget
            ?: return false
        
        // Execute the SSH command
        terminalWidget.executeCommand(sshCommand)
        
        // Handle SSH key passphrase and auto-sudo in a background thread to avoid blocking the UI
        if ((connectionData.useKey && !connectionData.encodedKeyPassword.isNullOrEmpty()) || 
            (connectionData.osType == OsType.LINUX && connectionData.useSudo)) {
            
            // Create a separate thread for handling interactive prompts
            Thread {
                try {
                    // When using SSH key, we may need to handle the passphrase prompt
                    if (connectionData.useKey && !connectionData.encodedKeyPassword.isNullOrEmpty()) {
                        // Wait a moment for the "Enter passphrase for key" prompt to appear
                        Thread.sleep(1000)
                        
                        // Send the passphrase to the terminal
                        connectionData.encodedKeyPassword?.let { keyPassword ->
                            terminalWidget.executeCommand(keyPassword)
                        }
                        
                        // Wait for connection to establish after sending passphrase
                        Thread.sleep(2000)
                    } else {
                        // For password auth, wait longer for the connection to establish
                        Thread.sleep(2500)
                    }
                    
                    // If auto-sudo is enabled for Linux servers, run sudo command after connection
                    if (connectionData.osType == OsType.LINUX && connectionData.useSudo) {
                        // Send the sudo command to elevate privileges
                        terminalWidget.executeCommand("sudo -s")
                        
                        // If a specific sudo password was provided, enter it after a short wait
                        if (!connectionData.encodedSudoPassword.isNullOrEmpty()) {
                            // Wait for sudo password prompt
                            Thread.sleep(1000)
                            
                            connectionData.encodedSudoPassword?.let { sudoPassword ->
                                terminalWidget.executeCommand(sudoPassword)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Log any errors that occur during the async process
                    e.printStackTrace()
                }
            }.start()
        }
        
        return true
    }
}