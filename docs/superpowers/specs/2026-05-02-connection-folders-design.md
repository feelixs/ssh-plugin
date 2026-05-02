# Connection Folders — Design

**Date:** 2026-05-02
**Status:** Approved (pending implementation plan)

## Problem

The SSH Connections tool window currently shows a flat `JBList` of all configured connections. Users with many connections cannot organize them into logical groupings (e.g., "Production", "Staging", "Personal"), and cannot reorder connections. We want browser-bookmark-bar-style folders: create folders, drag connections into them, reorder things.

## Goals

1. Users can create, rename, and delete folders.
2. Connections can live at the root of the tool window or inside one folder.
3. Users can drag connections into folders, drag them out, and reorder them.
4. Existing user data (flat list of connections) continues to work without manual migration.

## Non-goals (YAGNI)

- Nested folders (folders inside folders).
- Multi-select drag and drop.
- Folder color or icon customization.
- "Connect all" or other folder-level batch actions.
- Search or filtering across folders.

## Data Model

### `SshFolder` (new)

```kotlin
@Tag("SshFolder")
data class SshFolder(
    @Attribute("id") var id: String = UUID.randomUUID().toString(),
    @Attribute("name") var name: String = ""
)
```

### `SshConnectionData` (modified)

A new field is added:

```kotlin
@Attribute("folderId") var folderId: String? = null  // null = root
```

All other fields remain unchanged.

### `SshConnectionStorageService.State` (modified)

```kotlin
class State {
    @XCollection(style = XCollection.Style.v2)
    var connections: MutableList<SshConnectionData> = mutableListOf()

    @XCollection(style = XCollection.Style.v2)
    var folders: MutableList<SshFolder> = mutableListOf()
}
```

### Migration

No code-level migration is required:
- Existing XML files have no `folderId` attribute on connections — Kotlin's default value (`null`) applies, so all existing connections render at the root.
- Existing XML files have no `<folders>` element — `folders` defaults to an empty list.

### Display order

Order in the persisted lists is the display order:
- Folders render first, in `folders` list order.
- Then root-level connections (those whose `folderId == null`), in `connections` list order.
- Inside each folder, connections render in `connections` list order, filtered by `folderId`.

## Storage Service API

`SshConnectionStorageService` gains:

```kotlin
fun getFolders(): List<SshFolder>
fun addFolder(folder: SshFolder)
fun renameFolder(id: String, newName: String)
fun removeFolder(id: String, deleteContainedConnections: Boolean)
fun moveConnection(connectionId: String, targetFolderId: String?)  // null = root
fun reorderConnection(connectionId: String, newIndexInList: Int)
fun reorderFolder(folderId: String, newIndexInList: Int)
```

`removeFolder` semantics:
- If `deleteContainedConnections == true`: each contained connection is removed via the existing `removeConnection(id)` path, which also purges its passwords from `PasswordSafe`. Then the folder itself is removed.
- If `deleteContainedConnections == false`: every contained connection has its `folderId` set to `null` (moved to root). Then the folder itself is removed.

`moveConnection` to a `targetFolderId` that doesn't exist is a no-op — it must not throw, and the connection's `folderId` is left unchanged.

`reorderConnection` / `reorderFolder` use simple list `removeAt` + `add(newIndex, …)` semantics. `newIndexInList` is the index in the underlying `connections` (or `folders`) list, not a UI index.

## UI Architecture

### Tree replaces list

`SshToolWindowPanel` switches from `JBList<SshConnectionData>` + `DefaultListModel` to `com.intellij.ui.treeStructure.Tree` + `DefaultTreeModel`. Tree details:

- Root is a hidden `DefaultMutableTreeNode` (`tree.isRootVisible = false`, `tree.showsRootHandles = true`).
- Folder nodes: `DefaultMutableTreeNode` with `userObject = SshFolder`.
- Connection nodes: `DefaultMutableTreeNode` with `userObject = SshConnectionData`.
- Tree is rebuilt from storage on every `loadConnections()` (renamed `reloadTree()`), preserving the selected node id and folder expansion state across rebuilds.

### Cell renderer

A new `SshConnectionTreeCellRenderer` (subclass of `com.intellij.ui.ColoredTreeCellRenderer`) replaces `SshConnectionListCellRenderer`. Behavior:

- For `SshFolder` user objects: icon `AllIcons.Nodes.Folder`, name in regular attributes.
- For `SshConnectionData` user objects: identical formatting to today's list cell renderer (active session asterisk, host info, `[key]`, `[auto-sudo]` markers, OS-type icon).

The existing `SshConnectionListCellRenderer.kt` is deleted once the tree renderer is in place.

### Toolbar

A new action `SSHPlugin.NewFolder` is added to `SSHPlugin.ToolWindow.Toolbar` (declared in `plugin.xml`) alongside the existing actions. Use `AllIcons.Nodes.Folder` for folder icons (consistent with the cell renderer). The `CLAUDE.md` guidance is to prefer `AllIcons.General.*` over `AllIcons.Actions.*` — folder-specific icons under `AllIcons.Nodes.*` are fine, matching the existing renderer's use of `AllIcons.RunConfigurations.*` and `AllIcons.FileTypes.*`.

### "Add Connection" target folder

`AddConnectionAction` is updated so the new connection's `folderId` is set based on the current selection in the tree:
- If a folder node is selected → `folderId = thatFolder.id`.
- If a connection node is selected → `folderId = thatConnection.folderId` (same folder as the selected connection).
- Otherwise → `folderId = null` (root).

### Context menu

A right-click popup menu is added to the tree:
- On a connection node: existing actions only (Connect, Edit, Duplicate, Delete, Disconnect, Copy Sudo Password). Moving a connection between folders is done via drag-and-drop only.
- On a folder node: **"Rename Folder…"**, **"Delete Folder…"**, **"New Folder"**.
- On empty space: **"New Folder"**, **"Add Connection"**.

### Drag and drop

Implemented with `com.intellij.ide.dnd.DnDSupport`. Drop semantics:

| Dragged item   | Drop target                       | Result                                           |
| -------------- | --------------------------------- | ------------------------------------------------ |
| Connection     | Folder node                       | `moveConnection(connId, folderId)`               |
| Connection     | Between two connection siblings   | `moveConnection` to that parent + `reorderConnection` to the index |
| Connection     | Root (empty space or root insert) | `moveConnection(connId, null)`                   |
| Folder         | Between two folders at root       | `reorderFolder` to the new index                 |
| Folder         | On / inside another folder        | **Rejected** (no nesting)                        |
| Folder         | Inside its own connection child   | **Rejected**                                     |

After any successful drop, `reloadTree()` is called.

## Folder-deletion flow

When the user triggers "Delete Folder…":

- If the folder is empty: standard yes/no confirm ("Delete folder 'X'?").
- If the folder contains connections: a custom three-button dialog using `Messages.showDialog(...)`:
  - Title: "Delete Folder"
  - Message: "Folder 'X' contains N connection(s). What would you like to do?"
  - Buttons: `["Delete folder only (move connections to root)", "Delete folder and all connections", "Cancel"]`
  - Default: "Cancel".
  - Mapped to `removeFolder(id, deleteContainedConnections = false)`, `removeFolder(id, deleteContainedConnections = true)`, or no-op respectively.

## Error handling

- `moveConnection`, `reorderConnection`, `reorderFolder`, `renameFolder`, `removeFolder` are no-ops when the referenced id is not found. They do not throw.
- DnD rejects invalid drops at the `canHandleDrop` stage so the UI shows the standard "no-drop" cursor.
- A connection whose `folderId` references a folder that no longer exists (e.g., from a manually edited XML file) is rendered at the root, and on the next mutation of that connection its `folderId` is rewritten to `null`. This is a defensive read-side fix only — the application code never produces this state.

## Testing

Tests live in `src/test/kotlin/com/github/feelixs/sshplugin/MyPluginTest.kt` (or a new file alongside it). Required cases:

- **Storage:**
  - `addFolder` then `getFolders` returns it.
  - `renameFolder` updates the name; renaming a missing id is a no-op.
  - `removeFolder(id, deleteContainedConnections = false)` moves contained connections to root and removes the folder.
  - `removeFolder(id, deleteContainedConnections = true)` removes both the folder and its connections, **and** purges their `PasswordSafe` entries (verify by inspecting the credential store after the call, mirroring how `removeConnection` is tested today).
  - `moveConnection(connId, folderId)` updates `folderId`.
  - `moveConnection(connId, "non-existent-id")` is a no-op — does not throw and leaves `folderId` unchanged.
  - `reorderConnection` and `reorderFolder` change list order.
- **Migration:**
  - Loading state from an XML payload with no `<folders>` element and no `folderId` attributes yields all connections at root and an empty folder list.
- **Orphan tolerance:**
  - A connection whose `folderId` references a non-existent folder is exposed at the root by the tree-building helper.

UI tests are not required for this change (existing project does not exercise the tool window in tests).

## Files changed (summary)

**New:**
- `src/main/kotlin/com/github/feelixs/sshplugin/model/SshFolder.kt`
- `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshConnectionTreeCellRenderer.kt`
- `src/main/kotlin/com/github/feelixs/sshplugin/actions/NewFolderAction.kt`
- `src/main/kotlin/com/github/feelixs/sshplugin/actions/RenameFolderAction.kt`
- `src/main/kotlin/com/github/feelixs/sshplugin/actions/DeleteFolderAction.kt`

**Modified:**
- `src/main/kotlin/com/github/feelixs/sshplugin/model/SshConnectionData.kt` — add `folderId`
- `src/main/kotlin/com/github/feelixs/sshplugin/services/SshConnectionStorageService.kt` — add folder API
- `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshToolWindowPanel.kt` — switch to tree, add DnD, add context menu, add target-folder logic to `addConnection`
- `src/main/kotlin/com/github/feelixs/sshplugin/actions/AddConnectionAction.kt` — pass target folder context
- `src/main/kotlin/com/github/feelixs/sshplugin/actions/PluginDataKeys.kt` — possibly add `SELECTED_SSH_FOLDER` data key
- `src/main/resources/META-INF/plugin.xml` — register new actions in toolbar

**Deleted:**
- `src/main/kotlin/com/github/feelixs/sshplugin/toolWindow/SshConnectionListCellRenderer.kt`
