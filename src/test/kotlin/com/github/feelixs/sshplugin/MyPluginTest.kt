package com.github.feelixs.sshplugin

import com.github.feelixs.sshplugin.model.SshConnectionData
import com.github.feelixs.sshplugin.services.SshConnectionStorageService
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PsiErrorElementUtil

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
        
        // Add a connection and verify it was added
        val connection = SshConnectionData(alias = "Test Connection", host = "localhost")
        service.addConnection(connection)
        
        assertEquals(initialSize + 1, service.getConnections().size)
        
        // Remove the connection and verify it was removed
        service.removeConnection(connection.id)
        assertEquals(initialSize, service.getConnections().size)
    }

    override fun getTestDataPath() = "src/test/testData/rename"
}
