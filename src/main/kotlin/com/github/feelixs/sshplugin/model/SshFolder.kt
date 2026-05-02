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
