package com.github.feelixs.sshplugin

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.github.feelixs.sshplugin.services.SshConnectionStorageService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import org.junit.Assert.*

class PasswordEncryptionTest : BasePlatformTestCase() {

    @Test
    fun testPasswordEncryptionRoundtrip() {
        val service = SshConnectionStorageService.instance
        val testPassword = "mySecretPassword123!"
        
        // Create minimal test connection
        val testConnection = SshConnectionData(
            id = "encryption-test",
            alias = "Test",
            host = "test.com",
            port = 22,
            username = "user",
            encodedPassword = testPassword,
            osType = OsType.LINUX
        )

        // Add connection (triggers encryption)
        service.addConnection(testConnection)
        
        // Force synchronous encryption for testing
        // This modifies the connection that was just added to ensure it's encrypted
        val connectionToForceEncrypt = service.getConnections().find { it.id == testConnection.id }!!
        service.encryptConnectionForTest(connectionToForceEncrypt)
        
        // Get stored connection (should now be encrypted)
        val storedConnection = service.getConnections().find { it.id == testConnection.id }
        
        // Get stored connection (passwords encrypted)
        assertNotNull("Connection should be stored", storedConnection)
        val actualPassword = storedConnection?.encodedPassword
        println("Stored password: '${actualPassword}'")
        println("Original password: '${testPassword}'")
        
        assertNotEquals("Password should be encrypted", testPassword, actualPassword)
        assertTrue("Password should be marked as encrypted. Got: '$actualPassword'", 
            actualPassword == "encrypted:true")
        
        // Decrypt and verify
        val decryptedConnection = service.getConnectionWithPlainPasswords(testConnection.id)!!
        assertEquals("Decrypted password should match original", 
            testPassword, decryptedConnection.encodedPassword)
        
        // Clean up
        service.removeConnection(testConnection.id)
    }
}
