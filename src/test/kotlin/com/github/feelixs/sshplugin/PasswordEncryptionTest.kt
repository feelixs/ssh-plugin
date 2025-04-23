package com.github.feelixs.sshplugin

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.github.feelixs.sshplugin.services.SshConnectionStorageService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
        
        // Get stored connection (passwords encrypted)
        val storedConnection = service.getConnections().find { it.id == testConnection.id }!!
        assertNotEquals(testPassword, storedConnection.encodedPassword)
        
        // Decrypt and verify
        val decryptedConnection = service.getConnectionWithPlainPasswords(testConnection.id)!!
        assertEquals(testPassword, decryptedConnection.encodedPassword)
        
        // Clean up
        service.removeConnection(testConnection.id)
    }
}
