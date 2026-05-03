package com.github.feelixs.sshplugin.actions

import com.github.feelixs.sshplugin.model.SshConnectionData
import com.github.feelixs.sshplugin.model.SshFolder
import com.github.feelixs.sshplugin.toolWindow.SshToolWindowPanel
import com.intellij.openapi.actionSystem.DataKey

object PluginDataKeys {
    val SSH_TOOL_WINDOW_PANEL = DataKey.create<SshToolWindowPanel>("SshToolWindowPanel")
    val SELECTED_SSH_CONNECTION = DataKey.create<SshConnectionData>("SelectedSshConnection")
    val SELECTED_SSH_FOLDER = DataKey.create<SshFolder>("SelectedSshFolder")
    val SELECTED_SSH_CONNECTION_SUDO_PASSWORD = DataKey.create<String>("SelectedSshConnectionSudoPassword")
}
