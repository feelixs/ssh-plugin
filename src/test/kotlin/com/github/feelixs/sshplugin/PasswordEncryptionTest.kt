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
        
        // Wait briefly for encryption to complete
        Thread.sleep(500)
        
        // Get stored connection (passwords encrypted)
        val storedConnection = service.getConnections().find { it.id == testConnection.id }!!
        println("Stored password: ${storedConnection.encodedPassword}")
        assertNotEquals("Password should be encrypted", testPassword, storedConnection.encodedPassword)
        assertTrue("Password should be marked as encrypted", 
            storedConnection.encodedPassword?.startsWith("encrypted:") == true)
        
        // Decrypt and verify
        val decryptedConnection = service.getConnectionWithPlainPasswords(testConnection.id)!!
        assertEquals("Decrypted password should match original", 
            testPassword, decryptedConnection.encodedPassword)
        
        // Clean up
        service.removeConnection(testConnection.id)
    }
}
