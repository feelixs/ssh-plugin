package com.github.feelixs.sshplugin.toolWindow

import com.github.feelixs.sshplugin.actions.PluginDataKeys
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.github.feelixs.sshplugin.services.SshConnectionStorageService
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import javax.swing.DefaultListModel
import javax.swing.ListSelectionModel

// Represents the main panel content for the SSH Connections tool window.
// Implements DataProvider to supply context to actions.
class SshToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true), DataProvider {

    private val connectionStorageService = SshConnectionStorageService.instance
    private val listModel = DefaultListModel<SshConnectionData>()
    private val connectionList = JBList(listModel)

    init {
        setupUI()
        loadConnections()
    }

    private fun setupUI() {
        // Toolbar setup
        val actionManager = ActionManager.getInstance()
        // Retrieve the action group defined in plugin.xml
        val actionGroup = actionManager.getAction("SSHPlugin.ToolWindow.Toolbar") as? DefaultActionGroup
            ?: DefaultActionGroup() // Fallback to empty group if not found

        val toolbar = actionManager.createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, actionGroup, true)
        toolbar.targetComponent = this // Set target component for context, important for DataContext
        setToolbar(toolbar.component)

        // List setup
        connectionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        // TODO: Add a cell renderer for better display (e.g., show alias)
        // TODO: Add list selection listener to enable/disable Edit/Delete/Connect actions

        val scrollPane = JBScrollPane(connectionList)
        setContent(scrollPane)

        // Add listener to update action states on selection change
        connectionList.addListSelectionListener {
            // Request an update of the actions' states in the toolbar
            ActionManager.getInstance().getAction("SSHPlugin.ToolWindow.Toolbar")?.let { group ->
                // This is a bit indirect, ideally update specific actions if possible
                // For now, triggering an update on the group might refresh contained actions
                // A more robust way might involve specific update calls if needed.
            }
        }
    }

    private fun loadConnections() {
        listModel.clear()
        val connections = connectionStorageService.getConnections()
        connections.forEach { listModel.addElement(it) }
    }

    // --- Public methods for actions ---

    fun addConnection() {
        // TODO: Implement logic to show Add dialog
        println("Add Connection action triggered")
        // After adding, reload: loadConnections()
    }

    fun editConnection() {
        val selectedConnection = connectionList.selectedValue
        if (selectedConnection != null) {
            // TODO: Implement logic to show Edit dialog with selectedConnection data
            println("Edit Connection action triggered for: ${selectedConnection.alias}")
            // After editing, reload: loadConnections()
        }
    }

    fun deleteConnection() {
        val selectedConnection = connectionList.selectedValue
        if (selectedConnection != null) {
            // TODO: Add confirmation dialog
            connectionStorageService.removeConnection(selectedConnection.id)
            println("Delete Connection action triggered for: ${selectedConnection.alias}")
            loadConnections() // Reload list
        }
    }

    fun connect() {
        val selectedConnection = connectionList.selectedValue
        if (selectedConnection != null) {
            // TODO: Implement connection logic (using SshInitiator or similar)
            println("Connect action triggered for: ${selectedConnection.alias}")
        }
    }

    // --- DataProvider Implementation ---

    override fun getData(dataId: String): Any? {
        return when {
            // Provide the panel instance itself
            PluginDataKeys.SSH_TOOL_WINDOW_PANEL.`is`(dataId) -> this
            // Provide the currently selected connection
            PluginDataKeys.SELECTED_SSH_CONNECTION.`is`(dataId) -> connectionList.selectedValue
            // Delegate to default implementation for other keys
            else -> super.getData(dataId)
        }
    }
}
