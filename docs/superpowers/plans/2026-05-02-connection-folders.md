# Connection Folders Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add single-level folders to the SSH Connections tool window. Users can create folders, drag connections into and out of folders, reorder them, rename and delete folders. Existing flat-list configs keep working.

**Architecture:** Replace the current `JBList<SshConnectionData>` with an IntelliJ `Tree`. Persist folders as a parallel `MutableList<SshFolder>` in the existing `SshConnectionStorageService.State`. Each `SshConnectionData` gains a nullable `folderId` field (null = root). Drag-and-drop is the only mechanism for moving connections between folders; folder management uses toolbar + right-click actions.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (Tree, DefaultTreeModel, DnDSupport, Messages), existing `PersistentStateComponent` XML serialization.

**Spec:** `docs/superpowers/specs/2026-05-02-connection-folders-design.md`

**No tests:** This project has no test suite. Verification is by manual `./gradlew runIde` smoke testing — each task lists what to click on and what to expect. Do not add unit tests.

---

## Task 1: Add `SshFolder` data class

**Files:**
- Create: `src/main/kotlin/com/github/feelixs/sshplugin/model/SshFolder.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.github.feelixs.sshplugin.model

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import java.util.UUID

@Tag("SshFolder")
data class SshFolder(
    @Attribute("id") var id: String = UUID.randomUUID().toString(),
    @Attribute("name") var name: String = ""
) {
    constructor() : this(id = UUID.randomUUID().toString())
}
```

The no-arg secondary constructor mirrors the pattern used in `SshConnectionData.kt` for XML deserialization.

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/model/SshFolder.kt
git commit -m "feat(model): add SshFolder data class"
```

---

## Task 2: Add `folderId` field to `SshConnectionData`

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/model/SshConnectionData.kt`

- [ ] **Step 1: Add the field**

In `SshConnectionData.kt`, add a new field at the end of the constructor parameter list, BEFORE the closing `)`:

```kotlin
@Attribute("maximizeTerminal") var maximizeTerminal: Boolean = false, // Whether to maximize terminal on connect
@Attribute("folderId") var folderId: String? = null // ID of containing folder, null = root
```

Note the comma added to the previous line. The field comes last so XML serialization order is stable.

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/model/SshConnectionData.kt
git commit -m "feat(model): add folderId field to SshConnectionData"
```

---

## Task 3: Add folders list to storage service `State`

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/services/SshConnectionStorageService.kt`

- [ ] **Step 1: Add the import and update State**

Add to imports (alongside existing model imports):
```kotlin
import com.github.feelixs.sshplugin.model.SshFolder
```

Replace the existing `class State { ... }` (lines ~24-27) with:

```kotlin
class State {
    @XCollection(style = XCollection.Style.v2)
    var connections: MutableList<SshConnectionData> = mutableListOf()

    @XCollection(style = XCollection.Style.v2)
    var folders: MutableList<SshFolder> = mutableListOf()
}
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/services/SshConnectionStorageService.kt
git commit -m "feat(storage): add folders list to State"
```

---

## Task 4: Add folder CRUD methods to storage service

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/services/SshConnectionStorageService.kt`

- [ ] **Step 1: Add the methods**

At the end of the class (before the final `}`), add the following block:

```kotlin
    // --- Folder management ---

    fun getFolders(): List<SshFolder> {
        return internalState.folders.toList()
    }

    fun addFolder(folder: SshFolder) {
        internalState.folders.add(folder)
    }

    fun renameFolder(id: String, newName: String) {
        val folder = internalState.folders.find { it.id == id } ?: return
        folder.name = newName
    }

    /**
     * Removes a folder. If [deleteContainedConnections] is true, contained
     * connections are deleted via [removeConnection] (which also purges their
     * passwords from PasswordSafe). Otherwise contained connections have their
     * folderId reset to null (moved to root). Missing id is a no-op.
     */
    fun removeFolder(id: String, deleteContainedConnections: Boolean) {
        val folder = internalState.folders.find { it.id == id } ?: return
        val containedIds = internalState.connections
            .filter { it.folderId == id }
            .map { it.id }
        if (deleteContainedConnections) {
            for (connId in containedIds) {
                removeConnection(connId)
            }
        } else {
            for (conn in internalState.connections) {
                if (conn.folderId == id) conn.folderId = null
            }
        }
        internalState.folders.remove(folder)
    }

    /**
     * Moves a connection to a new folder. Pass null for [targetFolderId] to move
     * to root. If [targetFolderId] is non-null but no folder with that id exists,
     * this is a no-op (folderId is left unchanged).
     */
    fun moveConnection(connectionId: String, targetFolderId: String?) {
        val connection = internalState.connections.find { it.id == connectionId } ?: return
        if (targetFolderId != null && internalState.folders.none { it.id == targetFolderId }) {
            return
        }
        connection.folderId = targetFolderId
    }

    /**
     * Reorders a connection within the underlying connections list. [newIndexInList]
     * is clamped to [0, connections.lastIndex]. Missing id is a no-op.
     */
    fun reorderConnection(connectionId: String, newIndexInList: Int) {
        val currentIndex = internalState.connections.indexOfFirst { it.id == connectionId }
        if (currentIndex == -1) return
        val item = internalState.connections.removeAt(currentIndex)
        val safeIndex = newIndexInList.coerceIn(0, internalState.connections.size)
        internalState.connections.add(safeIndex, item)
    }

    /**
     * Reorders a folder within the folders list. [newIndexInList] is clamped.
     * Missing id is a no-op.
     */
    fun reorderFolder(folderId: String, newIndexInList: Int) {
        val currentIndex = internalState.folders.indexOfFirst { it.id == folderId }
        if (currentIndex == -1) return
        val item = internalState.folders.removeAt(currentIndex)
        val safeIndex = newIndexInList.coerceIn(0, internalState.folders.size)
        internalState.folders.add(safeIndex, item)
    }
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/services/SshConnectionStorageService.kt
git commit -m "feat(storage): add folder CRUD and reorder methods"
```

---

## Task 5: Create the tree cell renderer

**Files:**
- Create: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshConnectionTreeCellRenderer.kt`

- [ ] **Step 1: Create the renderer**

```kotlin
package com.github.feelixs.sshplugin.toolWindow

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.github.feelixs.sshplugin.model.SshFolder
import com.github.feelixs.sshplugin.services.SshConnectionExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Renders both folder and connection nodes in the SSH tool window tree.
 * Connection rendering matches the previous list cell renderer (active session
 * asterisk, host info, [key], [auto-sudo] markers, OS-type icon).
 */
class SshConnectionTreeCellRenderer : ColoredTreeCellRenderer() {

    private val ACTIVE_CONNECTION_ATTRIBUTES = SimpleTextAttributes(
        SimpleTextAttributes.STYLE_BOLD,
        JBColor.GREEN
    )

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val userObject = node.userObject) {
            is SshFolder -> renderFolder(userObject)
            is SshConnectionData -> renderConnection(userObject)
            else -> { /* root node, render nothing */ }
        }
    }

    private fun renderFolder(folder: SshFolder) {
        icon = AllIcons.Nodes.Folder
        append(folder.name.ifBlank { "(unnamed folder)" }, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }

    private fun renderConnection(value: SshConnectionData) {
        val osIcon = when (value.osType) {
            OsType.LINUX -> AllIcons.RunConfigurations.Application
            OsType.WINDOWS -> AllIcons.FileTypes.Any_type
        }
        icon = osIcon

        val activeSessionCount = getActiveSessionCount(value.id)
        if (activeSessionCount > 0) {
            append("* ", ACTIVE_CONNECTION_ATTRIBUTES)
            append(value.alias, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (activeSessionCount > 1) {
                append(" (${activeSessionCount} sessions)", ACTIVE_CONNECTION_ATTRIBUTES)
            }
        } else {
            append(value.alias, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }

        append(" (${value.username}@${value.host})", SimpleTextAttributes.GRAYED_ATTRIBUTES)

        if (value.useKey) {
            append(" [key]", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
        }
        @Suppress("DEPRECATION")
        if (value.useSudo) {
            append(" [auto-sudo]", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
        }
    }

    private fun getActiveSessionCount(connectionId: String): Int {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return 0
        val executor = project.getService(SshConnectionExecutor::class.java) ?: return 0
        return executor.getTerminalCount(connectionId)
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshConnectionTreeCellRenderer.kt
git commit -m "feat(ui): add tree cell renderer for folders and connections"
```

---

## Task 6: Switch `SshToolWindowPanel` from `JBList` to `Tree`

This task replaces the list with a tree but does NOT yet add folder actions, context menu, or DnD — those come later. After this task, the existing UI continues to work (all connections appear at root because no folders exist yet).

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt`

- [ ] **Step 1: Rewrite the panel**

Full replacement of `SshToolWindowPanel.kt`:

```kotlin
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
```

Note: `SshConnectionListCellRenderer.kt` is now unused but is NOT deleted yet — that happens in the cleanup task at the end so each task is independently revertable.

- [ ] **Step 2: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manually verify in IDE**

Run: `./gradlew runIde`
In the launched IDE, open the SSH Connections tool window and verify:
- Existing connections from the previous flat list still appear (now under the invisible root, with no folders shown).
- Add a new connection — appears at root.
- Edit, Duplicate, Delete still work.
- Connect button still launches a terminal.
- The active-session asterisk indicator still shows on connect.

Close the IDE.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt
git commit -m "feat(ui): switch tool window from JBList to Tree"
```

---

## Task 7: Add `NewFolderAction` and register it

**Files:**
- Create: `src/main/kotlin/com/github/feelixs/sshplugin/actions/NewFolderAction.kt`
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Add `newFolder()` to the panel**

In `SshToolWindowPanel.kt`, add this method alongside the other public action methods (e.g., right after `addConnection()`):

```kotlin
    fun newFolder() {
        val name = Messages.showInputDialog(
            project,
            "Folder name:",
            "New Folder",
            Messages.getQuestionIcon()
        )?.trim() ?: return
        if (name.isBlank()) return
        val folder = com.github.feelixs.sshplugin.model.SshFolder(name = name)
        connectionStorageService.addFolder(folder)
        reloadTree()
        selectNodeById(folder.id)
    }
```

- [ ] **Step 2: Create the action class**

```kotlin
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
```

- [ ] **Step 3: Register the action in `plugin.xml`**

In `src/main/resources/META-INF/plugin.xml`, inside the `SSHPlugin.ToolWindow.Toolbar` group, add a new `<action>` immediately after the `SSHPlugin.AddConnection` line:

```xml
            <action id="SSHPlugin.NewFolder"
                    class="com.github.feelixs.sshplugin.actions.NewFolderAction"
                    text="New Folder"
                    description="Create a new folder for organizing SSH connections"/>
```

- [ ] **Step 4: Build and run**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew runIde`
In the SSH Connections tool window:
- Click the new "New Folder" toolbar button.
- Enter a folder name, click OK — folder appears in the tree.
- Cancel the dialog — no folder is added.
- Enter a blank/whitespace name — no folder is added.

Close the IDE.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/actions/NewFolderAction.kt \
        src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(ui): add New Folder toolbar action"
```

---

## Task 8: Add `RenameFolderAction` and panel method

**Files:**
- Create: `src/main/kotlin/com/github/feelixs/sshplugin/actions/RenameFolderAction.kt`
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt`

- [ ] **Step 1: Add `renameFolder()` to the panel**

In `SshToolWindowPanel.kt`, add alongside `newFolder()`:

```kotlin
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
```

- [ ] **Step 2: Create the action class**

```kotlin
package com.github.feelixs.sshplugin.actions

import com.github.feelixs.sshplugin.model.SshFolder
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** Action to rename the selected folder. Enabled only when a folder is selected. */
class RenameFolderAction : AnAction, DumbAware {

    constructor() : super(AllIcons.General.Inline_edit)

    override fun actionPerformed(e: AnActionEvent) {
        val panel = e.dataContext.getData(PluginDataKeys.SSH_TOOL_WINDOW_PANEL)
        panel?.renameFolder()
    }

    override fun update(e: AnActionEvent) {
        val panel = e.dataContext.getData(PluginDataKeys.SSH_TOOL_WINDOW_PANEL)
        val folder = e.dataContext.getData(PluginDataKeys.SELECTED_SSH_FOLDER)
        e.presentation.isEnabled = panel != null && folder != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
```

Note: `SELECTED_SSH_FOLDER` is added in the next step. Per CLAUDE.md, icons must come from `AllIcons.General.*` rather than `AllIcons.Actions.*`, hence `AllIcons.General.Inline_edit`.

- [ ] **Step 3: Add the new data key**

Modify `src/main/kotlin/com/github/feelixs/sshplugin/actions/PluginDataKeys.kt` — replace its body with:

```kotlin
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
```

- [ ] **Step 4: Provide the folder via `uiDataSnapshot`**

In `SshToolWindowPanel.kt`, replace the existing `uiDataSnapshot` method with:

```kotlin
    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        sink.set(PluginDataKeys.SSH_TOOL_WINDOW_PANEL, this)
        selectedConnection()?.let { sink.set(PluginDataKeys.SELECTED_SSH_CONNECTION, it) }
        selectedFolder()?.let { sink.set(PluginDataKeys.SELECTED_SSH_FOLDER, it) }
    }
```

- [ ] **Step 5: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/actions/RenameFolderAction.kt \
        src/main/kotlin/com/github/feelixs/sshplugin/actions/PluginDataKeys.kt \
        src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt
git commit -m "feat(ui): add RenameFolderAction and SELECTED_SSH_FOLDER data key"
```

---

## Task 9: Add `DeleteFolderAction` with three-button confirm

**Files:**
- Create: `src/main/kotlin/com/github/feelixs/sshplugin/actions/DeleteFolderAction.kt`
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt`

- [ ] **Step 1: Add `deleteFolder()` to the panel**

In `SshToolWindowPanel.kt`, add alongside `renameFolder()`:

```kotlin
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
```

- [ ] **Step 2: Create the action class**

```kotlin
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
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/actions/DeleteFolderAction.kt \
        src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt
git commit -m "feat(ui): add DeleteFolderAction with 3-button confirm"
```

---

## Task 10: Wire up the right-click context menu

The toolbar shows New Folder. The context menu adds Rename/Delete on folders, plus New Folder anywhere. Existing connection actions (Connect, Edit, Duplicate, Delete, Disconnect, Copy Sudo Password) are also routed through the context menu when right-clicking a connection.

**Files:**
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt`

- [ ] **Step 1: Define the popup action group in `plugin.xml`**

Inside `<actions>` (alongside the existing toolbar group), add a new group:

```xml
        <group id="SSHPlugin.ToolWindow.Popup" popup="true">
            <reference ref="SSHPlugin.Connect"/>
            <reference ref="SSHPlugin.Disconnect"/>
            <separator/>
            <reference ref="SSHPlugin.AddConnection"/>
            <reference ref="SSHPlugin.EditConnection"/>
            <reference ref="SSHPlugin.DuplicateConnection"/>
            <reference ref="SSHPlugin.DeleteConnection"/>
            <separator/>
            <reference ref="SSHPlugin.NewFolder"/>
            <action id="SSHPlugin.RenameFolder"
                    class="com.github.feelixs.sshplugin.actions.RenameFolderAction"
                    text="Rename Folder…"
                    description="Rename the selected folder"/>
            <action id="SSHPlugin.DeleteFolder"
                    class="com.github.feelixs.sshplugin.actions.DeleteFolderAction"
                    text="Delete Folder…"
                    description="Delete the selected folder"/>
            <separator/>
            <reference ref="SSHPlugin.CopySudoPassword"/>
        </group>
```

Note: Rename/Delete folder are declared inside the popup group (not the toolbar group) so they only appear in the right-click menu. Each action's own `update()` handles enabled state based on what's selected, so a single popup group works whether the user right-clicked a folder, a connection, or empty space.

- [ ] **Step 2: Install the popup handler on the tree**

In `SshToolWindowPanel.kt`, modify `setupUI()` to install a popup mouse listener after the tree is configured. Append this to the end of `setupUI()`:

```kotlin
        installPopupMenu()
```

Then add the new method to the class:

```kotlin
    private fun installPopupMenu() {
        val popupGroup = ActionManager.getInstance()
            .getAction("SSHPlugin.ToolWindow.Popup") as? DefaultActionGroup ?: return
        com.intellij.openapi.actionSystem.PopupHandler.installPopupMenu(
            tree, popupGroup, "SSHPluginToolWindowPopup"
        )
    }
```

- [ ] **Step 3: Build and run**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew runIde`
In the SSH Connections tool window:
- Right-click empty space — popup shows New Folder, Add Connection (others disabled).
- Right-click a connection — Connect/Edit/Duplicate/Delete/Copy Sudo Password are enabled; Rename/Delete Folder are disabled.
- Right-click a folder — Rename Folder, Delete Folder, New Folder are enabled.
- Click "Rename Folder…" — rename dialog opens with current name pre-filled. Change it and verify it updates in the tree.
- Click "Delete Folder…" on an empty folder — yes/no confirm. Confirm, folder disappears.
- Add a connection while a folder is selected — connection appears inside that folder.
- Add a folder, drop a connection in (manually edit XML for now, since DnD is next task), select the folder, click "Delete Folder…" — three-button dialog appears. Test all three buttons across separate runs.

Close the IDE.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/META-INF/plugin.xml \
        src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt
git commit -m "feat(ui): add right-click context menu for tree"
```

---

## Task 11: Add drag-and-drop support

Drag-and-drop moves connections between folders, reorders connections, reorders folders, and rejects nesting folders.

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt`

- [ ] **Step 1: Install DnD support on the tree**

Add this method to `SshToolWindowPanel.kt`:

```kotlin
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
            .setTargetChecker { event ->
                val target = resolveDropTarget(event.point.x, event.point.y, event.attachedObject)
                event.isDropPossible = target != null
                true
            }
            .setDropHandler { event ->
                val target = resolveDropTarget(event.point.x, event.point.y, event.attachedObject) ?: return@setDropHandler
                applyDrop(event.attachedObject, target)
                reloadTree()
            }
            .install()
    }
```

Add the helper data classes and resolver to the class (e.g., near the bottom, before `dispose`):

```kotlin
    private data class DraggedConnection(val id: String)
    private data class DraggedFolder(val id: String)

    /**
     * What a drop should do, computed from the drop point and dragged payload.
     * - MoveConnectionToFolder: drop on a folder node OR on a connection inside a folder
     * - MoveConnectionToRoot:   drop on empty space OR on a root-level connection
     * - ReorderFolder:          drop on / between root-level folders (folder payload only)
     */
    private sealed class DropTarget {
        /** Move connection into a folder; if [insertBeforeConnectionId] is non-null, insert just before it; else append to end of folder's contents. */
        data class MoveConnectionToFolder(val connectionId: String, val folderId: String, val insertBeforeConnectionId: String? = null) : DropTarget()
        /** Move connection to root; if [insertBeforeConnectionId] is non-null, insert just before it; else append. */
        data class MoveConnectionToRoot(val connectionId: String, val insertBeforeConnectionId: String? = null) : DropTarget()
        data class ReorderFolderTo(val folderId: String, val newIndex: Int) : DropTarget()
    }

    private fun resolveDropTarget(x: Int, y: Int, payload: Any?): DropTarget? {
        val path = tree.getPathForLocation(x, y)
        val node = path?.lastPathComponent as? DefaultMutableTreeNode
        val rowBounds = path?.let { tree.getPathBounds(it) }
        // Drop is in upper half of the row -> insert before; lower half -> insert after.
        val dropAbove = rowBounds != null && y < (rowBounds.y + rowBounds.height / 2)

        when (payload) {
            is DraggedConnection -> {
                if (node == null) {
                    return DropTarget.MoveConnectionToRoot(payload.id)
                }
                return when (val obj = node.userObject) {
                    is SshFolder -> DropTarget.MoveConnectionToFolder(payload.id, obj.id)
                    is SshConnectionData -> {
                        if (obj.id == payload.id) return null
                        // Determine the connection-id to insert before (or null = append).
                        val insertBeforeId = if (dropAbove) {
                            obj.id
                        } else {
                            // Insert before the next sibling, if any
                            siblingConnectionAfter(node)?.id
                        }
                        if (obj.folderId == null) {
                            DropTarget.MoveConnectionToRoot(payload.id, insertBeforeId)
                        } else {
                            DropTarget.MoveConnectionToFolder(payload.id, obj.folderId!!, insertBeforeId)
                        }
                    }
                    else -> DropTarget.MoveConnectionToRoot(payload.id)
                }
            }
            is DraggedFolder -> {
                if (node == null) {
                    val lastIndex = connectionStorageService.getFolders().size
                    return DropTarget.ReorderFolderTo(payload.id, lastIndex)
                }
                val obj = node.userObject
                if (obj !is SshFolder) {
                    // dropping a folder on a connection is rejected
                    return null
                }
                if (obj.id == payload.id) return null
                val targetIdx = connectionStorageService.getFolders().indexOfFirst { it.id == obj.id }
                val newIndex = if (dropAbove) targetIdx else targetIdx + 1
                return DropTarget.ReorderFolderTo(payload.id, newIndex)
            }
            else -> return null
        }
    }

    /** Returns the connection node immediately after [node] under the same parent, or null. */
    private fun siblingConnectionAfter(node: DefaultMutableTreeNode): SshConnectionData? {
        val parent = node.parent as? DefaultMutableTreeNode ?: return null
        val idx = parent.getIndex(node)
        if (idx < 0 || idx + 1 >= parent.childCount) return null
        val next = parent.getChildAt(idx + 1) as? DefaultMutableTreeNode ?: return null
        return next.userObject as? SshConnectionData
    }

    private fun applyDrop(payload: Any?, target: DropTarget) {
        when (target) {
            is DropTarget.MoveConnectionToFolder -> {
                connectionStorageService.moveConnection(target.connectionId, target.folderId)
                target.insertBeforeConnectionId?.let { beforeId ->
                    val beforeIdx = connectionStorageService.getConnections().indexOfFirst { it.id == beforeId }
                    if (beforeIdx >= 0) connectionStorageService.reorderConnection(target.connectionId, beforeIdx)
                }
            }
            is DropTarget.MoveConnectionToRoot -> {
                connectionStorageService.moveConnection(target.connectionId, null)
                target.insertBeforeConnectionId?.let { beforeId ->
                    val beforeIdx = connectionStorageService.getConnections().indexOfFirst { it.id == beforeId }
                    if (beforeIdx >= 0) connectionStorageService.reorderConnection(target.connectionId, beforeIdx)
                }
            }
            is DropTarget.ReorderFolderTo ->
                connectionStorageService.reorderFolder(target.folderId, target.newIndex)
        }
    }
```

Then, in `setupUI()`, add at the end (after `installPopupMenu()`):

```kotlin
        installDnD()
```

Notes:
- Drop position within a row matters: upper half = "insert before this row", lower half = "insert after". This applies to both connection drops (insert relative to a sibling connection) and folder drops (reorder relative to another root folder).
- A connection dropped on a folder node (the folder header itself) is appended to that folder.
- A folder dropped onto another folder is rejected — no nesting.
- A folder dropped on a connection is rejected.
- A connection dropped on itself is a no-op.

- [ ] **Step 2: Build and run**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew runIde`
Verify in the launched IDE:
- Drag a root-level connection onto a folder — connection moves into the folder.
- Drag a connection out of a folder onto empty space — connection moves to root.
- Drag a connection from one folder to another — connection moves.
- Drag a connection above another root-level connection — order changes accordingly (use upper-half drop).
- Drag a connection below another root-level connection — order changes accordingly (use lower-half drop).
- Drag a folder above/below another folder — folder reorders.
- Drag a folder onto another folder — drop is rejected (no-drop cursor; no nesting created).
- Drag a folder onto a connection — drop is rejected.
- Drag a connection onto itself — no-op.

Close the IDE.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt
git commit -m "feat(ui): add drag-and-drop for moving connections and reordering folders"
```

---

## Task 12: Delete the obsolete list cell renderer

**Files:**
- Delete: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshConnectionListCellRenderer.kt`

- [ ] **Step 1: Confirm no references remain**

Run: `grep -r "SshConnectionListCellRenderer" /Users/michaelfelix/Documents/GitHub/ssh-plugin/src`
Expected: no matches (or only the file itself).

- [ ] **Step 2: Delete the file**

```bash
rm /Users/michaelfelix/Documents/GitHub/ssh-plugin/src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshConnectionListCellRenderer.kt
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/
git commit -m "chore(ui): remove obsolete SshConnectionListCellRenderer"
```

---

## Task 13: Final smoke test

- [ ] **Step 1: Run full build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full feature smoke test**

Run: `./gradlew runIde`
In the launched IDE, walk through every user-facing flow once:

1. Add a connection — appears at root.
2. New Folder "Production" — appears in tree.
3. Drag the connection into Production — moves.
4. Add a second connection while Production is selected — appears inside Production.
5. New Folder "Staging" — appears below Production.
6. Drag a connection from Production to Staging — moves.
7. Drag the Production folder below Staging — reorders.
8. Try to drag Production onto Staging — rejected.
9. Right-click Production → Rename Folder… → "Prod" — name updates.
10. Right-click Prod → Delete Folder… → choose "Delete folder only (move connections to root)" — connections appear at root, folder gone.
11. Right-click Staging → Delete Folder… → choose "Delete folder and all connections" — connections AND folder gone.
12. Restart `runIde`, open the tool window again — folders and connections that survived are still present and in their correct positions/folders (XML persistence works).
13. Connect to a remaining root-level connection — terminal launches, asterisk appears.

Close the IDE. No commit — this is verification only.
