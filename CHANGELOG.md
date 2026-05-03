<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# ssh-plugin Changelog

## [Unreleased]

## [0.3.0] - 2026-05-02

### Added

- Folders for organizing SSH connections in the tool window
- Drag and drop to move connections between folders, reorder connections within a folder, and reorder folders
- New Folder toolbar button
- Right-click context menu with Rename Folder and Delete Folder actions
- Three-button confirm when deleting a non-empty folder (move connections to root, delete everything, or cancel)

### Changed

- Tool window now uses a tree view instead of a flat list

## [0.2.1] - 2026-04-27

### Fixed

- Stale password no longer typed into the terminal after switching a profile from password auth to SSH key auth
- User password field is now disabled and cleared when "Use SSH key authentication" is selected

## [0.2.0]

### Added

- Show/hide password toggle (eye icon) on all password fields
- Startup commands now run even when no password is defined
- MIT License and EULA

### Fixed

- Removed misleading "no password provided" warning for passwordless connections

## [0.1.9]

### Added

- Increase delay before password entry

## [0.1.8]

### Added

- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

## [0.1.6]

### Added

- Fix bug where password isn't saved

## [0.1.5]

### Added

- Notification when no password exists for the connection.

[Unreleased]: https://github.com/feelixs/ssh-plugin/compare/0.3.0...HEAD
[0.3.0]: https://github.com/feelixs/ssh-plugin/compare/0.2.1...0.3.0
[0.2.1]: https://github.com/feelixs/ssh-plugin/compare/0.2.0...0.2.1
[0.2.0]: https://github.com/feelixs/ssh-plugin/compare/0.1.9...0.2.0
[0.1.9]: https://github.com/feelixs/ssh-plugin/compare/0.1.8...0.1.9
[0.1.8]: https://github.com/feelixs/ssh-plugin/compare/0.1.6...0.1.8
[0.1.6]: https://github.com/feelixs/ssh-plugin/compare/0.1.5...0.1.6
[0.1.5]: https://github.com/feelixs/ssh-plugin/commits/0.1.5
