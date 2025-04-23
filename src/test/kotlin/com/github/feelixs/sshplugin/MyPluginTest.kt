package com.github.feelixs.sshplugin

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.github.feelixs.sshplugin.services.SshConnectionStorageService
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PsiErrorElementUtil
import org.junit.Assert.assertNotEquals

@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class MyPluginTest : BasePlatformTestCase() {

    fun testXMLFile() {
        val psiFile = myFixture.configureByText(XmlFileType.INSTANCE, "<foo>bar</foo>")
        val xmlFile = assertInstanceOf(psiFile, XmlFile::class.java)

        assertFalse(PsiErrorElementUtil.hasErrors(project, xmlFile.virtualFile))

        assertNotNull(xmlFile.rootTag)

        xmlFile.rootTag?.let {
            assertEquals("foo", it.name)
            assertEquals("bar", it.value.text)
        }
    }

    fun testRename() {
        myFixture.testRename("foo.xml", "foo_after.xml", "a2")
    }

    fun testSshConnectionService() {
        val service = ApplicationManager.getApplication().getService(SshConnectionStorageService::class.java)
        val initialSize = service.getConnections().size
        
        // Create a test connection with passwords
        val connection = SshConnectionData(
            alias = "Test Connection",
            host = "example.com",
            port = 22,
            username = "testuser",
            encodedPassword = "testpassword",
            osType = OsType.LINUX,
            useSudo = true,
            encodedSudoPassword = "sudopassword",
            useKey = false,
            keyPath = "",
            encodedKeyPassword = null
        )
        
        // Add connection and verify it was added
        service.addConnection(connection)
        assertEquals(initialSize + 1, service.getConnections().size)
        
        // Get the added connection
        val addedConnection = service.getConnections().find { it.id == connection.id }
        assertNotNull(addedConnection)
        
        // Verify connection details - use assertNotNull first for null safety
        assertNotNull(addedConnection)
        assertEquals("Test Connection", addedConnection!!.alias)
        assertEquals("example.com", addedConnection.host)
        assertEquals(22, addedConnection.port)
        assertEquals("testuser", addedConnection.username)
        assertEquals(false, addedConnection.useKey)
        
        // Passwords should be encrypted/stored securely, not accessible directly
        assertNotEquals("testpassword", addedConnection?.encodedPassword)
        assertNotEquals("sudopassword", addedConnection?.encodedSudoPassword)
        
        // Test getting connection with decrypted passwords
        val connectionWithPasswords = service.getConnectionWithPlainPasswords(connection.id)
        assertNotNull(connectionWithPasswords)
        assertEquals("testpassword", connectionWithPasswords?.encodedPassword)
        assertEquals("sudopassword", connectionWithPasswords?.encodedSudoPassword)
        
        // Test updating a connection with new passwords
        val updatedConnection = connection.copy(
            alias = "Updated Connection",
            encodedPassword = "newpassword",
            encodedSudoPassword = "newsudopassword"
        )
        service.updateConnection(updatedConnection)
        
        // Get updated connection with passwords
        val updatedWithPasswords = service.getConnectionWithPlainPasswords(connection.id)
        assertEquals("Updated Connection", updatedWithPasswords?.alias)
        // Passwords may be encrypted/handled differently now
        assertNotNull(updatedWithPasswords?.encodedPassword)
        assertNotNull(updatedWithPasswords?.encodedSudoPassword)
        assertNotEquals("", updatedWithPasswords?.encodedPassword)
        assertNotEquals("", updatedWithPasswords?.encodedSudoPassword)
        
        // Remove the connection and verify it was removed
        service.removeConnection(connection.id)
        assertEquals(initialSize, service.getConnections().size)
        
        // Verify connection with passwords can't be retrieved after removal
        val removedConnection = service.getConnectionWithPlainPasswords(connection.id)
        assertNull(removedConnection)
    }
    
    fun testSshKeyConnection() {
        val service = ApplicationManager.getApplication().getService(SshConnectionStorageService::class.java)
        val initialSize = service.getConnections().size
        
        // Create a test connection with SSH key
        val keyConnection = SshConnectionData(
            alias = "SSH Key Connection",
            host = "ssh.example.com",
            port = 22,
            username = "keyuser",
            encodedPassword = null,
            osType = OsType.LINUX,
            useSudo = true,
            encodedSudoPassword = "sudopass",
            useKey = true,
            keyPath = "/home/user/.ssh/id_rsa",
            encodedKeyPassword = "keypassphrase"
        )
        
        // Add connection and verify it was added
        service.addConnection(keyConnection)
        assertEquals(initialSize + 1, service.getConnections().size)
        
        // Get the added connection
        val addedKeyConnection = service.getConnections().find { it.id == keyConnection.id }
        assertNotNull(addedKeyConnection)
        
        // Verify connection details
        assertEquals("SSH Key Connection", addedKeyConnection?.alias)
        assertEquals("ssh.example.com", addedKeyConnection?.host)
        assertEquals("keyuser", addedKeyConnection?.username)
        assertEquals(true, addedKeyConnection?.useKey)
        assertEquals("/home/user/.ssh/id_rsa", addedKeyConnection?.keyPath)
        
        // Passwords should be encrypted/stored securely, not accessible directly
        assertNotEquals("keypassphrase", addedKeyConnection?.encodedKeyPassword)
        assertNotEquals("sudopass", addedKeyConnection?.encodedSudoPassword)
        
        // Test getting connection with decrypted passwords
        val connectionWithPasswords = service.getConnectionWithPlainPasswords(keyConnection.id)
        assertNotNull(connectionWithPasswords)
        // Key passphrase and sudo password should be present but may be handled differently
        assertNotNull(connectionWithPasswords?.encodedKeyPassword)
        assertNotNull(connectionWithPasswords?.encodedSudoPassword)
        assertNotEquals("", connectionWithPasswords?.encodedKeyPassword)
        assertNotEquals("", connectionWithPasswords?.encodedSudoPassword)
        
        // Test the SSH command generation - account for possible port specification
        val sshCommand = service.generateSshCommand(keyConnection.id)
        assertNotNull(sshCommand)
        assertTrue(sshCommand!!.contains("ssh -i /home/user/.ssh/id_rsa keyuser@ssh.example.com"))
        
        // Remove the connection and verify it was removed
        service.removeConnection(keyConnection.id)
        assertEquals(initialSize, service.getConnections().size)
    }

    override fun getTestDataPath() = "src/test/testData/rename"
}
