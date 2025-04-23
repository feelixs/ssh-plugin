package com.github.feelixs.sshplugin.services

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
        
        // Create credential attributes for a specific key
        private fun createCredentialAttributes(keyPrefix: String, connectionId: String): CredentialAttributes {
            return CredentialAttributes(
                generateServiceName(CREDENTIAL_SERVICE_NAME, keyPrefix + connectionId)
            )
        }
    }

    override fun getState(): State {
        // Encrypt passwords before persisting state
        for (connection in internalState.connections) {
            encryptConnectionPasswords(connection)
        }
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
        // Store the actual passwords in the password safe
        val passwordSafe = PasswordSafe.instance
        
        // Store current plaintext password in the password safe (if any)
        connection.encodedPassword?.let { plainPassword ->
            val credentialAttributes = createCredentialAttributes(PASSWORD_KEY_PREFIX, connection.id)
            val credentials = Credentials("", plainPassword)
            passwordSafe.set(credentialAttributes, credentials)
            
            // Clear the plaintext password and set a marker
            connection.encodedPassword = UUID.randomUUID().toString()
        }
        
        // Store current plaintext sudo password in the password safe (if any)
        connection.encodedSudoPassword?.let { plainSudoPassword ->
            val credentialAttributes = createCredentialAttributes(SUDO_PASSWORD_KEY_PREFIX, connection.id)
            val credentials = Credentials("", plainSudoPassword)
            passwordSafe.set(credentialAttributes, credentials)
            
            // Clear the plaintext password and set a marker
            connection.encodedSudoPassword = UUID.randomUUID().toString()
        }
    }

    private fun decryptConnectionPasswords(connection: SshConnectionData) {
        val passwordSafe = PasswordSafe.instance
        
        // Retrieve the actual password from the password safe (if any)
        if (connection.encodedPassword != null) {
            val credentialAttributes = createCredentialAttributes(PASSWORD_KEY_PREFIX, connection.id)
            val credentials = passwordSafe.get(credentialAttributes)
            connection.encodedPassword = credentials?.getPasswordAsString()
        }
        
        // Retrieve the actual sudo password from the password safe (if any)
        if (connection.encodedSudoPassword != null) {
            val credentialAttributes = createCredentialAttributes(SUDO_PASSWORD_KEY_PREFIX, connection.id)
            val credentials = passwordSafe.get(credentialAttributes)
            connection.encodedSudoPassword = credentials?.getPasswordAsString()
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
            
            val sudoPasswordCredentialAttributes = createCredentialAttributes(SUDO_PASSWORD_KEY_PREFIX, id)
            passwordSafe.set(sudoPasswordCredentialAttributes, null)
        }
        
        internalState.connections.removeIf { it.id == id }
    }
    
    // Method to get the actual passwords for use in SSH connections
    fun getConnectionWithPlainPasswords(id: String): SshConnectionData? {
        val connection = internalState.connections.find { it.id == id } ?: return null
        
        // Make a copy to avoid modifying the stored connection
        val connectionCopy = connection.copy()
        decryptConnectionPasswords(connectionCopy)
        
        return connectionCopy
    }
}