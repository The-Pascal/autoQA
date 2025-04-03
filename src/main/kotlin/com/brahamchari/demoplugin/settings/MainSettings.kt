package com.brahamchari.demoplugin.settings

import com.brahamchari.demoplugin.models.SettingsState
import com.brahamchari.demoplugin.services.AnthropicService
import com.brahamchari.demoplugin.services.SettingService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
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
        return panel
    }

    override fun isModified(): Boolean {
        // TODO: fix this later
        return true
    }

    override fun apply() {
        state.apiKey = String(geminiApiKey.password)
        state.packageName = packageNameField.text

        SettingService.getInstance(project).loadState(state)
    }

    override fun getDisplayName(): String = "Test case settings"
}