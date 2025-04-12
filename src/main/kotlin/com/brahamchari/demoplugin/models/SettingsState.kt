package com.brahamchari.demoplugin.models

data class SettingsState(
        var apiKey: String = "", // TODO: Remove this later
        var packageName: String = "",
        var mcpServerPort: Int = 5000,
        var adbPath: String = "",
        var screenshotPath: String = ""
)
