package com.brahamchari.demoplugin.services

import com.brahamchari.demoplugin.models.SettingsState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

// TODO: later migrate this to protected storage
@Service
@State(name = "SettingsConfiguration", storages = [
    Storage(value = "settingsConfiguration.xml")
])
class SettingService: PersistentStateComponent<SettingsState> {

    private var settingsState: SettingsState = SettingsState()
    override fun getState(): SettingsState = settingsState

    override fun loadState(state: SettingsState) {
        settingsState = state
    }

    companion object {
        fun getInstance(project: Project): SettingService = project.service()
    }
}