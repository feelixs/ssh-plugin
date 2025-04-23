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
    @Attribute("useSudo") var useSudo: Boolean = false,
    var encodedSudoPassword: String? = null // Placeholder for securely stored sudo password
) {
    // Default constructor for XML serialization
    constructor() : this(id = java.util.UUID.randomUUID().toString())
}

enum class OsType {
    LINUX, WINDOWS
}
