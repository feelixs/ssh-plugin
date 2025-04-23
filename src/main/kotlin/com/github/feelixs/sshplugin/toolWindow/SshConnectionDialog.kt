package com.github.feelixs.sshplugin.toolWindow

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridLayout
import javax.swing.*

/**
 * Dialog for adding or editing SSH connections.
 */
class SshConnectionDialog(
    private val project: Project,
    private val existingConnection: SshConnectionData? = null
) : DialogWrapper(project) {

    private val aliasField = JBTextField(existingConnection?.alias ?: "", 20)
    private val hostField = JBTextField(existingConnection?.host ?: "", 20)
    private val portField = JBTextField(existingConnection?.port?.toString() ?: "22", 5)
    private val usernameField = JBTextField(existingConnection?.username ?: "", 20)
    private val passwordField = JBPasswordField()
    private val linuxRadioButton = JRadioButton("Linux")
    private val windowsRadioButton = JRadioButton("Windows")
    private val useSudoCheckbox = JBCheckBox("Use sudo", existingConnection?.useSudo ?: false)
    private val sudoPasswordField = JBPasswordField()
    
    init {
        title = if (existingConnection == null) "Add SSH Connection" else "Edit SSH Connection"
        
        // Set the current password as a placeholder - not visible but for editing purposes
        if (existingConnection?.encodedPassword != null) {
            passwordField.text = existingConnection.encodedPassword
        }
        
        if (existingConnection?.encodedSudoPassword != null) {
            sudoPasswordField.text = existingConnection.encodedSudoPassword
        }
        
        // Set OS type radio button
        val osButtonGroup = ButtonGroup()
        osButtonGroup.add(linuxRadioButton)
        osButtonGroup.add(windowsRadioButton)
        
        if (existingConnection?.osType == OsType.WINDOWS) {
            windowsRadioButton.isSelected = true
        } else {
            linuxRadioButton.isSelected = true
        }
        
        // Make sudo password field enabled only when useSudo is checked
        sudoPasswordField.isEnabled = useSudoCheckbox.isSelected
        useSudoCheckbox.addActionListener {
            sudoPasswordField.isEnabled = useSudoCheckbox.isSelected
        }
        
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        // Create OS type panel
        val osTypePanel = JPanel(BorderLayout())
        val osRadioButtonPanel = JPanel(GridLayout(1, 2))
        osRadioButtonPanel.add(linuxRadioButton)
        osRadioButtonPanel.add(windowsRadioButton)
        osTypePanel.add(JBLabel("Server OS Type:"), BorderLayout.WEST)
        osTypePanel.add(osRadioButtonPanel, BorderLayout.CENTER)
        
        // Create sudo panel with checkbox and password field
        val sudoPanel = JPanel(BorderLayout())
        sudoPanel.add(useSudoCheckbox, BorderLayout.WEST)
        sudoPanel.add(sudoPasswordField, BorderLayout.CENTER)
        
        // Main form
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Alias:", aliasField)
            .addLabeledComponent("Host:", hostField)
            .addLabeledComponent("Port:", portField)
            .addLabeledComponent("Username:", usernameField)
            .addLabeledComponent("Password:", passwordField)
            .addComponent(osTypePanel)
            .addComponent(sudoPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply {
                border = JBUI.Borders.empty(10)
            }
    }
    
    override fun doValidate(): ValidationInfo? {
        // Validate required fields
        if (aliasField.text.isBlank()) {
            return ValidationInfo("Alias cannot be empty", aliasField)
        }
        
        if (hostField.text.isBlank()) {
            return ValidationInfo("Host cannot be empty", hostField)
        }
        
        if (usernameField.text.isBlank()) {
            return ValidationInfo("Username cannot be empty", usernameField)
        }
        
        // Validate port number
        try {
            val port = portField.text.toInt()
            if (port <= 0 || port > 65535) {
                return ValidationInfo("Port must be between 1 and 65535", portField)
            }
        } catch (e: NumberFormatException) {
            return ValidationInfo("Port must be a valid number", portField)
        }
        
        // Validate sudo password if sudo is enabled
        if (useSudoCheckbox.isSelected && sudoPasswordField.password.isEmpty() && linuxRadioButton.isSelected) {
            return ValidationInfo("Sudo password is required when sudo is enabled", sudoPasswordField)
        }
        
        return null
    }
    
    /**
     * Creates an SshConnectionData object from the dialog's input fields.
     */
    fun createConnectionData(): SshConnectionData {
        val osType = if (linuxRadioButton.isSelected) OsType.LINUX else OsType.WINDOWS
        val useSudo = useSudoCheckbox.isSelected && osType == OsType.LINUX
        
        return SshConnectionData(
            id = existingConnection?.id ?: java.util.UUID.randomUUID().toString(),
            alias = aliasField.text,
            host = hostField.text,
            port = portField.text.toInt(),
            username = usernameField.text,
            encodedPassword = passwordField.password.joinToString(""),
            osType = osType,
            useSudo = useSudo,
            encodedSudoPassword = if (useSudo) sudoPasswordField.password.joinToString("") else null
        )
    }
}