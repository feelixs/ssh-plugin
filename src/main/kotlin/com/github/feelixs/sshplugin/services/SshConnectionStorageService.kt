package com.github.feelixs.sshplugin.services

import com.github.feelixs.sshplugin.model.SshConnectionData
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection

// Service responsible for persisting SSH connection configurations.
@State(
    name = "com.github.feelixs.sshplugin.services.SshConnectionStorageService",
    storages = [Storage("sshPluginConnections.xml")]
)
@Service(Service.Level.APP)
class SshConnectionStorageService : PersistentStateComponent<SshConnectionStorageService.State> {

    // Inner class to hold the state (list of connections)
    class State {
        @XCollection(style = XCollection.Style.v2)
        var connections: MutableList<SshConnectionData> = mutableListOf()
    }

    private var internalState = State()

    companion object {
        val instance: SshConnectionStorageService
            get() = ApplicationManager.getApplication().getService(SshConnectionStorageService::class.java)
    }

    override fun getState(): State {
        // TODO: Implement password encryption before returning state
        return internalState
    }

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, internalState)
        // TODO: Implement password decryption after loading state
    }

    // --- Public API for managing connections ---

    fun getConnections(): List<SshConnectionData> {
        return internalState.connections.toList() // Return immutable copy
    }

    fun addConnection(connection: SshConnectionData) {
        internalState.connections.add(connection)
    }

    fun updateConnection(connection: SshConnectionData) {
        val index = internalState.connections.indexOfFirst { it.id == connection.id }
        if (index != -1) {
            internalState.connections[index] = connection
        }
    }

    fun removeConnection(id: String) {
        internalState.connections.removeIf { it.id == id }
    }
}
