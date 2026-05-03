# Unified Ordering, Drag Feedback, and Double-Click Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explicit `order: Int` field to folders and connections so they can be interleaved at root, refactor DnD to use a unified `placeAt` storage method, add visual feedback during drag (insert line, folder highlight, ghost preview), and enable double-click on a connection to initiate the connection.

**Architecture:** Storage gains one new method `placeAt(itemId, parentFolderId, targetOrder)` that replaces three older methods (`moveConnection`, `reorderConnection`, `reorderFolder`). Display sorts root items and folder children by `order`. DnD's drop-target resolution gets new 3-zone math for folder rows (above / into / below). A `Tree` subclass paints custom drop indicators. New items added via `addConnection`/`addFolder` get appended via auto-assigned `order`.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (Tree, DnDSupport, Graphics2D, ImageUtil), existing `PersistentStateComponent` XML serialization.

**Spec:** `docs/superpowers/specs/2026-05-02-unified-ordering-and-drag-feedback-design.md`

**No tests:** This project has no test suite. Verification is manual via `./gradlew runIde`. Each task lists what to click and what to expect.

---

## Task 1: Add `order` field to model classes

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/model/SshFolder.kt`
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/model/SshConnectionData.kt`

- [ ] **Step 1: Modify `SshFolder.kt`**

Replace the existing data class declaration. The existing file is:

```kotlin
@Tag("SshFolder")
data class SshFolder(
    @Attribute("id") var id: String = UUID.randomUUID().toString(),
    @Attribute("name") var name: String = ""
) {
    constructor() : this(id = UUID.randomUUID().toString())
}
```

Add the new `order` field LAST in the constructor parameter list (before the closing `)`):

```kotlin
@Tag("SshFolder")
data class SshFolder(
    @Attribute("id") var id: String = UUID.randomUUID().toString(),
    @Attribute("name") var name: String = "",
    @Attribute("order") var order: Int = 0
) {
    constructor() : this(id = UUID.randomUUID().toString())
}
```

- [ ] **Step 2: Modify `SshConnectionData.kt`**

The current last constructor parameter is:
```kotlin
@Attribute("folderId") var folderId: String? = null // ID of containing folder, null = root
```

Change it to add a comma and a new `order` field:
```kotlin
@Attribute("folderId") var folderId: String? = null, // ID of containing folder, null = root
@Attribute("order") var order: Int = 0 // Position within containing scope (root or folder)
```

- [ ] **Step 3: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/model/SshFolder.kt \
        src/main/kotlin/com/github/feelixs/sshplugin/model/SshConnectionData.kt
git commit -m "feat(model): add order field to SshFolder and SshConnectionData"
```

---

## Task 2: Auto-assign `order` to new items in `addConnection` / `addFolder`

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/services/SshConnectionStorageService.kt`

- [ ] **Step 1: Modify `addConnection`**

Locate the existing method:
```kotlin
fun addConnection(connection: SshConnectionData) {
    // Ensure we encrypt passwords when adding a connection
    encryptConnectionPasswords(connection)
    internalState.connections.add(connection)
}
```

Replace with:
```kotlin
fun addConnection(connection: SshConnectionData) {
    // Auto-assign order = (max in target scope) + 1; 0 if scope empty.
    // Scope is determined by the connection's folderId, which the caller already set.
    val scopeMax = internalState.connections
        .filter { it.folderId == connection.folderId }
        .maxOfOrNull { it.order } ?: -1
    val rootScopeMax = if (connection.folderId == null) {
        // Root scope also includes folders
        maxOf(scopeMax, internalState.folders.maxOfOrNull { it.order } ?: -1)
    } else {
        scopeMax
    }
    connection.order = rootScopeMax + 1
    encryptConnectionPasswords(connection)
    internalState.connections.add(connection)
}
```

- [ ] **Step 2: Modify `addFolder`**

Locate:
```kotlin
fun addFolder(folder: SshFolder) {
    internalState.folders.add(folder)
}
```

Replace with:
```kotlin
fun addFolder(folder: SshFolder) {
    // Folders are always at root; root scope = folders + root connections.
    val foldersMax = internalState.folders.maxOfOrNull { it.order } ?: -1
    val rootConnectionsMax = internalState.connections
        .filter { it.folderId == null }
        .maxOfOrNull { it.order } ?: -1
    folder.order = maxOf(foldersMax, rootConnectionsMax) + 1
    internalState.folders.add(folder)
}
```

- [ ] **Step 3: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/services/SshConnectionStorageService.kt
git commit -m "feat(storage): auto-assign order to new connections and folders"
```

---

## Task 3: Add `placeAt` method to storage service

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/services/SshConnectionStorageService.kt`

- [ ] **Step 1: Add the method**

Add this method at the end of the class (after `reorderFolder`, before the final closing `}`):

```kotlin
    /**
     * Places an item (folder or connection) at [targetOrder] within [parentFolderId].
     * Increments order on every other item in the same scope where order >= targetOrder.
     *
     * For folders, [parentFolderId] is ignored — folders are always placed at root.
     * For a connection, if [parentFolderId] is non-null but no folder with that id
     * exists, this is a no-op.
     *
     * Missing item id is a no-op. [targetOrder] is clamped to non-negative.
     */
    fun placeAt(itemId: String, parentFolderId: String?, targetOrder: Int) {
        val safeOrder = maxOf(targetOrder, 0)
        val folder = internalState.folders.find { it.id == itemId }
        if (folder != null) {
            // Folder placement: always at root scope.
            // Increment order on other folders and root connections where order >= safeOrder.
            for (otherFolder in internalState.folders) {
                if (otherFolder.id != itemId && otherFolder.order >= safeOrder) {
                    otherFolder.order += 1
                }
            }
            for (conn in internalState.connections) {
                if (conn.folderId == null && conn.order >= safeOrder) {
                    conn.order += 1
                }
            }
            folder.order = safeOrder
            return
        }
        val connection = internalState.connections.find { it.id == itemId } ?: return
        // Validate target folder if specified.
        if (parentFolderId != null && internalState.folders.none { it.id == parentFolderId }) {
            return
        }
        // Connection placement scope:
        //   - parentFolderId == null: root scope (folders + root connections).
        //   - parentFolderId != null: connections in that folder.
        if (parentFolderId == null) {
            for (otherFolder in internalState.folders) {
                if (otherFolder.order >= safeOrder) otherFolder.order += 1
            }
            for (otherConn in internalState.connections) {
                if (otherConn.id != itemId && otherConn.folderId == null && otherConn.order >= safeOrder) {
                    otherConn.order += 1
                }
            }
        } else {
            for (otherConn in internalState.connections) {
                if (otherConn.id != itemId && otherConn.folderId == parentFolderId && otherConn.order >= safeOrder) {
                    otherConn.order += 1
                }
            }
        }
        connection.folderId = parentFolderId
        connection.order = safeOrder
    }
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/services/SshConnectionStorageService.kt
git commit -m "feat(storage): add placeAt for unified item ordering"
```

---

## Task 4: On-load order normalization in `loadState`

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/services/SshConnectionStorageService.kt`

This task initializes `order` from current list position on first load after upgrade. Per scope: if all items have `order == 0`, assign sequential 0,1,2,… by current list-position order. Otherwise no-op.

- [ ] **Step 1: Modify `loadState`**

Locate:
```kotlin
override fun loadState(state: State) {
    XmlSerializerUtil.copyBean(state, internalState)
    // Decrypt passwords after loading state
    for (connection in internalState.connections) {
        decryptConnectionPasswords(connection)
    }
}
```

Replace with:
```kotlin
override fun loadState(state: State) {
    XmlSerializerUtil.copyBean(state, internalState)
    // Decrypt passwords after loading state
    for (connection in internalState.connections) {
        decryptConnectionPasswords(connection)
    }
    normalizeOrder()
}

/**
 * For each scope (root, and each folder), if every item has order == 0, assign
 * sequential order values 0, 1, 2, ... in current list-position order. This
 * preserves a user's existing arrangement on first load after upgrade. After
 * any user reorder the scope has at least one non-zero order, so this is a no-op.
 */
private fun normalizeOrder() {
    // --- Root scope: folders (in folders-list order) followed by root connections (in connections-list order). ---
    val rootFolders = internalState.folders
    val rootConnections = internalState.connections.filter { it.folderId == null }
    val rootAllZero = rootFolders.all { it.order == 0 } && rootConnections.all { it.order == 0 }
    if (rootAllZero && (rootFolders.isNotEmpty() || rootConnections.isNotEmpty())) {
        var i = 0
        for (folder in rootFolders) {
            folder.order = i++
        }
        for (conn in rootConnections) {
            conn.order = i++
        }
    }
    // --- Each folder's connection scope. ---
    for (folder in internalState.folders) {
        val children = internalState.connections.filter { it.folderId == folder.id }
        if (children.isEmpty()) continue
        if (children.all { it.order == 0 }) {
            var j = 0
            for (conn in children) {
                conn.order = j++
            }
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/services/SshConnectionStorageService.kt
git commit -m "feat(storage): normalize order on load to preserve existing arrangement"
```

---

## Task 5: Refactor `reloadTree` to sort by order

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt`

This makes the display use the `order` field for sorting. After this task, items still sort correctly because Task 4 normalized order from list position. DnD still works through the OLD storage methods (`moveConnection`/`reorderConnection`/`reorderFolder`) for now, but those manipulate list position only — meaning DnD will appear broken (drops will not move items) after this commit until Task 6 lands.

This is acknowledged: Task 5 alone leaves the project in a state where the DnD drop motion has no effect. **Do not push to other users between Task 5 and Task 6.** Run them as a tight sequence.

- [ ] **Step 1: Replace `reloadTree`**

Locate the existing method (around lines 65–98 of `SshToolWindowPanel.kt`). The current body iterates folders first, then connections. Replace the body with:

```kotlin
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

        // Connections inside folders (including orphans whose folderId points nowhere -> root).
        val orphanRootEntries = mutableListOf<DefaultMutableTreeNode>()
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
            .forEach { orphanRootEntries += DefaultMutableTreeNode(it) }
        orphanRootEntries.forEach { rootNode.add(it) }

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
```

- [ ] **Step 2: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt
git commit -m "feat(ui): sort tree by order field instead of list position"
```

Do NOT smoke-test in the IDE between Tasks 5 and 6 — DnD will be temporarily non-functional. The next task fixes it.

---

## Task 6: Refactor DnD to use `placeAt` with 3-zone folder drop math

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt`

This task replaces `resolveDropTarget` and `applyDrop` and collapses the `DropTarget` sealed class to a single `PlaceItem` data class. After this task, DnD works again via the new model. New behavior:
- Connections can be dropped above/between/below folders at root
- Folders can be dropped above/between/below root connections (symmetric)
- Folder rows have 3 zones: top quarter = above, middle half = into, bottom quarter = below

- [ ] **Step 1: Replace the `DropTarget` sealed class**

Locate the existing `private sealed class DropTarget { ... }` and the two adjacent `private data class DraggedConnection(...)` / `private data class DraggedFolder(...)` declarations. Replace ALL THREE with:

```kotlin
    private data class DraggedConnection(val id: String)
    private data class DraggedFolder(val id: String)

    /**
     * Result of a drop computation: place [itemId] at [targetOrder] within
     * [parentFolderId] (null = root). null return from resolveDropTarget = reject.
     */
    private data class PlaceItem(val itemId: String, val parentFolderId: String?, val targetOrder: Int)
```

- [ ] **Step 2: Replace `resolveDropTarget`**

Locate the existing `private fun resolveDropTarget(x: Int, y: Int, payload: Any?): DropTarget? { ... }`. Replace it AND the helper `siblingConnectionAfter` (which is no longer needed) with:

```kotlin
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
```

- [ ] **Step 3: Replace `applyDrop`**

Locate the existing `private fun applyDrop(payload: Any?, target: DropTarget) { ... }`. Replace with:

```kotlin
    private fun applyDrop(target: PlaceItem) {
        connectionStorageService.placeAt(target.itemId, target.parentFolderId, target.targetOrder)
    }
```

- [ ] **Step 4: Update `installDnD` to use the new types**

Locate `installDnD()` and replace its body with:

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
                applyDrop(target)
                reloadTree()
            }
            .install()
    }
```

- [ ] **Step 5: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Smoke test in IDE**

Run: `./gradlew runIde`
In the launched IDE, open the SSH Connections tool window and verify:

1. **Existing arrangement preserved**: Folders and connections appear in the same order as before the upgrade. (This validates Task 4 normalization.)
2. **Connection above folder at root**: Drag a root-level connection onto the top quarter of a folder row. Connection should land ABOVE that folder at root level.
3. **Connection into folder**: Drag a root-level connection onto the middle half of a folder row. Connection should land INSIDE that folder.
4. **Connection below folder at root**: Drag a root-level connection onto the bottom quarter of a folder row. Connection should land BELOW that folder at root level.
5. **Folder above another folder**: Drag a folder onto the top quarter of another folder row. Folders reorder.
6. **Folder onto folder middle**: Drag a folder onto the middle of another folder row. **Rejected** (no nesting).
7. **Folder above a root connection**: Drag a folder onto the top quarter of a root-level connection row. Folder lands above that connection at root.
8. **Connection above another connection in same folder**: still works as before.

If any of these don't work as expected, STOP and investigate before continuing.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt
git commit -m "feat(ui): refactor DnD to use placeAt with 3-zone folder drop math"
```

---

## Task 7: Preserve `order` in `editConnection`

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt`

The `editConnection` panel method currently preserves `folderId` across edits with `updated.folderId = selectedConnection.folderId`. We need the same for `order`.

- [ ] **Step 1: Modify `editConnection`**

Locate:
```kotlin
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
```

Add one line preserving `order`:

```kotlin
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
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt
git commit -m "fix(ui): preserve connection order across edit"
```

---

## Task 8: Don't carry over `order` in `duplicateConnection`

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt`

The duplicate should append at the end of the original's scope, not stack on top of the original. With `order` in `data class.copy(...)`, the duplicate would inherit the original's `order` — we want to leave it at the default (0) so `addConnection`'s auto-assignment kicks in.

- [ ] **Step 1: Modify `duplicateConnection`**

Locate:
```kotlin
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
```

Replace with:
```kotlin
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
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt
git commit -m "fix(ui): duplicate appends at end of scope rather than stacking on original"
```

---

## Task 9: Remove obsolete storage methods

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/services/SshConnectionStorageService.kt`

After Task 6, the panel no longer calls `moveConnection`, `reorderConnection`, or `reorderFolder`. Remove them from the storage service.

- [ ] **Step 1: Confirm no callers remain**

Run:
```bash
grep -rn "moveConnection\|reorderConnection\|reorderFolder" /Users/michaelfelix/Documents/GitHub/ssh-plugin/src
```

Expected: only matches inside `SshConnectionStorageService.kt` itself (the methods being deleted). If any other file references them, STOP and investigate.

- [ ] **Step 2: Delete the methods**

In `src/main/kotlin/com/github/feelixs/sshplugin/services/SshConnectionStorageService.kt`, find and DELETE these three method blocks (each with its KDoc comment):

```kotlin
/**
 * Moves a connection to a new folder. Pass null for [targetFolderId] to move
 * to root. ...
 */
fun moveConnection(connectionId: String, targetFolderId: String?) {
    ...
}

/**
 * Reorders a connection within the underlying connections list. ...
 */
fun reorderConnection(connectionId: String, newIndexInList: Int) {
    ...
}

/**
 * Reorders a folder within the folders list. ...
 */
fun reorderFolder(folderId: String, newIndexInList: Int) {
    ...
}
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/services/SshConnectionStorageService.kt
git commit -m "chore(storage): remove obsolete moveConnection/reorderConnection/reorderFolder"
```

---

## Task 10: Add drop-indicator state and custom paint

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt`

Add a `dropHint` field and a `DropAwareTree` inner class that paints insert lines (between rows) and folder highlights (drop-into-folder).

- [ ] **Step 1: Add the `DropHint` sealed class and `dropHint` field**

In the panel class, add these declarations near the other private sealed classes (e.g., next to `PlaceItem`):

```kotlin
    private sealed class DropHint {
        /** Draw a horizontal insert line between siblings of [parent] at [insertIndex]. */
        data class InsertLine(val parent: DefaultMutableTreeNode, val insertIndex: Int) : DropHint()
        /** Draw a tinted background on the folder row. */
        data class HighlightFolder(val folderNode: DefaultMutableTreeNode) : DropHint()
    }

    private var dropHint: DropHint? = null
```

- [ ] **Step 2: Add the `DropAwareTree` inner class**

Add this inner class to the panel class (place near the bottom, before `dispose`):

```kotlin
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
            // Determine the y-coordinate of the gap. If insertIndex < childCount,
            // use the top of that child. Otherwise use the bottom of the last child.
            val y = when {
                parent.childCount == 0 -> {
                    // Empty parent: paint at the parent's row bottom (drop into the empty parent).
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
```

- [ ] **Step 3: Switch the tree field to use `DropAwareTree`**

Locate the existing field declarations near the top of the class:
```kotlin
private val rootNode = DefaultMutableTreeNode("ROOT")
private val treeModel = DefaultTreeModel(rootNode)
private val tree = Tree(treeModel)
```

Change the third line to:
```kotlin
private val tree = DropAwareTree(treeModel)
```

- [ ] **Step 4: Add a `dropHintFor(target)` helper and wire it into `installDnD`**

Add this helper to the panel class (near `resolveDropTarget`):

```kotlin
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
```

Then modify the `installDnD` method to set/clear `dropHint`. Replace the existing `installDnD` with:

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
```

- [ ] **Step 5: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Smoke test in IDE**

Run: `./gradlew runIde`
Verify:
- Drag a connection: as you hover over different rows you see EITHER a horizontal blue line (insertion point) OR a tinted folder row (drop-into-folder hint).
- The hint clears when you complete or cancel the drop.
- The hint is accurate: where you see the line is where the item lands.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt
git commit -m "feat(ui): paint drop indicator (insert line / folder highlight) during drag"
```

---

## Task 11: Add drag preview ghost image

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt`

Render the dragged row as a translucent image and attach it to the drag bean so it follows the cursor.

This task involves an SDK API that varies between IntelliJ Platform versions. The implementer should check what's available before committing to one approach.

- [ ] **Step 1: Investigate the available drag-image API**

Run:
```bash
grep -rn "setImageProvider\|withImage\|DnDDragStartBean" /Users/michaelfelix/Documents/GitHub/ssh-plugin/build/idea-sandbox/system 2>/dev/null | head -20
```

If that returns no results, look up the platform docs for the bundled IntelliJ Platform SDK at `platformVersion = 2025.1`. The two known API shapes are:

A. `DnDDragStartBean(attachedObject)` and a separate `DnDSupport.createBuilder(...).setImageProvider { Pair<Image, Point> }.install()` chain step.

B. `DnDDragStartBean(attachedObject)` with an `image` property settable after construction.

Pick whichever exists in the SDK. The plan's preference is option A (`setImageProvider` chain step) since it integrates with the existing builder.

- [ ] **Step 2: Add the ghost-image helper**

Add this helper to the panel class (near `resolveDropTarget`):

```kotlin
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
```

- [ ] **Step 3: Wire the image provider into `installDnD`**

Modify the `installDnD` builder chain. After `.setBeanProvider { ... }`, add a `.setImageProvider { info -> ... }` step that returns the image to display.

The exact code depends on the API discovered in Step 1. For option A (preferred):

```kotlin
            .setImageProvider { info ->
                val path = tree.getPathForLocation(info.point.x, info.point.y)
                    ?: return@setImageProvider null
                val img = renderGhostImage(path) ?: return@setImageProvider null
                com.intellij.ide.dnd.DnDImage(img, java.awt.Point(0, 0))
            }
```

If `DnDImage` doesn't exist in this SDK and the API expects a raw `Pair<Image, Point>` or just an `Image`, adjust accordingly.

If neither API form is available cleanly: SKIP this task entirely, document the deferral in a `DONE_WITH_CONCERNS` report, and the platform's default drag visual will be used. Drop indicators (Task 10) are the more important visual.

- [ ] **Step 4: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

If the build fails due to a missing class (`DnDImage`) or method (`setImageProvider`), STOP and report BLOCKED with the exact error. Do not improvise — this API is the documented risk in the spec.

- [ ] **Step 5: Smoke test**

Run: `./gradlew runIde`. Drag a connection: a translucent ghost image should follow the cursor while dragging.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt
git commit -m "feat(ui): show translucent ghost image of dragged item"
```

If the task was skipped due to API unavailability, commit with a `chore: ` prefix instead, noting the deferral in the message body.

---

## Task 12: Double-click connection to connect

**Files:**
- Modify: `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt`

Install a mouse listener on the tree. Double-clicking a connection node calls the executor; double-clicking elsewhere falls through to the tree's default handling (toggle expand/collapse).

- [ ] **Step 1: Add `installDoubleClickHandler` method**

Add this method to the panel class (near `installDnD`):

```kotlin
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
```

- [ ] **Step 2: Call it from `setupUI`**

Locate the end of `setupUI()` (currently calls `installPopupMenu()` then `installDnD()`). Add one more line after `installDnD()`:

```kotlin
        installDoubleClickHandler()
```

- [ ] **Step 3: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Smoke test**

Run: `./gradlew runIde`. Verify:
- Double-click a connection → terminal opens, asterisk indicator appears in the tree.
- Double-click a folder → folder expands/collapses (no connection initiated).
- Single-click on either type → just selects.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt
git commit -m "feat(ui): double-click connection to initiate the connection"
```

---

## Task 13: Final smoke test

- [ ] **Step 1: Clean build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: End-to-end smoke test**

Run: `./gradlew runIde`. In the launched IDE, walk through every user-facing flow:

1. Open the SSH Connections tool window. Existing folders/connections appear in the same arrangement as before the upgrade (Task 4 normalization preserved order).
2. Add a new connection — appears at the end of its scope (root or selected folder).
3. Add a new folder — appears at the end of root.
4. Drag a connection between two folders → drops where the indicator line shows.
5. Drag a connection ABOVE a folder (top quarter) → connection lands above folder at root.
6. Drag a connection INTO a folder (middle half) → connection lands inside folder, indicator highlights folder.
7. Drag a connection BELOW a folder (bottom quarter) → connection lands below folder at root.
8. Drag a folder above another root connection → folder lands above that connection.
9. Drag a folder onto another folder middle → drop is rejected (no nesting).
10. Drop indicator (line/highlight) is visible during all valid drags and clears on release/cancel.
11. Translucent ghost image of the dragged item follows the cursor (or platform default if Task 11 was deferred).
12. Double-click a connection → terminal opens.
13. Double-click a folder → expand/collapse.
14. Edit a connection (toolbar action) → after save, connection stays in its original position (order preserved).
15. Duplicate a connection → copy appears immediately after the original in the same scope.
16. Delete a folder with "move to root" → contained connections appear at the end of root.
17. Restart `runIde`, reopen tool window — all positions persist correctly.

No commit — verification only.
