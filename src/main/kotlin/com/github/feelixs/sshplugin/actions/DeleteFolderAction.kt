package com.github.feelixs.sshplugin.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** Action to delete the selected folder. Enabled only when a folder is selected. */
class DeleteFolderAction : AnAction, DumbAware {

    constructor() : super(AllIcons.General.Remove)

    override fun actionPerformed(e: AnActionEvent) {
        val panel = e.dataContext.getData(PluginDataKeys.SSH_TOOL_WINDOW_PANEL)
        panel?.deleteFolder()
    }

    override fun update(e: AnActionEvent) {
        val panel = e.dataContext.getData(PluginDataKeys.SSH_TOOL_WINDOW_PANEL)
        val folder = e.dataContext.getData(PluginDataKeys.SELECTED_SSH_FOLDER)
        e.presentation.isEnabled = panel != null && folder != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
