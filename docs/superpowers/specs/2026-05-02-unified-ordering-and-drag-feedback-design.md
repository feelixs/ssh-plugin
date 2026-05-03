# Unified Ordering, Drag Feedback, and Double-Click — Design

**Date:** 2026-05-02
**Status:** Approved (pending implementation plan)
**Builds on:** `2026-05-02-connection-folders-design.md`

## Problem

The folders feature shipped in v0.3.0 has three usability gaps:

1. **Connections cannot live between or above folders at the root level.** The display and storage model groups all folders before all connections, with no shared ordering. Users cannot, for example, put a "favorites" connection above their first folder.
2. **No visual feedback during drag.** Users see only the OS cursor change. There is no ghost image of the dragged row, and no indicator showing where the item will land.
3. **Connecting requires multiple clicks.** Users must select a connection and then click the Connect toolbar button (or Enter via context menu). Double-clicking a connection should initiate the connection directly.

## Goals

1. Connections and folders at the root can be interleaved in any order via drag-and-drop.
2. Folders can also be dragged into positions between root connections (symmetric model).
3. While dragging, the user sees a translucent ghost image of the dragged row and a clear visual indicator of where it will land (insert line for between-rows, row highlight for drop-into-folder).
4. Double-clicking a connection node initiates the connection (same effect as the Connect action).
5. Existing user data (current folder/connection layout) is preserved on first load — no manual migration.

## Non-goals (YAGNI)

- Up/down drag-handle icons on each row (decided against — row-drag is sufficient).
- Multi-select drag.
- Keyboard shortcuts for reordering (e.g., Alt+Up/Down).
- Nested folders (still explicitly out of scope).
- Animated reorder transitions.
- Any change to existing dialogs, action behaviors, or password storage.

## Data model

### `SshFolder` (modified)

Add one field:
```kotlin
@Attribute("order") var order: Int = 0
```

### `SshConnectionData` (modified)

Add one field:
```kotlin
@Attribute("order") var order: Int = 0
```

### Migration / on-load normalization

Existing XML files have no `order` attribute, so all items load with `order = 0`. To preserve the user's existing visual order, `SshConnectionStorageService.loadState` runs a one-time normalization after the existing decrypt loop:

```
For each "scope" (root, and each folder.id):
  Collect items in that scope (folders by folders-list position; root connections by connections-list position; connections inside a folder by connections-list position).
  If every item in the scope has order == 0:
    Assign sequential order values 0, 1, 2, ... in current list-position order.
```

Once any user reorder happens, at least one item in that scope will have a non-zero `order`, so the normalization is a no-op on subsequent loads. The check is per-scope, so a freshly-created folder (whose contents are still all-zero) gets normalized correctly on the next load.

### Display order

- **Root scope** — folders + root connections (those whose `folderId == null`) are merged and sorted by `(order ascending)`. Folders no longer always render before connections.
- **Inside a folder** — connections whose `folderId == folder.id` are sorted by `(order ascending)`.

## Storage Service API

### Replace three methods with one

Remove:
- `moveConnection(connectionId, targetFolderId)`
- `reorderConnection(connectionId, newIndexInList)`
- `reorderFolder(folderId, newIndexInList)`

Add:
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
fun placeAt(itemId: String, parentFolderId: String?, targetOrder: Int)
```

Implementation outline:
1. Determine if `itemId` is a folder or connection (search both lists). Missing → return.
2. If connection and `parentFolderId` references a non-existent folder, return.
3. Determine effective parent scope:
   - For a folder: scope is "root" (the folder list itself plus root connections — connections whose `folderId == null`).
   - For a connection: scope is items whose location matches `parentFolderId` (root if null; folder contents if non-null).
4. Increment `order` by 1 on every item in that scope **other than the moved item itself** where `order >= max(targetOrder, 0)`.
5. Set the moved item's `order = max(targetOrder, 0)`. For a connection, also set `folderId = parentFolderId`.

**Worked example:** Items in scope have orders `[A=0, B=1, C=2, D=3]`. User drags A below C (target order = 3).
- Step 4: increment items other than A with `order >= 3` → `D: 3 → 4`. Scope is now `[A=0, B=1, C=2, D=4]`.
- Step 5: set `A.order = 3`. Scope is `[B=1, C=2, A=3, D=4]`. Sorted display: B, C, A, D. ✓

### Modify `addConnection` and `addFolder`

When a new item is added, its `order` is set to one more than the current max in its target scope (so it appends to the end of that scope). If the scope is empty, the new item gets `order = 0`. For folders, the scope is "root". For connections, the scope is determined by the connection's `folderId` (which `addConnection`'s caller will already have set, e.g., from `targetFolderIdForNewConnection` in the panel).

### Caller responsibilities for preserving `order` across edit and duplicate

`updateConnection` is unchanged — it overwrites the stored connection with whatever the caller passes. So the panel's `editConnection` must explicitly preserve `order` (and `folderId`, as today) on the updated object before calling `updateConnection`. Same as today's `folderId` preservation pattern, just one extra line.

`duplicateConnection` in the panel: do NOT carry over the original's `order` to the copy. Instead, the new connection should append to the end of the original's scope. Easiest: leave `duplicated.order` at its default (0) when constructing the copy, then call `addConnection(duplicated)` — `addConnection`'s logic above will set `order = (max in scope) + 1` so the duplicate lands immediately after the rest. The duplicate's `folderId` is still preserved from the original.

### Other methods unchanged

`getFolders`, `getConnections`, `renameFolder`, `removeFolder`, `removeConnection`, `updateConnection`, `getConnectionWithPlainPasswords`, `generateSshCommand`, encryption helpers — no change.

## UI Architecture

### Tree-build changes (`SshToolWindowPanel.reloadTree`)

Replace the existing "iterate folders, then iterate root connections" code with unified-sort logic:

```kotlin
val rootFolders = connectionStorageService.getFolders()
val allConnections = connectionStorageService.getConnections()

val rootEntries = mutableListOf<Pair<Int, DefaultMutableTreeNode>>()
for (folder in rootFolders) {
    rootEntries += folder.order to DefaultMutableTreeNode(folder)
}
for (conn in allConnections.filter { it.folderId == null }) {
    rootEntries += conn.order to DefaultMutableTreeNode(conn)
}
rootEntries.sortedBy { it.first }.forEach { (_, node) -> rootNode.add(node) }

// For folder children, build map by folder id, sort connections by order
val folderNodeById = mutableMapOf<String, DefaultMutableTreeNode>()
rootNode.children().asSequence().forEach { node ->
    val n = node as DefaultMutableTreeNode
    val obj = n.userObject
    if (obj is SshFolder) folderNodeById[obj.id] = n
}
for ((folderId, folderNode) in folderNodeById) {
    allConnections
        .filter { it.folderId == folderId }
        .sortedBy { it.order }
        .forEach { folderNode.add(DefaultMutableTreeNode(it)) }
}
```

(Final implementation may iterate differently, but the semantics are: folders + root connections sorted by `order` at root, connections sorted by `order` within their folder. Orphan tolerance — connection whose `folderId` references a missing folder — still falls back to root and participates in root sort.)

### Drop-zone math (`SshToolWindowPanel.resolveDropTarget`)

Drop targets are computed from `(x, y)` and the dragged payload (connection or folder).

#### Zone calculation per row

For a row with bounds `rowBounds`:
```kotlin
val relY = y - rowBounds.y
val zoneAbove   = relY < rowBounds.height / 4         // top quarter
val zoneInto    = !zoneAbove && relY < rowBounds.height * 3 / 4  // middle half
val zoneBelow   = !zoneAbove && !zoneInto             // bottom quarter
```

#### Drop semantics matrix

| Dragged item | Drop target row userObject | Zone | Resulting `placeAt` call |
|---|---|---|---|
| Connection | Folder | above | `placeAt(connId, parentFolderId = null, targetOrder = folder.order)` |
| Connection | Folder | into | `placeAt(connId, parentFolderId = folder.id, targetOrder = (max conn.order in folder) + 1)` (append) |
| Connection | Folder | below | `placeAt(connId, parentFolderId = null, targetOrder = folder.order + 1)` |
| Connection | Connection | above | `placeAt(connId, parentFolderId = targetConn.folderId, targetOrder = targetConn.order)` |
| Connection | Connection | below | `placeAt(connId, parentFolderId = targetConn.folderId, targetOrder = targetConn.order + 1)` |
| Connection | (empty space) | — | `placeAt(connId, parentFolderId = null, targetOrder = (max root order) + 1)` (append; if root scope is empty, target = 0) |
| Connection | self | (any) | rejected (return null) |
| Folder | Folder | above | `placeAt(folderId, null, targetOrder = targetFolder.order)` |
| Folder | Folder | into | **rejected** (no nesting) |
| Folder | Folder | below | `placeAt(folderId, null, targetOrder = targetFolder.order + 1)` |
| Folder | Connection | above | `placeAt(folderId, null, targetOrder = targetConn.order)` (only valid if `targetConn.folderId == null`; otherwise rejected) |
| Folder | Connection | below | `placeAt(folderId, null, targetOrder = targetConn.order + 1)` (only valid if `targetConn.folderId == null`; otherwise rejected) |
| Folder | (empty space) | — | `placeAt(folderId, null, targetOrder = (max root order) + 1)` |
| Folder | self | (any) | rejected |

A folder dropped onto a connection that is *inside another folder* is rejected because folders cannot be placed inside folders, and there is no obvious "root position" implied by that drop point.

#### `DropTarget` sealed class — collapsed

The previous three-arm sealed class collapses to a single case:

```kotlin
private data class PlaceItem(val itemId: String, val parentFolderId: String?, val targetOrder: Int)
```

`resolveDropTarget` returns `PlaceItem?` (null = reject).

### Drop indicator painting

Track current drop hint in the panel:

```kotlin
private var dropHint: DropHint? = null

private sealed class DropHint {
    /** Draw a horizontal insert line between siblings of [parent] at [insertIndex]. */
    data class InsertLine(val parent: DefaultMutableTreeNode, val insertIndex: Int) : DropHint()
    /** Draw a tinted background on the folder row. */
    data class HighlightFolder(val folderNode: DefaultMutableTreeNode) : DropHint()
}
```

`setTargetChecker` computes the `PlaceItem`, derives a corresponding `DropHint`, assigns `dropHint`, and calls `tree.repaint()`. `setDropHandler` clears `dropHint` after dispatching the drop. A drag-over event with no valid target also clears it.

**Custom paint:** Use a `Tree` subclass declared inline in the panel file, overriding `paintComponent`:

```kotlin
private inner class DropAwareTree(model: TreeModel) : Tree(model) {
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val hint = dropHint ?: return
        val g2 = g.create() as Graphics2D
        try {
            when (hint) {
                is DropHint.InsertLine -> paintInsertLine(g2, hint)
                is DropHint.HighlightFolder -> paintFolderHighlight(g2, hint)
            }
        } finally {
            g2.dispose()
        }
    }
}
```

**Insert line:** 2-px horizontal line at the y-coordinate of the gap between rows. Computed from `getPathBounds(child[insertIndex])` (or row-after-last-child if `insertIndex == childCount`). Spans the tree width minus a small left/right inset matching the tree's indentation.

**Folder highlight:** filled rectangle at the row bounds, semi-transparent. Use `JBColor` with manual alpha:
```kotlin
Color(JBColor.BLUE.red, JBColor.BLUE.green, JBColor.BLUE.blue, 60)
```

(If `JBUI.CurrentTheme.Tree` exposes a drop-target color in this SDK version, use it instead. The plan task will check at implementation time and pick whichever exists; both are acceptable.)

### Drag preview (ghost image)

In `installDnD`'s `setBeanProvider` lambda (or via `DnDSupport.setImageProvider` if available), construct a translucent `BufferedImage` from the rendered cell:

```kotlin
val component = tree.cellRenderer.getTreeCellRendererComponent(
    tree, node, /* selected */ false, /* expanded */ false,
    /* leaf */ node.isLeaf, /* row */ tree.getRowForPath(path), /* hasFocus */ false
)
component.size = component.preferredSize
val img = ImageUtil.createImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
val g = img.createGraphics()
g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)
component.paint(g)
g.dispose()
```

The image is attached to the drag start bean via the SDK-version-appropriate API. **Implementation risk:** the exact API for attaching a custom drag image to `DnDDragStartBean` versus through a separate `DnDSupport.setImageProvider` builder method varies between IntelliJ Platform versions. The implementer should pick whichever signature is available in the targeted SDK (`platformVersion = 2025.1`). If neither is straightforward, fall back to the platform's default drag visual (no custom ghost) and note the deferral in the commit message — the drop indicator (lines/highlight) is the more important of the two visuals.

### Double-click connection to connect

In `setupUI()`, after `installDnD()`, install a mouse listener on the tree:

```kotlin
tree.addMouseListener(object : MouseAdapter() {
    override fun mouseClicked(e: MouseEvent) {
        if (e.clickCount != 2 || e.button != MouseEvent.BUTTON1) return
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val conn = node.userObject as? SshConnectionData ?: return
        executor.executeConnection(conn.id)
    }
})
```

Notes:
- Double-clicking a folder row continues to use the tree's default behavior (toggle expand/collapse). The mouse listener returns early because the userObject is not `SshConnectionData`.
- Single-click and right-click behaviors are unaffected.

## Error handling

- `placeAt` with missing item id, missing folder id, or negative `targetOrder` (clamped to 0) is a no-op. No throws.
- DnD `setTargetChecker` sets `event.isDropPossible = false` when `resolveDropTarget` returns null, giving the user the platform's standard no-drop cursor.
- A connection whose `folderId` references a non-existent folder is rendered at root (orphan tolerance preserved from v0.3.0).
- Drop-hint painting wraps in try/finally to ensure the `Graphics2D` copy is disposed even on exception.

## Files changed (summary)

**Modified:**
- `model/SshFolder.kt` — add `order` field
- `model/SshConnectionData.kt` — add `order` field
- `services/SshConnectionStorageService.kt` — add `placeAt`, remove `moveConnection`/`reorderConnection`/`reorderFolder`, modify `addConnection`/`addFolder` to set `order` on new items, add on-load normalization in `loadState`
- `toolWindow/SshToolWindowPanel.kt` — unified-sort `reloadTree`, new drop-zone math + collapsed `PlaceItem`, drop-hint state, `DropAwareTree` inner class with custom paint, drag preview image, double-click mouse listener

**No new or deleted files.**

## Out of scope (recap)

- Drag handle icons
- Multi-select
- Keyboard reorder shortcuts
- Nested folders
- Reorder animation
- Any storage migration code (on-load normalization is a one-time read-time computation, not a versioned migration)
