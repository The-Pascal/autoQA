package com.brahamchari.demoplugin.settings

import com.brahamchari.demoplugin.models.SettingsState
import com.brahamchari.demoplugin.services.*
import com.brahamchari.demoplugin.utils.ADBUtils
import com.brahamchari.demoplugin.utils.DEFAULT_MCP_PORT
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.passwordSafe.PasswordSafeException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState // Import ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.layout.ValidationInfoBuilder
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent
import javax.swing.JPasswordField
import javax.swing.JTextField

class MainSettings(private val project: Project) : Configurable {

    private val settingService: SettingService by lazy {
        SettingService.getInstance(project)
    }
    private val anthropicService: AnthropicService by lazy {
        AnthropicService.getInstance(project)
    }
    private val geminiService: GeminiService by lazy {
        GeminiService.getInstance(project)
    }

    private val initialSettingsState: SettingsState = settingService.state.copy()
    private var initialAnthropicApiKeyMask: String = ""
    private var initialGeminiApiKeyMask: String = ""

    private lateinit var anthropicApiKeyField: JPasswordField
    private lateinit var geminiApiKeyField: JPasswordField
    private lateinit var mcpServerPortField: JTextField
    private lateinit var appPackageNameField: JTextField
    private lateinit var adbPathField: TextFieldWithBrowseButton
    private lateinit var screenshotPathField: TextFieldWithBrowseButton

    init {
        println("DEBUG_LIFECYCLE: MainSettings instance CREATED. HashCode: ${this.hashCode()}, Project: ${project.name}")
    }

    override fun getDisplayName() = "AutoQA Plugin Settings (${project.name})"

    override fun createComponent(): JComponent {
        println("DEBUG_LIFECYCLE: createComponent called. Instance: ${this.hashCode()}")
        anthropicApiKeyField = JPasswordField()
        geminiApiKeyField = JPasswordField()
        adbPathField = createBrowseTextField("Select ADB Executable", project, isDirectory = false)
        screenshotPathField = createBrowseTextField("Select Screenshot Directory", project, isDirectory = true)

        val panel = panel {
            // ... (your panel DSL structure) ...
            group("API Keys") {
                row("Anthropic API Key:") {
                    cell(anthropicApiKeyField)
                        .label("Required if using Anthropic models.", LabelPosition.TOP)
                        .comment("Get your key from <a href='https://console.anthropic.com/settings/keys'>Anthropic Console</a>. Stored securely.", MAX_LINE_LENGTH_NO_WRAP)
                        .align(AlignX.FILL).resizableColumn()
                        .validationOnApply(apiKeyValidation(anthropicApiKeyField, ::isAnthropicApiKeyStored, "Anthropic"))
                }.topGap(TopGap.SMALL)
                row("Gemini API Key:") {
                    cell(geminiApiKeyField)
                        .label("Required if using Gemini models.", LabelPosition.TOP)
                        .comment("Get your key from <a href='https://aistudio.google.com/app/apikey'>Google AI Studio</a>. Stored securely.", MAX_LINE_LENGTH_NO_WRAP)
                        .align(AlignX.FILL).resizableColumn()
                        .validationOnApply(apiKeyValidation(geminiApiKeyField, ::isGeminiApiKeyStored, "Gemini"))
                }.topGap(TopGap.SMALL)
            }
            group("Local Server & App Configuration") {
                row("MCP Server Port:") {
                    cell(intTextField(range = 1024..65535).component).applyToComponent { mcpServerPortField = this }
                        .label("Required.", LabelPosition.TOP)
                        .comment("Port for the local MCP WebSocket server. Default: $DEFAULT_MCP_PORT.")
                        .validationOnApply {
                            val port = it.text.toIntOrNull()
                            when {
                                port == null -> ValidationInfo("Only Integer value is allowed for Port.", it)
                                port !in 1024..65535 -> ValidationInfo("Port must be in range 1024 to 65535.", it)
                                else -> null
                            }
                        }.align(AlignX.FILL).resizableColumn()
                }.topGap(TopGap.SMALL)
                row("App Package Name:") {
                    cell(textField().component).applyToComponent { appPackageNameField = this }
                        .label("Optional.", LabelPosition.TOP)
                        .comment("e.g., com.example.myapp. Used as a default if not provided in commands.")
                        .align(AlignX.FILL).resizableColumn()
                }.topGap(TopGap.SMALL)
            }
            group("Development Tools Paths") {
                row("Local ADB Path:") {
                    cell(adbPathField).label("Optional.", LabelPosition.TOP)
                        .comment("Leave blank for auto-detect: ${ADBUtils.getAdbPath()?.ifBlank { "[Not Detected]" } ?: "[Not Detected]"}")
                        .align(AlignX.FILL).resizableColumn()
                        .validationOnApply(pathValidation(adbPathField, false, false, "ADB Path"))
                }.topGap(TopGap.SMALL)
                row("Screenshots Saving Path:") {
                    cell(screenshotPathField).label("Optional.", LabelPosition.TOP)
                        .comment("Directory to save screenshots. Default: ${ADBUtils.screenshotSaveFolderPath}")
                        .align(AlignX.FILL).resizableColumn()
                        .validationOnApply(pathValidation(screenshotPathField, true, false, "Screenshot Path"))
                }.topGap(TopGap.SMALL)
            }
        }
        reset()
        return panel
    }

    override fun reset() {
        println("DEBUG_RESET: reset() method started. Instance: ${this.hashCode()}")

        val savedState = settingService.state.copy()
        initialSettingsState.mcpServerPort = savedState.mcpServerPort
        initialSettingsState.packageName = savedState.packageName
        initialSettingsState.adbPath = savedState.adbPath
        initialSettingsState.screenshotPath = savedState.screenshotPath

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val anthropicMask = anthropicService.getApiKeyMask()
                ApplicationManager.getApplication().invokeLater({
                    initialAnthropicApiKeyMask = anthropicMask
                    anthropicApiKeyField.text = initialAnthropicApiKeyMask
                    println("DEBUG_RESET: Anthropic mask updated. Mask empty: ${anthropicMask.isEmpty()}. Instance: ${this.hashCode()}")
                }, ModalityState.any())
            } catch (e: Exception) {
                System.err.println("DEBUG_RESET_ERROR: Error fetching Anthropic mask: ${e.message}")
                e.printStackTrace()
                ApplicationManager.getApplication().invokeLater({
                    initialAnthropicApiKeyMask = ""
                    anthropicApiKeyField.text = ""
                }, ModalityState.any())
            }
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val geminiMask = geminiService.getApiKeyMask()
                ApplicationManager.getApplication().invokeLater({
                    initialGeminiApiKeyMask = geminiMask
                    geminiApiKeyField.text = initialGeminiApiKeyMask
                    println("DEBUG_RESET: Gemini mask updated. Mask empty: ${geminiMask.isEmpty()}. Instance: ${this.hashCode()}")
                }, ModalityState.any())
            } catch (e: Exception) {
                System.err.println("DEBUG_RESET_ERROR: Error fetching Gemini mask: ${e.message}")
                e.printStackTrace()
                ApplicationManager.getApplication().invokeLater({
                    initialGeminiApiKeyMask = ""
                    geminiApiKeyField.text = ""
                }, ModalityState.any())
            }
        }

        mcpServerPortField.text = initialSettingsState.mcpServerPort.toString()
        appPackageNameField.text = initialSettingsState.packageName
        adbPathField.text = initialSettingsState.adbPath.ifBlank { ADBUtils.getAdbPath() ?: "" }
        screenshotPathField.text = initialSettingsState.screenshotPath.ifBlank { ADBUtils.screenshotSaveFolderPath }

        println("DEBUG_RESET: reset() method synchronous part finished. API Key fields will update asynchronously. Instance: ${this.hashCode()}")
    }

    override fun isModified(): Boolean {
        val currentAnthropicApiKeyText = String(anthropicApiKeyField.password)
        val currentGeminiApiKeyText = String(geminiApiKeyField.password)
        val anthropicKeyModified = currentAnthropicApiKeyText != initialAnthropicApiKeyMask
        val geminiKeyModified = currentGeminiApiKeyText != initialGeminiApiKeyMask
        val currentPortText = mcpServerPortField.text
        val currentPackageName = appPackageNameField.text
        val currentAdbPath = adbPathField.text
        val currentScreenshotPath = screenshotPathField.text
        val portModified = currentPortText.toIntOrNull() != initialSettingsState.mcpServerPort
        val packageModified = currentPackageName != initialSettingsState.packageName
        val adbPathModified = currentAdbPath != initialSettingsState.adbPath.ifBlank { ADBUtils.getAdbPath() ?: "" }
        val screenshotPathModified = currentScreenshotPath != initialSettingsState.screenshotPath.ifBlank { ADBUtils.screenshotSaveFolderPath }
        return anthropicKeyModified || geminiKeyModified || portModified || packageModified || adbPathModified || screenshotPathModified
    }

    override fun apply() {
        println("DEBUG_APPLY: Apply called. Instance: ${this.hashCode()}")
        var overallSettingsChangedLocal = false
        val pendingAsyncOperations = AtomicInteger(0)
        var asyncApiKeyTasksWereDispatched = false
        val mainSettingsInstance = this@MainSettings

        val onAsyncTaskCompleted = {
            val remainingOps = pendingAsyncOperations.decrementAndGet()
            println("DEBUG_APPLY_ON_ASYNC_TASK_COMPLETE: Entered. Instance: ${mainSettingsInstance.hashCode()}. Thread: ${Thread.currentThread().name}. Remaining ops: $remainingOps. overallSettingsChangedLocal: $overallSettingsChangedLocal")

            if (remainingOps == 0 && overallSettingsChangedLocal) {
                println("DEBUG_APPLY_ON_ASYNC_TASK_COMPLETE: Condition to schedule reset MET. Scheduling reset via invokeLater. Instance: ${mainSettingsInstance.hashCode()}")

                // Test 2: Original invokeLater with reset (Now Enabled)
                ApplicationManager.getApplication().invokeLater({
                    try {
                        println("DEBUG_APPLY_INVOKE_LATER_WITH_RESET: invokeLater EXECUTING for reset. Instance: ${mainSettingsInstance.hashCode()}. Thread: ${Thread.currentThread().name}")
                        mainSettingsInstance.reset()
                        println("DEBUG_APPLY_INVOKE_LATER_WITH_RESET: reset() call completed. Instance: ${mainSettingsInstance.hashCode()}")
                    } catch (t: Throwable) {
                        System.err.println("DEBUG_APPLY_INVOKE_LATER_WITH_RESET_ERROR: Exception during invokeLater reset execution. Instance: ${mainSettingsInstance.hashCode()}")
                        t.printStackTrace()
                    }
                }, ModalityState.any())

            } else if (remainingOps < 0) {
                System.err.println("DEBUG_APPLY_ON_ASYNC_TASK_COMPLETE_ERROR: pendingAsyncOperations went below zero! Instance: ${mainSettingsInstance.hashCode()}")
            } else {
                println("DEBUG_APPLY_ON_ASYNC_TASK_COMPLETE: Condition to schedule reset NOT MET. Remaining ops: $remainingOps, overallSettingsChangedLocal: $overallSettingsChangedLocal. Instance: ${mainSettingsInstance.hashCode()}")
            }
        }

        // --- Handle Anthropic API Key ---
        val enteredAnthropicApiKey = String(anthropicApiKeyField.password)
        if (enteredAnthropicApiKey.isNotEmpty() && enteredAnthropicApiKey != initialAnthropicApiKeyMask) {
            pendingAsyncOperations.incrementAndGet()
            asyncApiKeyTasksWereDispatched = true
            overallSettingsChangedLocal = true
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    println("DEBUG_APPLY_BG: Storing new/updated Anthropic API Key. Instance: ${mainSettingsInstance.hashCode()}. Thread: ${Thread.currentThread().name}")
                    anthropicService.setApiKey(enteredAnthropicApiKey)
                } catch (e: Exception) {
                    System.err.println("DEBUG_APPLY_BG_ERROR: Error storing Anthropic key: ${e.message}. Instance: ${mainSettingsInstance.hashCode()}")
                    e.printStackTrace()
                } finally {
                    onAsyncTaskCompleted()
                }
            }
        } else if (enteredAnthropicApiKey.isEmpty() && initialAnthropicApiKeyMask.isNotEmpty()) {
            pendingAsyncOperations.incrementAndGet()
            asyncApiKeyTasksWereDispatched = true
            overallSettingsChangedLocal = true
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    println("DEBUG_APPLY_BG: Clearing stored Anthropic API Key. Instance: ${mainSettingsInstance.hashCode()}. Thread: ${Thread.currentThread().name}")
                    anthropicService.setApiKey("")
                } catch (e: Exception) {
                    System.err.println("DEBUG_APPLY_BG_ERROR: Error clearing Anthropic key: ${e.message}. Instance: ${mainSettingsInstance.hashCode()}")
                    e.printStackTrace()
                } finally {
                    onAsyncTaskCompleted()
                }
            }
        }

        // --- Handle Gemini API Key ---
        val enteredGeminiApiKey = String(geminiApiKeyField.password)
        if (enteredGeminiApiKey.isNotEmpty() && enteredGeminiApiKey != initialGeminiApiKeyMask) {
            pendingAsyncOperations.incrementAndGet()
            asyncApiKeyTasksWereDispatched = true
            overallSettingsChangedLocal = true
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    println("DEBUG_APPLY_BG: Storing new/updated Gemini API Key. Instance: ${mainSettingsInstance.hashCode()}. Thread: ${Thread.currentThread().name}")
                    geminiService.setApiKey(enteredGeminiApiKey)
                } catch (e: Exception) {
                    System.err.println("DEBUG_APPLY_BG_ERROR: Error storing Gemini key: ${e.message}. Instance: ${mainSettingsInstance.hashCode()}")
                    e.printStackTrace()
                } finally {
                    onAsyncTaskCompleted()
                }
            }
        } else if (enteredGeminiApiKey.isEmpty() && initialGeminiApiKeyMask.isNotEmpty()) {
            pendingAsyncOperations.incrementAndGet()
            asyncApiKeyTasksWereDispatched = true
            overallSettingsChangedLocal = true
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    println("DEBUG_APPLY_BG: Clearing stored Gemini API Key. Instance: ${mainSettingsInstance.hashCode()}. Thread: ${Thread.currentThread().name}")
                    geminiService.setApiKey("")
                } catch (e: Exception) {
                    System.err.println("DEBUG_APPLY_BG_ERROR: Error clearing Gemini key: ${e.message}. Instance: ${mainSettingsInstance.hashCode()}")
                    e.printStackTrace()
                } finally {
                    onAsyncTaskCompleted()
                }
            }
        }

        // --- Handle other settings (synchronously) ---
        val enteredPort = mcpServerPortField.text.toIntOrNull() ?: DEFAULT_MCP_PORT
        val enteredPackageName = appPackageNameField.text
        val enteredAdbPath = adbPathField.text.let { if (it == (ADBUtils.getAdbPath() ?: "")) "" else it }.trim()
        val enteredScreenshotPath = screenshotPathField.text.let { if (it == ADBUtils.screenshotSaveFolderPath) "" else it }.trim()

        if ( (enteredPort != initialSettingsState.mcpServerPort) ||
            (enteredPackageName != initialSettingsState.packageName) ||
            (enteredAdbPath != initialSettingsState.adbPath.ifBlank { ADBUtils.getAdbPath() ?: "" }) ||
            (enteredScreenshotPath != initialSettingsState.screenshotPath.ifBlank { ADBUtils.screenshotSaveFolderPath }) )
        {
            val newState = SettingsState(
                packageName = enteredPackageName,
                adbPath = enteredAdbPath,
                mcpServerPort = enteredPort,
                screenshotPath = enteredScreenshotPath
            )
            settingService.loadState(newState)
            initialSettingsState.mcpServerPort = newState.mcpServerPort
            initialSettingsState.packageName = newState.packageName
            initialSettingsState.adbPath = newState.adbPath
            initialSettingsState.screenshotPath = newState.screenshotPath
            overallSettingsChangedLocal = true
        }

        println("DEBUG_APPLY: Settings processed. overallSettingsChangedLocal: $overallSettingsChangedLocal, asyncApiKeyTasksWereDispatched: $asyncApiKeyTasksWereDispatched, Final Pending ops before check: ${pendingAsyncOperations.get()}. Instance: ${this.hashCode()}")

        if (overallSettingsChangedLocal && !asyncApiKeyTasksWereDispatched) {
            println("DEBUG_APPLY: Synchronous changes made, no async API tasks, calling reset() directly. Instance: ${this.hashCode()}")
            reset()
        } else if (!overallSettingsChangedLocal) {
            println("DEBUG_APPLY: No changes detected. Not calling reset(). Instance: ${this.hashCode()}")
        }
    }

    override fun disposeUIResources() {
        println("DEBUG_LIFECYCLE: disposeUIResources called. Instance: ${this.hashCode()}. Current thread: ${Thread.currentThread().name}")
    }

    // ... (helper methods) ...
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
        field.addBrowseFolderListener(title, "Select the path", project, descriptor)
        return field
    }

    private fun isAnthropicApiKeyStored(): Boolean {
        return try {
            PasswordSafe.instance.getPassword(ANTHROPIC_CREDENTIAL_ATTRIBUTES) != null
        } catch (e: PasswordSafeException) { false }
    }

    private fun isGeminiApiKeyStored(): Boolean {
        return try {
            PasswordSafe.instance.getPassword(GEMINI_CREDENTIAL_ATTRIBUTES) != null
        } catch (e: PasswordSafeException) { false }
    }

    private fun apiKeyValidation(
        field: JPasswordField,
        isStoredCheck: () -> Boolean,
        keyName: String
    ): ValidationInfoBuilder.(JPasswordField) -> ValidationInfo? {
        return {
            null // API keys are optional in settings UI
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
            if (path.isNotBlank() && path != (ADBUtils.getAdbPath() ?: "") && path != ADBUtils.screenshotSaveFolderPath) {
                val file = java.io.File(path)
                if (mustExist && !file.exists()) {
                    error("$messagePrefix: Path does not exist.")
                } else if (file.exists()) {
                    if (isDirectory && !file.isDirectory) error("$messagePrefix: Path is not a directory.")
                    else if (!isDirectory && !file.isFile) error("$messagePrefix: Path is not a file.")
                    else null
                } else {
                    null
                }
            } else {
                null
            }
        }
    }
}
