package com.github.feelixs.sshplugin.actions

import com.github.feelixs.sshplugin.model.SshConnectionData
import com.github.feelixs.sshplugin.toolWindow.SshToolWindowPanel
import com.intellij.openapi.actionSystem.DataKey

// Defines custom data keys for accessing plugin components via DataContext.
object PluginDataKeys {
    // Data key to access the SshToolWindowPanel instance.
    val SSH_TOOL_WINDOW_PANEL = DataKey.create<SshToolWindowPanel>("SshToolWindowPanel")
    // Data key to access the currently selected SshConnectionData in the list.
    val SELECTED_SSH_CONNECTION = DataKey.create<SshConnectionData>("SelectedSshConnection")
    // Data key to access the currently selected SshConnectionData's sudo password.
    val SELECTED_SSH_CONNECTION_SUDO_PASSWORD = DataKey.create<String>("SelectedSshConnectionSudoPassword")
}