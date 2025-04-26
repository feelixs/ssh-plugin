package com.github.feelixs.sshplugin.toolWindow

import com.github.feelixs.sshplugin.model.OsType
import com.github.feelixs.sshplugin.model.SshConnectionData
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
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
    
    // SSH key authentication
    private val useKeyCheckbox = JBCheckBox("Use SSH key authentication", existingConnection?.useKey ?: false)
    private val keyPathField = TextFieldWithBrowseButton().apply {
        text = existingConnection?.keyPath ?: ""

        val sshDir = System.getProperty("user.home") + "/.ssh"
        val initialDir = java.io.File(sshDir).takeIf { it.exists() }?.let {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it.path)
        }

        val descriptor = FileChooserDescriptor(true, false, false, false, false, false).apply {
            title = "Select SSH Key"
            description = "Choose the SSH key file to use for authentication"
            if (initialDir != null) {
                setRoots(initialDir)
            }
        }

        addActionListener {
            val file = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, initialDir)
            if (file != null) {
                text = file.path
            }
        }
    }


    private val keyPasswordField = JBPasswordField()
    
    // SSH key panel with path and passphrase fields
    private val keyPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isVisible = false  // Initially hidden
    }
    
    // OS type selection
    private val linuxRadioButton = JRadioButton("Linux")
    private val windowsRadioButton = JRadioButton("Windows")
    
    // Run commands option
    private val runCommandsCheckbox = JBCheckBox("Run commands after successful connection", 
        existingConnection?.runCommands ?: false)
    private val commandsTextArea = JBTextArea(5, 40).apply {
        text = existingConnection?.commands ?: ""
        lineWrap = true
        wrapStyleWord = true
    }
    private val commandsScrollPane = JBScrollPane(commandsTextArea)
    
    // Sudo password and options
    private val useUserPasswordForSudoCheckbox = JBCheckBox("Use user password for sudo commands", 
        existingConnection?.useUserPasswordForSudo ?: false)
    private val sudoPasswordField = JBPasswordField()
    
    // Terminal options
    private val maximizeTerminalCheckbox = JBCheckBox("Maximize terminal window on connect", 
        existingConnection?.maximizeTerminal ?: false)
    
    init {
        title = if (existingConnection == null) "Add SSH Connection" else "Edit SSH Connection"
        
        // Set the current password values for editing
        if (existingConnection != null) {
            if (!existingConnection.useKey) {
                if (existingConnection.encodedPassword != null) {
                    passwordField.text = existingConnection.encodedPassword
                }
            } else {
                if (existingConnection.encodedKeyPassword != null) {
                    keyPasswordField.text = existingConnection.encodedKeyPassword
                }
            }
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
        
        // Make sure command options are properly enabled/disabled
        updateCommandsEnabled()
        
        // Set up auth panel with card layout
        setupAuthPanel()
        
        // Set event listeners for form controls
        setupEventListeners()
        
        init()
    }
    
    private fun setupAuthPanel() {
        // Setup SSH Key panel
        val keyPathLabelPanel = JPanel(BorderLayout())
        keyPathLabelPanel.add(JBLabel("Key path:"), BorderLayout.WEST)
        keyPathLabelPanel.border = JBUI.Borders.empty(0, 0, 5, 0)
        
        val keyPasswordLabelPanel = JPanel(BorderLayout())
        keyPasswordLabelPanel.add(JBLabel("Key passphrase:"), BorderLayout.WEST)
        
        keyPanel.add(keyPathLabelPanel)
        keyPanel.add(keyPathField)
        keyPanel.add(Box.createVerticalStrut(5))
        keyPanel.add(keyPasswordLabelPanel)
        keyPanel.add(keyPasswordField)
        
        // Set initial visibility based on existing connection
        if (existingConnection != null && existingConnection.useKey) {
            keyPanel.isVisible = true
            useKeyCheckbox.isSelected = true
        } else {
            keyPanel.isVisible = false
            useKeyCheckbox.isSelected = false
        }
    }
    
    private fun setupEventListeners() {
        // Toggle SSH key panel visibility
        useKeyCheckbox.addActionListener {
            keyPanel.isVisible = useKeyCheckbox.isSelected
        }
        
        // Make commands field enabled only when runCommands is checked
        updateCommandsEnabled()
        
        runCommandsCheckbox.addActionListener {
            updateCommandsEnabled()
        }
        
        // Update sudo password field state based on checkbox
        updateSudoPasswordEnabled()
        
        useUserPasswordForSudoCheckbox.addActionListener {
            updateSudoPasswordEnabled()
        }
        
        linuxRadioButton.addActionListener {
            // Update suggested commands if OS type changes
            updateCommandsPlaceholder()
        }
        
        windowsRadioButton.addActionListener {
            // Update suggested commands if OS type changes
            updateCommandsPlaceholder()
        }
    }
    
    private fun updateSudoPasswordEnabled() {
        // Disable sudo password field if using user password for sudo
        sudoPasswordField.isEnabled = !useUserPasswordForSudoCheckbox.isSelected
    }
    
    private fun updateCommandsEnabled() {
        val areCommandsEnabled = runCommandsCheckbox.isSelected
        commandsTextArea.isEnabled = areCommandsEnabled
        
        // Update placeholder text based on OS type
        updateCommandsPlaceholder()
    }
    
    private fun updateCommandsPlaceholder() {
        // Update placeholder text or sample commands based on OS type
        if (!commandsTextArea.isEnabled || commandsTextArea.text.isBlank()) {
            val osType = if (linuxRadioButton.isSelected) OsType.LINUX else OsType.WINDOWS
            val placeholderText = when (osType) {
                OsType.LINUX -> "# Example commands:\nsudo -s\ncd /var/www/html\nls -la"
                OsType.WINDOWS -> "# Example commands:\ncd C:\\Users\\Administrator\\Documents\ndir"
            }
            
            if (!commandsTextArea.isEnabled) {
                commandsTextArea.text = ""
            } else if (commandsTextArea.text.isBlank()) {
                commandsTextArea.text = placeholderText
            }
        }
    }
    
    override fun createCenterPanel(): JComponent {
        // Create OS type panel
        val osTypePanel = JPanel(BorderLayout())
        val osRadioButtonPanel = JPanel(GridLayout(1, 2))
        osRadioButtonPanel.add(linuxRadioButton)
        osRadioButtonPanel.add(windowsRadioButton)
        osTypePanel.add(JBLabel("Server OS Type:"), BorderLayout.WEST)
        osTypePanel.add(osRadioButtonPanel, BorderLayout.CENTER)
        
        // Create commands panel with checkbox and text area
        val commandsPanel = JPanel(BorderLayout())
        commandsPanel.add(runCommandsCheckbox, BorderLayout.NORTH)
        
        val commandsTextPanel = JPanel(BorderLayout())
        commandsTextPanel.add(JBLabel("Commands to run after connecting:"), BorderLayout.NORTH)
        commandsScrollPane.preferredSize = Dimension(400, 100)
        commandsTextPanel.add(commandsScrollPane, BorderLayout.CENTER)
        commandsTextPanel.border = JBUI.Borders.empty(5, 20, 0, 0)
        
        commandsPanel.add(commandsTextPanel, BorderLayout.CENTER)
        
        // Sudo password panel with checkbox for using user password
        val sudoPanel = JPanel(BorderLayout())
        sudoPanel.add(useUserPasswordForSudoCheckbox, BorderLayout.NORTH)
        
        val sudoPasswordPanel = JPanel(BorderLayout())
        sudoPasswordPanel.add(JBLabel("Sudo Password (if needed):"), BorderLayout.WEST)
        sudoPasswordPanel.add(sudoPasswordField, BorderLayout.CENTER)
        sudoPasswordPanel.border = JBUI.Borders.empty(5, 20, 0, 0)
        
        sudoPanel.add(sudoPasswordPanel, BorderLayout.CENTER)
        
        // Terminal options panel
        val terminalOptionsPanel = JPanel(BorderLayout())
        terminalOptionsPanel.add(maximizeTerminalCheckbox, BorderLayout.NORTH)
        terminalOptionsPanel.border = JBUI.Borders.empty(10, 0, 0, 0)
        
        // Main form
        val mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Alias:", aliasField)
            .addLabeledComponent("Host:", hostField)
            .addLabeledComponent("Port:", portField)
            .addLabeledComponent("Username:", usernameField)
            .addLabeledComponent("Password:", passwordField)
            .addComponent(useKeyCheckbox)
            .addComponent(keyPanel)
            .addComponent(osTypePanel)
            .addComponent(commandsPanel)
            .addComponent(sudoPanel)
            .addComponent(terminalOptionsPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        
        mainPanel.border = JBUI.Borders.empty(10)
        return mainPanel
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
        
        // Validate SSH key path if using key authentication
        if (useKeyCheckbox.isSelected && keyPathField.text.isBlank()) {
            return ValidationInfo("SSH key path is required when using key authentication", keyPathField)
        }
        
        // Validate commands if enabled
        if (runCommandsCheckbox.isSelected && commandsTextArea.text.isBlank()) {
            return ValidationInfo("Please enter at least one command to run or uncheck the option", commandsTextArea)
        }
        
        return null
    }
    
    /**
     * Creates an SshConnectionData object from the dialog's input fields.
     */
    fun createConnectionData(): SshConnectionData {
        val osType = if (linuxRadioButton.isSelected) OsType.LINUX else OsType.WINDOWS
        val useKey = useKeyCheckbox.isSelected
        
        return SshConnectionData(
            id = existingConnection?.id ?: java.util.UUID.randomUUID().toString(),
            alias = aliasField.text,
            host = hostField.text,
            port = portField.text.toInt(),
            username = usernameField.text,
            encodedPassword = passwordField.password.joinToString(""),
            osType = osType,
            runCommands = runCommandsCheckbox.isSelected,
            commands = if (runCommandsCheckbox.isSelected) commandsTextArea.text else "",
            // Store sudo password if provided
            encodedSudoPassword = if (sudoPasswordField.password.isNotEmpty())
                                    sudoPasswordField.password.joinToString("")
                                  else null,
            useKey = useKey,
            keyPath = if (useKey) keyPathField.text else "",
            encodedKeyPassword = if (useKey) keyPasswordField.password.joinToString("") else null,
            useUserPasswordForSudo = useUserPasswordForSudoCheckbox.isSelected,
            maximizeTerminal = maximizeTerminalCheckbox.isSelected
        )
    }
}
