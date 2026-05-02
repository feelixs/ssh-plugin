package com.github.feelixs.sshplugin.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** Action to create a new folder in the SSH tool window. */
class NewFolderAction : AnAction, DumbAware {

    constructor() : super(AllIcons.Nodes.Folder)

    override fun actionPerformed(e: AnActionEvent) {
        val panel = e.dataContext.getData(PluginDataKeys.SSH_TOOL_WINDOW_PANEL)
        panel?.newFolder()
    }

    override fun update(e: AnActionEvent) {
        val panel = e.dataContext.getData(PluginDataKeys.SSH_TOOL_WINDOW_PANEL)
        e.presentation.isEnabled = panel != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
