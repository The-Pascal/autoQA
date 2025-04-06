package com.brahamchari.demoplugin.settings

import com.brahamchari.demoplugin.models.SettingsState
import com.brahamchari.demoplugin.services.ANTHROPIC_CREDENTIAL_ATTRIBUTES
import com.brahamchari.demoplugin.services.AnthropicService
import com.brahamchari.demoplugin.services.SettingService
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.passwordSafe.PasswordSafeException
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.rd.swing.textProperty
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

class MainSettings(private val project: Project): Configurable {

    private val state: SettingsState by lazy {
        SettingService.getInstance(project).state
    }

    private val anthropicService: AnthropicService by lazy {
        AnthropicService.getInstance(project)
    }

    private val geminiApiKey: JPasswordField = JPasswordField()
    private val anthropicApiKey: JPasswordField = JPasswordField()
    private val packageNameField: JTextField = JTextField()
    private val panel: JPanel = panel {
        row("Gemini Api Key") {
            cell(geminiApiKey)
                    .resizableColumn()
                    .align(Align.FILL)
        }
        row("Anthropic API Key:") {
            cell(anthropicApiKey)
                .resizableColumn()
                .align(Align.FILL)
                .comment("API Key is stored securely per project.")
        }
        row("App package name") {
            cell(packageNameField)
                    .resizableColumn()
                    .align(Align.FILL)
        }
    }

    override fun createComponent(): JComponent {
        geminiApiKey.apply {
            text = state.apiKey
        }
        packageNameField.apply {
            text = state.packageName
        }
        anthropicApiKey.apply {
            text = if(isApiKeyStored()) "**********" else ""
        }
        return panel
    }

    override fun isModified(): Boolean {
        // TODO: fix this later
        return true
    }

    override fun apply() {
        state.apiKey = String(geminiApiKey.password)
        state.packageName = packageNameField.text

        anthropicService.setApiKey(String(anthropicApiKey.password))
        println("Setting anthropic api key - ${String(anthropicApiKey.password)}")
        anthropicApiKey.text =  if(isApiKeyStored()) "**********" else ""

        SettingService.getInstance(project).loadState(state)
    }

    override fun getDisplayName(): String = "Test case settings"

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

}