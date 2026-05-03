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
    private val tree = DropAwareTree(treeModel)
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
        installPopupMenu()
        installDnD()
        installDoubleClickHandler()
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

        // Build root entries: folders + root connections, all sorted by order.
        // Each entry pairs (order, lazy node builder) so we can sort and then attach in one pass.
        val rootEntries = mutableListOf<Pair<Int, DefaultMutableTreeNode>>()
        val folderNodesById = mutableMapOf<String, DefaultMutableTreeNode>()
        for (folder in folders) {
            val node = DefaultMutableTreeNode(folder)
            folderNodesById[folder.id] = node
            rootEntries += folder.order to node
        }
        for (conn in connections.filter { it.folderId == null }) {
            rootEntries += conn.order to DefaultMutableTreeNode(conn)
        }
        // Stable sort: ties (e.g., first-load before normalize) preserve insertion order.
        rootEntries.sortedBy { it.first }.forEach { (_, node) -> rootNode.add(node) }

        // Connections inside folders.
        for ((folderId, folderNode) in folderNodesById) {
            connections
                .filter { it.folderId == folderId }
                .sortedBy { it.order }
                .forEach { folderNode.add(DefaultMutableTreeNode(it)) }
        }
        // Orphan connections: folderId is non-null but folder does not exist.
        connections
            .filter { it.folderId != null && it.folderId !in folderNodesById.keys }
            .sortedBy { it.order }
            .forEach { rootNode.add(DefaultMutableTreeNode(it)) }

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

    fun renameFolder() {
        val folder = selectedFolder() ?: return
        val newName = Messages.showInputDialog(
            project,
            "Folder name:",
            "Rename Folder",
            Messages.getQuestionIcon(),
            folder.name,
            null
        )?.trim() ?: return
        if (newName.isBlank() || newName == folder.name) return
        connectionStorageService.renameFolder(folder.id, newName)
        reloadTree()
        selectNodeById(folder.id)
    }

    fun deleteFolder() {
        val folder = selectedFolder() ?: return
        val containedCount = connectionStorageService.getConnections().count { it.folderId == folder.id }
        if (containedCount == 0) {
            val result = Messages.showYesNoDialog(
                project,
                "Delete folder '${folder.name}'?",
                "Delete Folder",
                Messages.getWarningIcon()
            )
            if (result == Messages.YES) {
                connectionStorageService.removeFolder(folder.id, deleteContainedConnections = false)
                reloadTree()
            }
            return
        }
        val choice = Messages.showDialog(
            project,
            "Folder '${folder.name}' contains $containedCount connection(s). What would you like to do?",
            "Delete Folder",
            arrayOf(
                "Delete folder only (move connections to root)",
                "Delete folder and all connections",
                "Cancel"
            ),
            2, // default = Cancel
            Messages.getWarningIcon()
        )
        when (choice) {
            0 -> {
                connectionStorageService.removeFolder(folder.id, deleteContainedConnections = false)
                reloadTree()
            }
            1 -> {
                connectionStorageService.removeFolder(folder.id, deleteContainedConnections = true)
                reloadTree()
            }
            else -> { /* cancel */ }
        }
    }

    fun editConnection() {
        val selectedConnection = selectedConnection() ?: return
        val connectionWithPasswords = connectionStorageService.getConnectionWithPlainPasswords(selectedConnection.id)
            ?: return
        val dialog = SshConnectionDialog(project, connectionWithPasswords)
        if (dialog.showAndGet()) {
            val updated = dialog.createConnectionData()
            // Preserve folderId and order across edit (dialog exposes neither)
            updated.folderId = selectedConnection.folderId
            updated.order = selectedConnection.order
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
            folderId = withPasswords.folderId,
            order = 0 // addConnection will assign (max in scope)+1, appending after the original
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

    private fun installPopupMenu() {
        val popupGroup = ActionManager.getInstance()
            .getAction("SSHPlugin.ToolWindow.Popup") as? DefaultActionGroup
        if (popupGroup == null) {
            com.intellij.openapi.diagnostic.thisLogger()
                .warn("SSHPlugin.ToolWindow.Popup action group not found; right-click menu disabled")
            return
        }
        com.intellij.ui.PopupHandler.installPopupMenu(
            tree, popupGroup, "SSHPluginToolWindowPopup"
        )
    }

    /** Render a translucent ghost of the cell at [path] for use as a drag preview image. */
    private fun renderGhostImage(path: javax.swing.tree.TreePath): java.awt.image.BufferedImage? {
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        val row = tree.getRowForPath(path)
        val component = tree.cellRenderer.getTreeCellRendererComponent(
            tree, node, /* selected */ false, /* expanded */ false,
            /* leaf */ node.isLeaf, /* row */ row, /* hasFocus */ false
        )
        component.size = component.preferredSize
        if (component.width <= 0 || component.height <= 0) return null
        val img = com.intellij.util.ui.ImageUtil.createImage(
            component.width, component.height, java.awt.image.BufferedImage.TYPE_INT_ARGB
        )
        val g = img.createGraphics()
        try {
            g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.5f)
            component.paint(g)
        } finally {
            g.dispose()
        }
        return img
    }

    private fun installDnD() {
        com.intellij.ide.dnd.DnDSupport.createBuilder(tree)
            .setBeanProvider { info ->
                val path = tree.getPathForLocation(info.point.x, info.point.y) ?: return@setBeanProvider null
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return@setBeanProvider null
                when (val obj = node.userObject) {
                    is SshConnectionData -> com.intellij.ide.dnd.DnDDragStartBean(DraggedConnection(obj.id))
                    is SshFolder -> com.intellij.ide.dnd.DnDDragStartBean(DraggedFolder(obj.id))
                    else -> null
                }
            }
            .setImageProvider { info ->
                val path = tree.getPathForLocation(info.point.x, info.point.y)
                    ?: return@setImageProvider null
                val img = renderGhostImage(path) ?: return@setImageProvider null
                com.intellij.ide.dnd.DnDImage(img, java.awt.Point(0, 0))
            }
            .setTargetChecker { event ->
                val target = resolveDropTarget(event.point.x, event.point.y, event.attachedObject)
                event.isDropPossible = target != null
                // Compute visual hint.
                val newHint: DropHint? = if (target == null) {
                    null
                } else {
                    // For drop-into-folder, target.parentFolderId is the folder id and the
                    // gesture targets the folder body (zone "into") — show highlight.
                    // We detect this by checking if the drop point's row is the target folder.
                    val path = tree.getPathForLocation(event.point.x, event.point.y)
                    val hoveredNode = path?.lastPathComponent as? DefaultMutableTreeNode
                    val hoveredFolder = hoveredNode?.userObject as? SshFolder
                    if (hoveredFolder != null && hoveredFolder.id == target.parentFolderId) {
                        DropHint.HighlightFolder(hoveredNode)
                    } else {
                        dropHintFor(target)
                    }
                }
                if (newHint != dropHint) {
                    dropHint = newHint
                    tree.repaint()
                }
                true
            }
            .setDropHandler { event ->
                val target = resolveDropTarget(event.point.x, event.point.y, event.attachedObject)
                dropHint = null
                tree.repaint()
                if (target == null) return@setDropHandler
                applyDrop(target)
                reloadTree()
            }
            .install()
    }

    private fun installDoubleClickHandler() {
        tree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount != 2 || e.button != java.awt.event.MouseEvent.BUTTON1) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val conn = node.userObject as? SshConnectionData ?: return
                executor.executeConnection(conn.id)
            }
        })
    }

    private data class DraggedConnection(val id: String)
    private data class DraggedFolder(val id: String)

    /**
     * Result of a drop computation: place [itemId] at [targetOrder] within
     * [parentFolderId] (null = root). null return from resolveDropTarget = reject.
     */
    private data class PlaceItem(val itemId: String, val parentFolderId: String?, val targetOrder: Int)

    private sealed class DropHint {
        /** Draw a horizontal insert line between siblings of [parent] at [insertIndex]. */
        data class InsertLine(val parent: DefaultMutableTreeNode, val insertIndex: Int) : DropHint()
        /** Draw a tinted background on the folder row. */
        data class HighlightFolder(val folderNode: DefaultMutableTreeNode) : DropHint()
    }

    private var dropHint: DropHint? = null

    private fun resolveDropTarget(x: Int, y: Int, payload: Any?): PlaceItem? {
        val path = tree.getPathForLocation(x, y)
        val node = path?.lastPathComponent as? DefaultMutableTreeNode
        val rowBounds = path?.let { tree.getPathBounds(it) }

        // Folder rows have 3 zones; connection rows have 2 (halves).
        val zoneAbove: Boolean
        val zoneInto: Boolean
        val zoneBelow: Boolean
        if (rowBounds != null) {
            val relY = y - rowBounds.y
            if (node?.userObject is SshFolder) {
                zoneAbove = relY < rowBounds.height / 4
                zoneBelow = relY >= rowBounds.height * 3 / 4
                zoneInto = !zoneAbove && !zoneBelow
            } else {
                val midpoint = rowBounds.height / 2
                zoneAbove = relY < midpoint
                zoneBelow = !zoneAbove
                zoneInto = false
            }
        } else {
            zoneAbove = false; zoneInto = false; zoneBelow = false
        }

        return when (payload) {
            is DraggedConnection -> resolveConnectionDrop(payload.id, node, zoneAbove, zoneInto, zoneBelow)
            is DraggedFolder -> resolveFolderDrop(payload.id, node, zoneAbove, zoneInto, zoneBelow)
            else -> null
        }
    }

    private fun resolveConnectionDrop(
        connId: String,
        node: DefaultMutableTreeNode?,
        zoneAbove: Boolean,
        zoneInto: Boolean,
        @Suppress("UNUSED_PARAMETER") zoneBelow: Boolean
    ): PlaceItem? {
        if (node == null) {
            // Empty space: append to root.
            val rootMax = maxRootOrder()
            return PlaceItem(connId, null, rootMax + 1)
        }
        return when (val obj = node.userObject) {
            is SshFolder -> when {
                zoneAbove -> PlaceItem(connId, null, obj.order)
                zoneInto -> {
                    val folderChildrenMax = connectionStorageService.getConnections()
                        .filter { it.folderId == obj.id }
                        .maxOfOrNull { it.order } ?: -1
                    PlaceItem(connId, obj.id, folderChildrenMax + 1)
                }
                else /* below */ -> PlaceItem(connId, null, obj.order + 1)
            }
            is SshConnectionData -> {
                if (obj.id == connId) null
                else if (zoneAbove) PlaceItem(connId, obj.folderId, obj.order)
                else PlaceItem(connId, obj.folderId, obj.order + 1)
            }
            else -> {
                // Root node or unknown: append to root.
                val rootMax = maxRootOrder()
                PlaceItem(connId, null, rootMax + 1)
            }
        }
    }

    private fun resolveFolderDrop(
        folderId: String,
        node: DefaultMutableTreeNode?,
        zoneAbove: Boolean,
        zoneInto: Boolean,
        @Suppress("UNUSED_PARAMETER") zoneBelow: Boolean
    ): PlaceItem? {
        if (node == null) {
            // Empty space: append at end of root.
            val rootMax = maxRootOrder()
            return PlaceItem(folderId, null, rootMax + 1)
        }
        return when (val obj = node.userObject) {
            is SshFolder -> {
                if (obj.id == folderId) null
                else when {
                    zoneAbove -> PlaceItem(folderId, null, obj.order)
                    zoneInto -> null // No folder nesting.
                    else /* below */ -> PlaceItem(folderId, null, obj.order + 1)
                }
            }
            is SshConnectionData -> {
                // A folder dropped on a connection only makes sense if the connection is at root
                // (otherwise the implied "root position" is ambiguous).
                if (obj.folderId != null) null
                else if (zoneAbove) PlaceItem(folderId, null, obj.order)
                else PlaceItem(folderId, null, obj.order + 1)
            }
            else -> {
                val rootMax = maxRootOrder()
                PlaceItem(folderId, null, rootMax + 1)
            }
        }
    }

    /** Max order across all root-scope items (folders + root connections); -1 if scope is empty. */
    private fun maxRootOrder(): Int {
        val foldersMax = connectionStorageService.getFolders().maxOfOrNull { it.order } ?: -1
        val rootConnsMax = connectionStorageService.getConnections()
            .filter { it.folderId == null }
            .maxOfOrNull { it.order } ?: -1
        return maxOf(foldersMax, rootConnsMax)
    }

    private fun applyDrop(target: PlaceItem) {
        connectionStorageService.placeAt(target.itemId, target.parentFolderId, target.targetOrder)
    }

    /**
     * Translate a [PlaceItem] into a [DropHint] for visual feedback.
     * Returns null if no hint should be shown (caller should set dropHint = null).
     *
     * The insert-line visual index is the count of CURRENT children whose order
     * is strictly less than [PlaceItem.targetOrder] — counting the moved item
     * normally if it is in this scope. This produces a line at the visual
     * position the dropped item will land at, regardless of whether the move is
     * forward, backward, or cross-scope.
     */
    private fun dropHintFor(target: PlaceItem): DropHint? {
        val parentNode: DefaultMutableTreeNode = if (target.parentFolderId == null) {
            rootNode
        } else {
            findFolderNode(target.parentFolderId) ?: return null
        }
        val orders = (0 until parentNode.childCount).map { i ->
            val child = parentNode.getChildAt(i) as DefaultMutableTreeNode
            when (val obj = child.userObject) {
                is SshConnectionData -> obj.order
                is SshFolder -> obj.order
                else -> Int.MAX_VALUE
            }
        }
        val insertIndex = orders.count { it < target.targetOrder }
        return DropHint.InsertLine(parentNode, insertIndex)
    }

    /** Find a folder node in the current tree by id. */
    private fun findFolderNode(folderId: String): DefaultMutableTreeNode? {
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val obj = child.userObject as? SshFolder ?: continue
            if (obj.id == folderId) return child
        }
        return null
    }

    private inner class DropAwareTree(model: javax.swing.tree.TreeModel) : Tree(model) {
        override fun paintComponent(g: java.awt.Graphics) {
            super.paintComponent(g)
            val hint = dropHint ?: return
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.setRenderingHint(
                    java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                )
                when (hint) {
                    is DropHint.InsertLine -> paintInsertLine(g2, hint)
                    is DropHint.HighlightFolder -> paintFolderHighlight(g2, hint)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun paintInsertLine(g2: java.awt.Graphics2D, hint: DropHint.InsertLine) {
            val parent = hint.parent
            val y = when {
                parent.childCount == 0 -> {
                    val parentBounds = this.getPathBounds(javax.swing.tree.TreePath(parent.path)) ?: return
                    parentBounds.y + parentBounds.height
                }
                hint.insertIndex < parent.childCount -> {
                    val child = parent.getChildAt(hint.insertIndex) as DefaultMutableTreeNode
                    val bounds = this.getPathBounds(javax.swing.tree.TreePath(child.path)) ?: return
                    bounds.y
                }
                else -> {
                    val lastChild = parent.getChildAt(parent.childCount - 1) as DefaultMutableTreeNode
                    val bounds = this.getPathBounds(javax.swing.tree.TreePath(lastChild.path)) ?: return
                    bounds.y + bounds.height
                }
            }
            g2.color = com.intellij.ui.JBColor.BLUE
            g2.stroke = java.awt.BasicStroke(2f)
            g2.drawLine(8, y, this.width - 8, y)
        }

        private fun paintFolderHighlight(g2: java.awt.Graphics2D, hint: DropHint.HighlightFolder) {
            val bounds = this.getPathBounds(javax.swing.tree.TreePath(hint.folderNode.path)) ?: return
            val base = com.intellij.ui.JBColor.BLUE
            g2.color = java.awt.Color(base.red, base.green, base.blue, 60)
            g2.fillRect(bounds.x, bounds.y, this.width - bounds.x, bounds.height)
        }
    }

    override fun dispose() {
        refreshTimer.cancel()
        updateQueue.cancelAllUpdates()
    }

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        sink.set(PluginDataKeys.SSH_TOOL_WINDOW_PANEL, this)
        selectedConnection()?.let { sink.set(PluginDataKeys.SELECTED_SSH_CONNECTION, it) }
        selectedFolder()?.let { sink.set(PluginDataKeys.SELECTED_SSH_FOLDER, it) }
    }
}
