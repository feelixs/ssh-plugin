package com.github.feelixs.sshplugin.toolWindow

import com.github.feelixs.sshplugin.actions.PluginDataKeys
import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.github.feelixs.sshplugin.services.SshConnectionExecutor
import com.github.feelixs.sshplugin.services.SshConnectionStorageService
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import java.util.Timer
import java.util.TimerTask
import javax.swing.DefaultListModel
import javax.swing.ListSelectionModel

// Represents the main panel content for the SSH Connections tool window.
// Implements DataProvider to supply context to actions.
class SshToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true), DataProvider, Disposable {

    private val connectionStorageService = SshConnectionStorageService.instance
    private val listModel = DefaultListModel<SshConnectionData>()
    private val connectionList = JBList(listModel)
    
    // Timer to refresh connection list status
    private val refreshTimer = Timer("SSH-Connection-Status-Timer", true)
    
    // Update queue to avoid too many UI refreshes
    private val updateQueue = MergingUpdateQueue(
        "SshConnectionListRefresh",
        300, // merge updates within 300ms
        true,
        null,
        this,
        null,
        false
    )

    init {
        setupUI()
        loadConnections()
        startStatusRefreshTimer()
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
        connectionList.cellRenderer = SshConnectionListCellRenderer() // Custom renderer to display connection info

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
        val dialog = SshConnectionDialog(project)
        if (dialog.showAndGet()) {
            // User clicked OK - get the connection data and save it
            val newConnection = dialog.createConnectionData()
            connectionStorageService.addConnection(newConnection)
            println("Added new connection: ${newConnection.alias}")
            loadConnections() // Reload the list to show the new connection
        } else {
            println("Add connection cancelled")
        }
    }

    fun editConnection() {
        val selectedConnection = connectionList.selectedValue
        if (selectedConnection != null) {
            // Get a copy with decrypted passwords for editing
            val connectionWithPasswords = connectionStorageService.getConnectionWithPlainPasswords(selectedConnection.id)
            if (connectionWithPasswords != null) {
                val dialog = SshConnectionDialog(project, connectionWithPasswords)
                if (dialog.showAndGet()) {
                    // User clicked OK - get the updated connection data and save it
                    val updatedConnection = dialog.createConnectionData()
                    connectionStorageService.updateConnection(updatedConnection)
                    println("Updated connection: ${updatedConnection.alias}")
                    loadConnections() // Reload the list to show the updated connection
                } else {
                    println("Edit connection cancelled for: ${selectedConnection.alias}")
                }
            }
        }
    }

    fun deleteConnection() {
        val selectedConnection = connectionList.selectedValue
        if (selectedConnection != null) {
            // Show confirmation dialog before deleting
            val confirmTitle = "Confirm Deletion"
            val confirmMessage = "Are you sure you want to delete the connection '${selectedConnection.alias}'?"
            
            val result = Messages.showYesNoDialog(
                project,
                confirmMessage,
                confirmTitle,
                Messages.getQuestionIcon()
            )
            
            if (result == Messages.YES) {
                // User confirmed, proceed with deletion
                connectionStorageService.removeConnection(selectedConnection.id)
                println("Connection deleted: ${selectedConnection.alias}")
                loadConnections() // Reload list
            } else {
                // User cancelled deletion
                println("Deletion cancelled for: ${selectedConnection.alias}")
            }
        }
    }

    fun connect() {
        val selectedConnection = connectionList.selectedValue
        if (selectedConnection != null) {
            // Get the service instance of SshConnectionExecutor
            val executor = project.getService(com.github.feelixs.sshplugin.services.SshConnectionExecutor::class.java)
            val success = executor.executeConnection(selectedConnection.id)
            
            if (success) {
                println("SSH connection initiated for: ${selectedConnection.alias}")
            } else {
                println("Failed to initiate SSH connection for: ${selectedConnection.alias}")
            }
        }
    }
    
    /**
     * Duplicates the currently selected connection with a "Copy of" prefix.
     */
    fun duplicateConnection() {
        val selectedConnection = connectionList.selectedValue
        if (selectedConnection != null) {
            // Get a copy with decrypted passwords
            val connectionWithPasswords = connectionStorageService.getConnectionWithPlainPasswords(selectedConnection.id)
            if (connectionWithPasswords != null) {
                // Create a clone with a new ID and modified alias
                val duplicatedConnection = connectionWithPasswords.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    alias = "Copy of ${connectionWithPasswords.alias}"
                )
                
                // Add the duplicated connection
                connectionStorageService.addConnection(duplicatedConnection)
                println("Duplicated connection: ${selectedConnection.alias} -> ${duplicatedConnection.alias}")
                
                // Reload the list to show the new connection
                loadConnections()
                
                // Select the new connection in the list
                for (i in 0 until listModel.size()) {
                    val connection = listModel.getElementAt(i)
                    if (connection.id == duplicatedConnection.id) {
                        connectionList.selectedIndex = i
                        break
                    }
                }
            }
        }
    }

    /**
     * Starts a timer to refresh the connection list status every few seconds
     * This ensures the asterisk indicators for active connections stay current
     */
    private fun startStatusRefreshTimer() {
        // Schedule a timer task to refresh the connection list every 2 seconds
        refreshTimer.schedule(object : TimerTask() {
            override fun run() {
                // Use update queue to avoid flooding UI thread
                // Queue update on UI thread
                updateQueue.queue(Update.create("refresh") {
                    try {
                        // Only repaint the list if component is still valid
                        if (connectionList.isShowing && connectionList.isValid) {
                            connectionList.repaint()
                        }
                    } catch (e: Exception) {
                        // Ignore exceptions that might occur during shutdown
                        refreshTimer.cancel()
                    }
                })
            }
        }, 2000, 2000) // Initial delay 2 sec, then every 2 sec
    }
    
    /**
     * Clean up resources when panel is disposed
     */
    override fun dispose() {
        // Cancel timer to prevent memory leaks
        refreshTimer.cancel()
        updateQueue.cancelAllUpdates()
    }

    // --- DataProvider Implementation ---
    override fun uiDataSnapshot(sink: DataSink) {
        // Call the superclass implementation first
        super.uiDataSnapshot(sink)

        // Provide the panel instance itself
        sink.set(PluginDataKeys.SSH_TOOL_WINDOW_PANEL, this)

        // Provide the currently selected connection (if it's not null)
        connectionList.selectedValue?.let { connection ->
            sink.set(PluginDataKeys.SELECTED_SSH_CONNECTION, connection)
        }
    }
}
