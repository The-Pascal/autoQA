package com.brahamchari.demoplugin.models

data class AiModelData(
    val displayName: String,
    val modelName: String,
    val company: AiModelCompany,
    val enabled: Boolean
)

enum class AiModelCompany(name: String) {
    GEMINI("Gemini"),
    ANTHROPIC("Anthropic")
}
