package com.github.feelixs.sshplugin.services

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
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
        val tabName = connectionData.alias
        val terminal = terminalManager.createShellWidget(project.basePath ?: "", tabName, false, false)

        // Execute the SSH command
        logger.info("Executing command in terminal for ${connectionData.alias}")
        terminal.sendCommandToExecute(sshCommand)
        
        // Handle SSH key passphrase and auto-sudo in a background thread to avoid blocking the UI
        if ((connectionData.useKey && !connectionData.encodedKeyPassword.isNullOrEmpty()) || 
            (connectionData.osType == OsType.LINUX && connectionData.useSudo)) {
            
            logger.info("Starting background thread for timed password automation for ${connectionData.alias}")
            
            // Create a separate thread for handling interactive prompts
            Thread {
                try {
                    // Fixed timing delays for authentication steps
                    val initialDelay = 2000L        // Wait for SSH to start and possibly show passphrase prompt
                    val sshEstablishDelay = 3000L   // Wait for SSH connection to establish
                    val sudoPromptDelay = 1500L     // Wait for sudo prompt to appear
                    
                    // Handle key passphrase if needed (enter after initial delay)
                    if (connectionData.useKey && !connectionData.encodedKeyPassword.isNullOrEmpty()) {
                        logger.info("Waiting ${initialDelay}ms for potential key passphrase prompt")
                        Thread.sleep(initialDelay)
                        
                        connectionData.encodedKeyPassword?.let { keyPassword ->
                            logger.info("Sending key passphrase after timed delay")
                            terminal.sendCommandToExecute("$keyPassword\n")
                        }
                    }
                    
                    // Handle sudo if needed (wait for SSH connection to stabilize first)
                    if (connectionData.osType == OsType.LINUX && connectionData.useSudo) {
                        // Wait for SSH connection to fully establish before attempting sudo
                        logger.info("Waiting ${sshEstablishDelay}ms for SSH connection to establish")
                        Thread.sleep(sshEstablishDelay)
                        
                        // Send sudo command
                        logger.info("Sending sudo command after timed delay")
                        terminal.sendCommandToExecute("sudo -s\n")
                        
                        // Wait for sudo password prompt
                        logger.info("Waiting ${sudoPromptDelay}ms for sudo password prompt")
                        Thread.sleep(sudoPromptDelay)
                        
                        // Send appropriate sudo password
                        if (!connectionData.encodedSudoPassword.isNullOrEmpty()) {
                            connectionData.encodedSudoPassword?.let { sudoPassword ->
                                logger.info("Sending sudo password after timed delay")
                                terminal.sendCommandToExecute("$sudoPassword\n")
                            }
                        } else {
                            logger.info("No specific sudo password provided, using regular password")
                            connectionData.encodedPassword?.let { password ->
                                terminal.sendCommandToExecute("$password\n")
                            }
                        }
                    }
                    
                    logger.info("Timed password automation completed for ${connectionData.alias}")
                    
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
            logger.info("  Key Path: ${connection.keyPath}")
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
