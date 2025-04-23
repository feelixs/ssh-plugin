package com.github.feelixs.sshplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.github.feelixs.sshplugin.services.SSHCredentialService
import com.github.feelixs.sshplugin.ui.SSHCredentialDialog

class SSHConnectAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val credentialService = project.sshCredentialService
        
        SSHCredentialDialog(project, credentialService).show()
    }
}
