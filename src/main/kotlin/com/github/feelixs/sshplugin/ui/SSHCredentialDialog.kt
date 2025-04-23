package com.github.feelixs.sshplugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.github.feelixs.sshplugin.services.SSHCredentialService
import javax.swing.JComponent

class SSHCredentialDialog(
    private val project: Project,
    private val credentialService: SSHCredentialService
) : DialogWrapper(project) {

    private val aliasField = JBTextField()
    private val keyPathField = JBTextField()
    private val passwordField = JBTextField()
    private val hostField = JBTextField()
    private val usernameField = JBTextField()

    init {
        title = "Add SSH Credential"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Alias:") { cell(aliasField) }
            row("Key Path:") { cell(keyPathField) }
            row("Password:") { cell(passwordField) }
            row("Host:") { cell(hostField) }
            row("Username:") { cell(usernameField) }
        }
    }

    override fun doOKAction() {
        credentialService.addCredential(
            alias = aliasField.text,
            keyPath = keyPathField.text,
            password = passwordField.text,
            serverInfo = SSHCredentialService.ServerInfo(
                host = hostField.text,
                username = usernameField.text
            )
        )
        super.doOKAction()
    }
}
