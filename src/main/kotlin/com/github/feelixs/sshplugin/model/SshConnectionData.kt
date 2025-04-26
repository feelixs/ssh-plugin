package com.github.feelixs.sshplugin.model

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag

// Represents the data for a single SSH connection configuration.
@Tag("SshConnection")
data class SshConnectionData(
    @Attribute("id") var id: String = java.util.UUID.randomUUID().toString(), // Unique identifier
    @Attribute("alias") var alias: String = "", // User-friendly name
    @Attribute("host") var host: String = "",
    @Attribute("port") var port: Int = 22,
    @Attribute("username") var username: String = "",
    // Password will be handled securely, not stored directly as plain text attribute
    var encodedPassword: String? = null, // Placeholder for securely stored password
    @Attribute("osType") var osType: OsType = OsType.LINUX,
    @Attribute("runCommands") var runCommands: Boolean = false, // Whether to run commands after connection
    @Attribute("commands") var commands: String = "", // Commands to run after successful connection
    var encodedSudoPassword: String? = null, // Placeholder for securely stored sudo password (kept for backwards compatibility)
    @Attribute("useUserPasswordForSudo") var useUserPasswordForSudo: Boolean = false, // Whether to use the user password for sudo commands
    @Attribute("useKey") var useKey: Boolean = false, // Whether to use SSH key authentication
    @Attribute("keyPath") var keyPath: String = "", // Path to the SSH key file
    var encodedKeyPassword: String? = null, // Placeholder for securely stored key password
    @Attribute("maximizeTerminal") var maximizeTerminal: Boolean = false // Whether to maximize terminal on connect
) {
    // Default constructor for XML serialization
    constructor() : this(id = java.util.UUID.randomUUID().toString())
    
    // For backward compatibility
    @Deprecated("Use runCommands and commands instead")
    var useSudo: Boolean
        get() = runCommands && commands.contains("sudo -s")
        set(value) {
            if (value) {
                runCommands = true
                if (!commands.contains("sudo -s")) {
                    commands = if (commands.isBlank()) "sudo -s" else "sudo -s\n$commands"
                }
            } else {
                commands = commands.replace("sudo -s\n", "").replace("sudo -s", "")
                if (commands.isBlank()) {
                    runCommands = false
                }
            }
        }
}

enum class OsType {
    LINUX, WINDOWS
}
