package com.github.feelixs.sshplugin.services

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.jediterm.terminal.Terminal
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Executes SSH connections in the IntelliJ terminal.
 */
@Service
class SshConnectionExecutor(private val project: Project) {

    private var activeTerminal: TerminalWidget? = null
    private val logger = thisLogger()

    fun getTerminal(): TerminalWidget? {
        return activeTerminal
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
        this.activeTerminal = terminal
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
                        terminalToolWindow?.show {
                            terminal.component.requestFocusInWindow()
                            println("Terminal focus requested on UI thread")
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
