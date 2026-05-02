package com.github.feelixs.sshplugin.toolWindow

import com.github.feelixs.sshplugin.actions.PluginDataKeys
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.github.feelixs.sshplugin.model.SshFolder
import com.github.feelixs.sshplugin.services.SshConnectionExecutor
import com.github.feelixs.sshplugin.services.SshConnectionStorageService
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import java.util.Timer
import java.util.TimerTask
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class SshToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true), DataProvider, Disposable {

    private val connectionStorageService = SshConnectionStorageService.instance
    private val rootNode = DefaultMutableTreeNode("ROOT")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    val executor = project.getService(SshConnectionExecutor::class.java)

    private val refreshTimer = Timer("SSH-Connection-Status-Timer", true)
    private val updateQueue = MergingUpdateQueue(
        "SshConnectionListRefresh", 300, true, null, this, null, false
    )

    init {
        setupUI()
        reloadTree()
        startStatusRefreshTimer()
    }

    private fun setupUI() {
        val actionManager = ActionManager.getInstance()
        val actionGroup = actionManager.getAction("SSHPlugin.ToolWindow.Toolbar") as? DefaultActionGroup
            ?: DefaultActionGroup()
        val toolbar = actionManager.createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, actionGroup, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = SshConnectionTreeCellRenderer()
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

        val scrollPane = JBScrollPane(tree)
        setContent(scrollPane)
    }

    /**
     * Rebuild the tree from storage, preserving the previously selected node id
     * and expanded folder ids across the rebuild.
     */
    private fun reloadTree() {
        val previouslySelectedId = selectedNodeId()
        val expandedFolderIds = collectExpandedFolderIds()

        rootNode.removeAllChildren()
        val folders = connectionStorageService.getFolders()
        val connections = connectionStorageService.getConnections()

        // Folders first, then connections grouped under their folder
        val folderNodesById = mutableMapOf<String, DefaultMutableTreeNode>()
        for (folder in folders) {
            val folderNode = DefaultMutableTreeNode(folder)
            folderNodesById[folder.id] = folderNode
            rootNode.add(folderNode)
        }
        for (connection in connections) {
            val connNode = DefaultMutableTreeNode(connection)
            val parent = connection.folderId
                ?.let { folderNodesById[it] }
                ?: rootNode  // null folderId OR orphan folderId -> root
            parent.add(connNode)
        }
        treeModel.reload()

        // Restore expansion
        for (folderId in expandedFolderIds) {
            val folderNode = folderNodesById[folderId] ?: continue
            tree.expandPath(TreePath(folderNode.path))
        }
        // Restore selection
        if (previouslySelectedId != null) {
            selectNodeById(previouslySelectedId)
        }
    }

    private fun selectedNodeId(): String? {
        val selected = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return when (val obj = selected.userObject) {
            is SshConnectionData -> obj.id
            is SshFolder -> obj.id
            else -> null
        }
    }

    private fun collectExpandedFolderIds(): Set<String> {
        val ids = mutableSetOf<String>()
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val obj = child.userObject as? SshFolder ?: continue
            if (tree.isExpanded(TreePath(child.path))) ids.add(obj.id)
        }
        return ids
    }

    private fun selectNodeById(id: String) {
        val node = findNodeById(id) ?: return
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun findNodeById(id: String): DefaultMutableTreeNode? {
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChildAt(i) as DefaultMutableTreeNode
            when (val obj = child.userObject) {
                is SshFolder -> if (obj.id == id) return child
                is SshConnectionData -> if (obj.id == id) return child
            }
            for (j in 0 until child.childCount) {
                val grand = child.getChildAt(j) as DefaultMutableTreeNode
                val gobj = grand.userObject as? SshConnectionData ?: continue
                if (gobj.id == id) return grand
            }
        }
        return null
    }

    /** The currently selected connection, or null. */
    private fun selectedConnection(): SshConnectionData? {
        val sel = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return sel.userObject as? SshConnectionData
    }

    /** The currently selected folder, or null. */
    private fun selectedFolder(): SshFolder? {
        val sel = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return sel.userObject as? SshFolder
    }

    /**
     * Determines the target folder id for a newly added connection, based on
     * the current selection: selected folder -> that folder; selected connection
     * -> its parent folder; otherwise null (root).
     */
    private fun targetFolderIdForNewConnection(): String? {
        selectedFolder()?.let { return it.id }
        selectedConnection()?.let { return it.folderId }
        return null
    }

    // --- Public API for actions ---

    fun addConnection() {
        val dialog = SshConnectionDialog(project)
        if (dialog.showAndGet()) {
            val newConnection = dialog.createConnectionData()
            newConnection.folderId = targetFolderIdForNewConnection()
            connectionStorageService.addConnection(newConnection)
            reloadTree()
        }
    }

    fun newFolder() {
        val name = Messages.showInputDialog(
            project,
            "Folder name:",
            "New Folder",
            Messages.getQuestionIcon()
        )?.trim() ?: return
        if (name.isBlank()) return
        val folder = SshFolder(name = name)
        connectionStorageService.addFolder(folder)
        reloadTree()
        selectNodeById(folder.id)
    }

    fun editConnection() {
        val selectedConnection = selectedConnection() ?: return
        val connectionWithPasswords = connectionStorageService.getConnectionWithPlainPasswords(selectedConnection.id)
            ?: return
        val dialog = SshConnectionDialog(project, connectionWithPasswords)
        if (dialog.showAndGet()) {
            val updated = dialog.createConnectionData()
            // Preserve folderId across edit (dialog doesn't expose it)
            updated.folderId = selectedConnection.folderId
            connectionStorageService.updateConnection(updated)
            reloadTree()
        }
    }

    fun deleteConnection() {
        val selectedConnection = selectedConnection() ?: return
        val result = Messages.showYesNoDialog(
            project,
            "Are you sure you want to delete the connection '${selectedConnection.alias}'?",
            "Confirm Deletion",
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            connectionStorageService.removeConnection(selectedConnection.id)
            reloadTree()
        }
    }

    fun connect() {
        val selectedConnection = selectedConnection() ?: return
        executor.executeConnection(selectedConnection.id)
    }

    fun duplicateConnection() {
        val selectedConnection = selectedConnection() ?: return
        val withPasswords = connectionStorageService.getConnectionWithPlainPasswords(selectedConnection.id) ?: return
        val duplicated = withPasswords.copy(
            id = java.util.UUID.randomUUID().toString(),
            alias = "Copy of ${withPasswords.alias}",
            encodedPassword = withPasswords.encodedPassword,
            encodedSudoPassword = withPasswords.encodedSudoPassword,
            encodedKeyPassword = withPasswords.encodedKeyPassword,
            folderId = withPasswords.folderId
        )
        connectionStorageService.addConnection(duplicated)
        reloadTree()
        selectNodeById(duplicated.id)
    }

    private fun startStatusRefreshTimer() {
        refreshTimer.schedule(object : TimerTask() {
            override fun run() {
                updateQueue.queue(Update.create("refresh") {
                    try {
                        if (tree.isShowing && tree.isValid) {
                            tree.repaint()
                        }
                    } catch (e: Exception) {
                        refreshTimer.cancel()
                    }
                })
            }
        }, 2000, 2000)
    }

    override fun dispose() {
        refreshTimer.cancel()
        updateQueue.cancelAllUpdates()
    }

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        sink.set(PluginDataKeys.SSH_TOOL_WINDOW_PANEL, this)
        selectedConnection()?.let { sink.set(PluginDataKeys.SELECTED_SSH_CONNECTION, it) }
    }
}
