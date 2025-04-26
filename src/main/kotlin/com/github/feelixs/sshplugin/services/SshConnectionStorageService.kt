package com.github.feelixs.sshplugin.services

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection
import java.util.UUID

// Service responsible for persisting SSH connection configurations.
@State(
    name = "com.github.feelixs.sshplugin.services.SshConnectionStorageService",
    storages = [Storage("sshPluginConnections.xml")]
)
@Service(Service.Level.APP)
class SshConnectionStorageService : PersistentStateComponent<SshConnectionStorageService.State> {

    // Inner class to hold the state (list of connections)
    class State {
        @XCollection(style = XCollection.Style.v2)
        var connections: MutableList<SshConnectionData> = mutableListOf()
    }

    private var internalState = State()

    companion object {
        val instance: SshConnectionStorageService
            get() = ApplicationManager.getApplication().getService(SshConnectionStorageService::class.java)
        
        // Service name for credential store
        private const val CREDENTIAL_SERVICE_NAME = "SshPlugin"
        
        // Keys prefixes used for password storage
        private const val PASSWORD_KEY_PREFIX = "password:"
        private const val SUDO_PASSWORD_KEY_PREFIX = "sudo_password:"
        private const val KEY_PASSWORD_PREFIX = "key_password:"
        
        // Create credential attributes for a specific key
        private fun createCredentialAttributes(keyPrefix: String, connectionId: String): CredentialAttributes {
            return CredentialAttributes(
                generateServiceName(CREDENTIAL_SERVICE_NAME, keyPrefix + connectionId)
            )
        }
    }

    override fun getState(): State {
        // Don't encrypt passwords in getState() as it runs in a read action
        // Just return the state as-is - passwords should be encrypted when adding/updating
        return internalState
    }

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, internalState)
        // Decrypt passwords after loading state
        for (connection in internalState.connections) {
            decryptConnectionPasswords(connection)
        }
    }

    // --- Password encryption/decryption ---

    private fun encryptConnectionPasswords(connection: SshConnectionData) {
        // Run outside of read action to avoid SlowOperations error
        val app = ApplicationManager.getApplication()
        
        // If we're in a read action, use invokeLater to queue this operation
        if (app.isReadAccessAllowed) {
            app.invokeLater {
                encryptConnectionPasswordsImpl(connection)
            }
        } else {
            encryptConnectionPasswordsImpl(connection)
        }
    }
    
    // Helper method for tests to force synchronous encryption
    fun encryptConnectionForTest(connection: SshConnectionData) {
        encryptConnectionPasswordsImpl(connection)
    }
    
    private fun encryptConnectionPasswordsImpl(connection: SshConnectionData) {
        val passwordSafe = PasswordSafe.instance
        
        // Encrypt main password if present
        connection.encodedPassword?.takeIf { it.isNotBlank() }?.let { plainPassword ->
            val credentialAttributes = createCredentialAttributes(PASSWORD_KEY_PREFIX, connection.id)
            val credentials = Credentials(connection.username, plainPassword)
            passwordSafe.set(credentialAttributes, credentials)
            connection.encodedPassword = "encrypted:true"
        }

        // Encrypt sudo password if present and needed
        if (connection.osType == OsType.LINUX && connection.useSudo) {
            connection.encodedSudoPassword?.takeIf { it.isNotBlank() }?.let { plainSudoPassword ->
                val credentialAttributes = createCredentialAttributes(SUDO_PASSWORD_KEY_PREFIX, connection.id)
                val credentials = Credentials(connection.username, plainSudoPassword)
                passwordSafe.set(credentialAttributes, credentials)
                connection.encodedSudoPassword = "encrypted:true"
            }
        }

        // Encrypt key passphrase if present
        connection.encodedKeyPassword?.takeIf { it.isNotBlank() }?.let { plainKeyPassword ->
            val credentialAttributes = createCredentialAttributes(KEY_PASSWORD_PREFIX, connection.id)
            val credentials = Credentials(connection.username, plainKeyPassword)
            passwordSafe.set(credentialAttributes, credentials)
            connection.encodedKeyPassword = "encrypted:true"
        }
    }

    private fun decryptConnectionPasswords(connection: SshConnectionData) {
        // Run outside of read action to avoid SlowOperations error
        val app = ApplicationManager.getApplication()
        
        // If we're in a read action, use executeOnPooledThread
        if (app.isReadAccessAllowed) {
            app.executeOnPooledThread {
                decryptConnectionPasswordsImpl(connection)
            }.get() // wait for completion
        } else {
            decryptConnectionPasswordsImpl(connection)
        }
    }
    
    private fun decryptConnectionPasswordsImpl(connection: SshConnectionData) {
        val passwordSafe = PasswordSafe.instance
        
        // Decrypt main password if marked as encrypted
        if (connection.encodedPassword?.startsWith("encrypted:") == true) {
            val credentialAttributes = createCredentialAttributes(PASSWORD_KEY_PREFIX, connection.id)
            val credentials = passwordSafe.get(credentialAttributes)
            connection.encodedPassword = credentials?.getPasswordAsString() ?: ""
        }

        // Decrypt sudo password if marked as encrypted
        if (connection.encodedSudoPassword?.startsWith("encrypted:") == true) {
            val credentialAttributes = createCredentialAttributes(SUDO_PASSWORD_KEY_PREFIX, connection.id)
            val credentials = passwordSafe.get(credentialAttributes)
            connection.encodedSudoPassword = credentials?.getPasswordAsString() ?: ""
        }

        // Decrypt key passphrase if marked as encrypted
        if (connection.encodedKeyPassword?.startsWith("encrypted:") == true) {
            val credentialAttributes = createCredentialAttributes(KEY_PASSWORD_PREFIX, connection.id)
            val credentials = passwordSafe.get(credentialAttributes)
            connection.encodedKeyPassword = credentials?.getPasswordAsString() ?: ""
        }
    }

    // --- Public API for managing connections ---

    fun getConnections(): List<SshConnectionData> {
        return internalState.connections.toList() // Return immutable copy
    }

    fun addConnection(connection: SshConnectionData) {
        // Ensure we encrypt passwords when adding a connection
        encryptConnectionPasswords(connection)
        internalState.connections.add(connection)
    }

    fun updateConnection(connection: SshConnectionData) {
        val index = internalState.connections.indexOfFirst { it.id == connection.id }
        if (index != -1) {
            // Ensure we encrypt passwords when updating a connection
            encryptConnectionPasswords(connection)
            internalState.connections[index] = connection
        }
    }

    fun removeConnection(id: String) {
        // Clean up passwords from the password safe before removing the connection
        val connection = internalState.connections.find { it.id == id }
        if (connection != null) {
            val passwordSafe = PasswordSafe.instance
            
            // Remove the passwords
            val passwordCredentialAttributes = createCredentialAttributes(PASSWORD_KEY_PREFIX, id)
            passwordSafe.set(passwordCredentialAttributes, null)
            
            val keyPasswordCredentialAttributes = createCredentialAttributes(KEY_PASSWORD_PREFIX, id)
            passwordSafe.set(keyPasswordCredentialAttributes, null)
            
            val sudoPasswordCredentialAttributes = createCredentialAttributes(SUDO_PASSWORD_KEY_PREFIX, id)
            passwordSafe.set(sudoPasswordCredentialAttributes, null)
        }
        
        internalState.connections.removeIf { it.id == id }
    }
    
    // Method to get the actual passwords for use in SSH connections
    fun getConnectionWithPlainPasswords(id: String): SshConnectionData? {
        // Simplify the method to always operate in the current thread for test reliability
        val connection = internalState.connections.find { it.id == id } ?: return null
        
        // Make a copy to avoid modifying the stored connection
        val connectionCopy = connection.copy()
        decryptConnectionPasswords(connectionCopy)
        
        return connectionCopy
    }
    
    /**
     * Generates the SSH command for a connection based on its configuration.
     * @param id The ID of the connection
     * @return The SSH command string or null if the connection is not found
     */
    fun generateSshCommand(id: String): String? {
        // Simplify for test reliability
        val connection = getConnectionWithPlainPasswords(id) ?: return null
        
        val sshCommand = StringBuilder("ssh ")
        
        // Add port if not the default port 22
        if (connection.port != 22) {
            sshCommand.append("-p ${connection.port} ")
        }
        
        // Add key option if using SSH key authentication
        if (connection.useKey) {
            // Use the SSH key for authentication
            sshCommand.append("-i ${connection.keyPath} ")
        }
        
        // Add the username and host
        sshCommand.append("${connection.username}@${connection.host} -o StrictHostKeyChecking=no")
        
        // Note: Passwords are not included in the command as they would be handled by the SSH client
        // or through other secure methods like an agent or passphrase prompt
        
        return sshCommand.toString()
    }
}
