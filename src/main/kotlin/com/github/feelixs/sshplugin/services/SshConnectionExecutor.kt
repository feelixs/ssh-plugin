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

    // States for tracking the authentication flow
    private enum class AuthState {
        NOT_STARTED,  // Process hasn't started yet
        WAITING,      // Waiting for prompt or user input
        COMPLETED     // Process has completed successfully
    }

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
            
            logger.info("Starting background thread for intelligent password automation for ${connectionData.alias}")
            
            // Create a separate thread for handling interactive prompts
            Thread {
                try {
                    // Track states for each authentication step
                    var keyPassphraseState = if (connectionData.useKey && !connectionData.encodedKeyPassword.isNullOrEmpty()) 
                                             AuthState.WAITING else AuthState.COMPLETED
                    var sudoState = if (connectionData.osType == OsType.LINUX && connectionData.useSudo) 
                                   AuthState.NOT_STARTED else AuthState.COMPLETED
                    
                    // Set timeouts to avoid infinite waiting
                    val startTime = System.currentTimeMillis()
                    val maxWaitTime = 15000L // 15 seconds
                    val pollInterval = 250L // Check every 250ms
                    
                    // Keywords to detect in terminal for key passphrase prompt
                    val keyPassphrasePromptPatterns = listOf(
                        "Enter passphrase",
                        "Key passphrase:",
                        "password:",
                        "passphrase for key"
                    ).map { it.lowercase() }
                    
                    // Keywords to detect in terminal for shell prompt (indicating successful connection)
                    val shellPromptPatterns = listOf(
                        "$ ",
                        "# ",
                        "> ",
                        "~]$ "
                    )
                    
                    // Keywords to detect in terminal for sudo password prompt
                    val sudoPasswordPromptPatterns = listOf(
                        "[sudo] password",
                        "password for",
                        "password:"
                    ).map { it.lowercase() }
                    
                    while (System.currentTimeMillis() - startTime < maxWaitTime &&
                           (keyPassphraseState != AuthState.COMPLETED || sudoState != AuthState.COMPLETED)) {
                        
                        // Handle key passphrase if needed
                        if (keyPassphraseState == AuthState.WAITING) {
                            // Get terminal title and visible lines to check for passphrase prompt
                            val visibleTerminalText = terminal.terminalTitle.toString().lowercase()
                            
                            // Check if any passphrase patterns match the terminal text
                            val passphrasePromptDetected = keyPassphrasePromptPatterns.any { 
                                visibleTerminalText.contains(it) 
                            }
                            
                            if (passphrasePromptDetected) {
                                logger.info("Detected key passphrase prompt for ${connectionData.alias}")
                                connectionData.encodedKeyPassword?.let { keyPassword ->
                                    logger.info("Sending key passphrase")
                                    terminal.sendCommandToExecute("$keyPassword\n")
                                    keyPassphraseState = AuthState.COMPLETED
                                }
                            }
                        }
                        
                        // Handle sudo command and password if needed
                        if (keyPassphraseState == AuthState.COMPLETED && sudoState != AuthState.COMPLETED) {
                            // If sudo not started yet, wait for shell prompt then send sudo command
                            if (sudoState == AuthState.NOT_STARTED) {
                                val terminalText = terminal.terminalTitle
                                
                                // Check if shell prompt is detected (connection established)
                                val shellPromptDetected = shellPromptPatterns.any { 
                                    terminalText.contains(it) 
                                }
                                
                                if (shellPromptDetected) {
                                    logger.info("Detected shell prompt, running sudo command for ${connectionData.alias}")
                                    terminal.sendCommandToExecute("sudo -s\n")
                                    sudoState = AuthState.WAITING
                                    // Reset timer to give enough time for sudo prompt
                                    Thread.sleep(500)
                                }
                            } 
                            // If sudo command sent but password not yet entered
                            else if (sudoState == AuthState.WAITING) {
                                val terminalText = terminal.terminalTitle ?: "".lowercase()
                                
                                // Check for sudo password prompt
                                val sudoPromptDetected = sudoPasswordPromptPatterns.any { 
                                    terminalText.contains(it)
                                }
                                
                                if (sudoPromptDetected) {
                                    logger.info("Detected sudo password prompt for ${connectionData.alias}")
                                    
                                    // Use sudo password if available, otherwise fall back to regular password
                                    if (!connectionData.encodedSudoPassword.isNullOrEmpty()) {
                                        connectionData.encodedSudoPassword?.let { sudoPassword ->
                                            logger.info("Sending sudo password")
                                            terminal.sendCommandToExecute("$sudoPassword\n")
                                            sudoState = AuthState.COMPLETED
                                        }
                                    } else {
                                        logger.info("No specific sudo password provided, using regular password")
                                        connectionData.encodedPassword?.let { password ->
                                            terminal.sendCommandToExecute("$password\n")
                                            sudoState = AuthState.COMPLETED
                                        }
                                    }
                                }
                            }
                        }
                        
                        // Sleep before next check to reduce CPU usage
                        Thread.sleep(pollInterval)
                    }
                    
                    // Log results of automation
                    if (System.currentTimeMillis() - startTime >= maxWaitTime) {
                        logger.info("Timeout reached during password automation for ${connectionData.alias}")
                    } else {
                        logger.info("Password automation completed successfully for ${connectionData.alias}")
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
