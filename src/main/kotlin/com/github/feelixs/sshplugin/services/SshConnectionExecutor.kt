package com.github.feelixs.sshplugin.services

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.terminal.JBTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import org.jetbrains.plugins.terminal.ShellTerminalWidget

/**
 * Executes SSH connections in the IntelliJ terminal.
 */
class SshConnectionExecutor(private val project: Project) {

    private val logger = thisLogger()

    /**
     * Open a terminal tab and execute the SSH command for the given connection.
     * 
     * @param connectionId The ID of the SSH connection to use
     * @return true if the connection was initiated successfully, false otherwise
     */
    fun executeConnection(connectionId: String): Boolean {
        logger.info("Attempting to execute connection with ID: $connectionId")
        // Get connection data with decrypted passwords
        val connectionData = SshConnectionStorageService.instance.getConnectionWithPlainPasswords(connectionId)
        if (connectionData == null) {
            logger.warn("Connection data not found for ID: $connectionId")
            return false
        }
        
        // Ensure we have properly decrypted passwords
        logger.info("Verifying connection passwords are properly decrypted")

        // Log connection details (masking sensitive info)
        logConnectionDetails(connectionData)

        // Generate the SSH command
        val sshCommand = SshConnectionStorageService.instance.generateSshCommand(connectionId)
        if (sshCommand == null) {
            logger.error("Failed to generate SSH command for connection ID: $connectionId")
            return false
        }
        logger.info("Generated SSH command for ${connectionData.alias}: $sshCommand")

        // Create a terminal tab for the connection
        val terminalManager = TerminalToolWindowManager.getInstance(project)

        // Create a terminal tab with the SSH connection name
        val tabName = connectionData.alias
        val terminal = terminalManager.createLocalShellWidget(null, tabName) as? ShellTerminalWidget
        
        if (terminal == null) {
            logger.error("Failed to create terminal widget for connection ${connectionData.alias}")
            return false
        }

        // Execute the SSH command
        logger.info("Executing command in terminal for ${connectionData.alias}")
        terminal.executeCommand(sshCommand)
        
        // Handle SSH key passphrase and auto-sudo in a background thread to avoid blocking the UI
        if ((connectionData.useKey && !connectionData.encodedKeyPassword.isNullOrEmpty()) || 
            (connectionData.osType == OsType.LINUX && connectionData.useSudo)) {
            
            logger.info("Starting background thread for potential passphrase/sudo handling for ${connectionData.alias}")
            // Create a separate thread for handling interactive prompts
            Thread {
                try {
                    // When using SSH key, we may need to handle the passphrase prompt
                    if (connectionData.useKey && !connectionData.encodedKeyPassword.isNullOrEmpty()) {
                        logger.info("SSH key requires passphrase for ${connectionData.alias}. Waiting for prompt...")
                        Thread.sleep(3000)

                        // Send the passphrase to the terminal, followed by a newline
                        connectionData.encodedKeyPassword?.let { keyPassword ->
                            logger.info("Sending key passphrase for ${connectionData.alias}")
                            // Use password already in connectionData - no need to re-decrypt
                            terminal.executeCommand("$keyPassword\n") // Append newline
                        }

                        // Wait for connection to establish after sending passphrase
                        logger.info("Waiting for connection to establish after passphrase for ${connectionData.alias}")
                        Thread.sleep(2000) // Adjust timing if needed
                    } else {
                        // For password auth or key without passphrase, wait for the connection itself
                        logger.info("Waiting for potential connection establishment for ${connectionData.alias}")
                        Thread.sleep(2500) // Adjust timing if needed
                    }

                    // If auto-sudo is enabled for Linux servers, run sudo command after connection
                    if (connectionData.osType == OsType.LINUX && connectionData.useSudo) {
                        logger.info("Attempting sudo elevation for ${connectionData.alias}")
                        // Send the sudo command to elevate privileges
                        terminal.executeCommand("sudo -s")

                        // If a specific sudo password was provided, enter it after a short wait
                        if (!connectionData.encodedSudoPassword.isNullOrEmpty()) {
                            logger.info("Sudo password provided for ${connectionData.alias}. Waiting for prompt...")
                            // Wait for sudo password prompt
                            Thread.sleep(1000) // Adjust timing if needed

                            connectionData.encodedSudoPassword?.let { sudoPassword ->
                                logger.info("Sending sudo password for ${connectionData.alias}")
                                // Use password already in connectionData - no need to re-decrypt
                                terminal.executeCommand("$sudoPassword\n") // Append newline
                                logger.debug("Sudo password sent for ${connectionData.alias}")
                            }
                        } else {
                            logger.info("No sudo password provided for ${connectionData.alias}. Manual entry might be required.")
                        }
                    }
                    logger.info("Background handling thread finished for ${connectionData.alias}")
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt() // Restore interrupt status
                    logger.warn("Background handling thread interrupted for ${connectionData.alias}", ie)
                } catch (e: Exception) {
                    // Log any errors that occur during the async process
                    logger.error("Error in background handling thread for ${connectionData.alias}", e)
                }
            }.start()
        } else {
            logger.info("No background handling needed (no key passphrase or sudo) for ${connectionData.alias}")
        }

        return true
    }

    private fun logConnectionDetails(connection: SshConnectionData) {
        logger.info("--- Connection Details (${connection.alias}) ---")
        logger.info("  ID: ${connection.id}")
        logger.info("  Host: ${connection.host}")
        logger.info("  Port: ${connection.port}")
        logger.info("  Username: ${connection.username}")
        logger.info("  OS Type: ${connection.osType}")
        logger.info("  Use Key: ${connection.useKey}")
        if (connection.useKey) {
            logger.info("  Key Path: ${connection.keyPath ?: "Not set"}")
            // Log if passphrase *exists* but not the passphrase itself
            logger.info("  Key Passphrase Provided: ${!connection.encodedKeyPassword.isNullOrBlank()}")
        } else {
            // Log if password *exists* but not the password itself
            logger.info("  Password Provided: ${!connection.encodedPassword.isNullOrBlank()}")
        }
        logger.info("  Use Sudo: ${connection.useSudo}")
        if (connection.useSudo) {
            // Log if sudo password *exists* but not the password itself
            logger.info("  Sudo Password Provided: ${!connection.encodedSudoPassword.isNullOrBlank()}")
        }
        logger.info("---------------------------------------")
    }
}
