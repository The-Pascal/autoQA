package com.brahamchari.demoplugin.settings

import com.brahamchari.demoplugin.models.SettingsState
import com.brahamchari.demoplugin.services.ANTHROPIC_CREDENTIAL_ATTRIBUTES
import com.brahamchari.demoplugin.services.AnthropicService
import com.brahamchari.demoplugin.services.SettingService
import com.brahamchari.demoplugin.utils.ADBUtils
import com.brahamchari.demoplugin.utils.DEFAULT_MCP_PORT
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.passwordSafe.PasswordSafeException
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.layout.ValidationInfoBuilder
import com.jetbrains.rd.swing.textProperty
import org.jetbrains.kotlin.idea.gradleTooling.get
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

class MainSettings(private val project: Project): Configurable {

    private val settingService: SettingService by lazy {
        SettingService.getInstance(project)
    }

    private val anthropicService: AnthropicService by lazy {
        AnthropicService.getInstance(project)
    }


    // --- Load Initial State ---
    private val initialSettingsState: SettingsState = settingService.state.copy()

    // --- UI Components (for fields not using direct binding) ---
    private lateinit var anthropicApiKeyField: JPasswordField
    private lateinit var mcpServerPortField: JTextField
    private lateinit var appPackageNameField: JTextField
    private lateinit var adbPathField: TextFieldWithBrowseButton
    private lateinit var screenshotPathField: TextFieldWithBrowseButton

    override fun getDisplayName() = "AutoQA Plugin Settings (${project.name})"

    override fun createComponent(): JComponent {
        anthropicApiKeyField = JPasswordField()
        adbPathField = createBrowseTextField("Select ADB Executable", project, isDirectory = false)
        screenshotPathField = createBrowseTextField("Select Screenshot Directory", project, isDirectory = true)

        val panel = panel {
            row("Anthropic API Key") {
                cell(anthropicApiKeyField)
                    .label("Required.", LabelPosition.TOP)
                    .comment(
                        "Get your key from <a href='https://console.anthropic.com/settings/keys'>Anthropic Console</a>. Stored securely per project.",
                        MAX_LINE_LENGTH_NO_WRAP
                    )
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .validationOnApply(apiKeyValidation())
            }.topGap(TopGap.SMALL)

            row("MCP Server Port") {
                cell(intTextField(range = 1024..65535).component)
                    .applyToComponent {
                        mcpServerPortField = this
                        println("MCP Port Field component assigned: ${this::class.simpleName}") // Check type
                    } // Store component
                    .label("Required.", LabelPosition.TOP)
                    .comment("Port for the local MCP WebSocket server. Default: $DEFAULT_MCP_PORT.")
                    .validationOnApply {
                        val port = it.text.toIntOrNull()
                        println("Port is invalid - $port")
                        when {
                            port == null -> ValidationInfo("Only Integer value is allowed for Port.", it)
                            port !in 1024..65535 -> ValidationInfo("Port must be in range 1024 to 65535.", it)
                            else -> null
                        }
                    }
                    .align(AlignX.FILL).resizableColumn()
            }.topGap(TopGap.SMALL)

            row("App Package Name") {
                // Don't bind, just create and store reference
                cell(textField().component)
                    .applyToComponent { appPackageNameField = this } // Store component
                    .label("Optional.", LabelPosition.TOP)
                    .comment("e.g., com.example.myapp")
                    .align(AlignX.FILL).resizableColumn()
            }.topGap(TopGap.SMALL)

            row("Local ADB Path") {
                cell(adbPathField) // Use pre-initialized component
                    // No binding needed
                    .label("Optional.", LabelPosition.TOP)
                    .comment("Leave blank for auto-detect: ${ADBUtils.getAdbPath()?.ifBlank { "[Not Detected]" } ?: "[Not Detected]"}")
                    .align(AlignX.FILL).resizableColumn()
                    .validationOnApply(pathValidation(adbPathField, false, false, "ADB Path"))
            }.topGap(TopGap.SMALL)

            row("Screenshots Saving Path") {
                cell(screenshotPathField) // Use pre-initialized component
                    // No binding needed
                    .label("Optional.", LabelPosition.TOP)
                    .comment("Directory to save screenshots. \nDefault: ${ADBUtils.screenshotSaveFolderPath}")
                    .align(AlignX.FILL).resizableColumn()
                    .validationOnApply(pathValidation(screenshotPathField, true, false, "Screenshot Path"))
            }.topGap(TopGap.SMALL)
        }

        reset()
        return panel
    }

    override fun reset() {
        // Reload the persistent state (in case it changed externally - less likely)
        val savedState = settingService.state.copy()
        initialSettingsState.mcpServerPort = savedState.mcpServerPort
        initialSettingsState.packageName = savedState.packageName
        initialSettingsState.adbPath = savedState.adbPath
        initialSettingsState.screenshotPath = savedState.screenshotPath

        // Update UI components explicitly based on the UI model properties and secure storage
        anthropicApiKeyField.text = anthropicService.getApiKeyMask()
        mcpServerPortField.text = initialSettingsState.mcpServerPort.toString()
        appPackageNameField.text = initialSettingsState.packageName
        adbPathField.text = initialSettingsState.adbPath.ifBlank { ADBUtils.getAdbPath() ?: "adb" }
        screenshotPathField.text = initialSettingsState.screenshotPath.ifBlank { ADBUtils.screenshotSaveFolderPath }

        // Tell BoundConfigurable to update the UI from the bound properties if using superclass reset
        // super.reset() // Only needed if using super.apply/isModified with direct binding
        println("MainSettings [${project.name}]: Reset performed. $initialSettingsState")
    }

    override fun isModified(): Boolean {
        // Read CURRENT values directly from UI components
        val currentApiKeyText = String(anthropicApiKeyField.password)
        val currentPortText = mcpServerPortField.text
        val currentPackageName = appPackageNameField.text
        val currentAdbPath = adbPathField.text
        val currentScreenshotPath = screenshotPathField.text

        // Compare current UI values against the INITIAL state
        val apiKeyModified = currentApiKeyText != anthropicService.getApiKeyMask()
        val portModified = currentPortText.toIntOrNull() != initialSettingsState.mcpServerPort
        val packageModified = currentPackageName != initialSettingsState.packageName
        val adbPathModified = currentAdbPath != initialSettingsState.adbPath.ifBlank { ADBUtils.getAdbPath() }
        val screenshotPathModified = currentScreenshotPath != initialSettingsState.screenshotPath.ifBlank { ADBUtils.screenshotSaveFolderPath }

        return apiKeyModified || portModified || packageModified || adbPathModified || screenshotPathModified
    }

    override fun apply() {
        println("MainSettings [${project.name}]: Apply called.")

        val enteredApiKey = String(anthropicApiKeyField.password)
        val enteredPort = mcpServerPortField.text.toIntOrNull() ?: DEFAULT_MCP_PORT
        val enteredPackageName = appPackageNameField.text
        val enteredAdbPath = adbPathField.text.let { if (it == ADBUtils.getAdbPath()) "" else it }.trim()
        val enteredScreenshotPath = screenshotPathField.text.let { if (it == ADBUtils.screenshotSaveFolderPath) "" else it }.trim()

        // Handle API Key
        val currentMask = anthropicService.getApiKeyMask()
        val shouldStoreKey = enteredApiKey.isNotEmpty() && (enteredApiKey != currentMask)
        if (shouldStoreKey) {
            println("MainSettings [${project.name}]: Storing new/updated Anthropic API Key.")
            anthropicService.setApiKey(enteredApiKey)
        }

        // Create new state object from UI model properties for non-sensitive data
        val newState = SettingsState(
            mcpServerPort = enteredPort.coerceIn(1024, 65535),
            packageName = enteredPackageName,
            adbPath = enteredAdbPath,
            screenshotPath = enteredScreenshotPath
        )

        settingService.loadState(newState)

        // Update the 'initial' state reference for future isModified checks
        initialSettingsState.mcpServerPort = newState.mcpServerPort
        initialSettingsState.packageName = newState.packageName
        initialSettingsState.adbPath = newState.adbPath
        initialSettingsState.screenshotPath = newState.screenshotPath

        println("MainSettings [${project.name}]: Settings applied and saved.")

        // Refresh UI after saving to show the correct mask for the potentially new key
        reset()
    }

    // Helper function to check if a key is currently stored
    private fun isApiKeyStored(): Boolean {
        return try {
            // Check if PasswordSafe returns non-null (meaning a value exists)
            PasswordSafe.instance.getPassword(ANTHROPIC_CREDENTIAL_ATTRIBUTES) != null
        } catch (e: PasswordSafeException) {
            // Treat errors during check as "not stored" for UI purposes
            false
        }
    }

    // --- Helper for Browse Buttons ---
    private fun createBrowseTextField(
        title: String,
        project: Project?,
        isDirectory: Boolean = false
    ): TextFieldWithBrowseButton {
        val field = TextFieldWithBrowseButton()
        val descriptor = if (isDirectory) {
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        } else {
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
        }
        descriptor.title = title
        // Allow choosing non-existent files/dirs for optional paths unless mustExist=true
        // descriptor.isForcedToUseIdeaFileChooser = true // Use native chooser if preferred
        field.addBrowseFolderListener(title, "Select the path", project, descriptor)
        return field
    }

    // ---------- Validation Logic ------------
    private fun apiKeyValidation(): ValidationInfoBuilder.(JPasswordField) -> ValidationInfo? {
        println("Validation on api key apply")
        return {
            if (String(it.password).isBlank() && !isApiKeyStored()) {
                println("Api key is required")
                error("Anthropic API Key is required.")
            } else { null }
        }
    }

    private fun mcpServerPortValidation(): ValidationInfoBuilder.(JBTextField) -> ValidationInfo? {
        return {
            when (it.text.toIntOrNull()) {
                null -> {
                    error("Only Integer value is allowed for Port.")
                }
                !in 1024..65535 -> {
                    error("Range of Ports allowed is 1024 <= port <= 65535")
                }
                else -> {
                    null
                }
            }
        }
    }

    private fun pathValidation(
        component: TextFieldWithBrowseButton,
        isDirectory: Boolean,
        mustExist: Boolean,
        messagePrefix: String
    ): ValidationInfoBuilder.(TextFieldWithBrowseButton) -> ValidationInfo? {
        return {
            val path = component.text.trim()
            // Ignore validation if path is blank (optional fields)
            // or if it's just the auto-detected/default path placeholder being shown
            if (path.isNotBlank() && path != ADBUtils.getAdbPath() && path != ADBUtils.screenshotSaveFolderPath) {
                val file = java.io.File(path)
                if (mustExist && !file.exists()) {
                    error("$messagePrefix: Path does not exist.")
                } else if (file.exists()) {
                    if (isDirectory && !file.isDirectory) error("$messagePrefix: Path is not a directory.")
                    else if (!isDirectory && !file.isFile) error("$messagePrefix: Path is not a file.")
                    else null // Valid existing path
                } else {
                    // Path doesn't exist, but mustExist=false, maybe show warning?
                    warning("$messagePrefix: Path does not currently exist.")
                }
            } else {
                null // Empty path is allowed
            }
        }
    }
}