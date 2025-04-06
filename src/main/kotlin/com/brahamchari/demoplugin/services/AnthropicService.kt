package com.brahamchari.demoplugin.services

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.credentialStore.Credentials // For securely storing API key
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName // Helper
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.passwordSafe.PasswordSafeException
import kotlinx.coroutines.CoroutineScope

// Define attributes for storing the credential securely
val ANTHROPIC_CREDENTIAL_ATTRIBUTES = CredentialAttributes(
    // Use generateServiceName to create a unique key based on your plugin/service name
    generateServiceName("MyMCPPluginAnthropicService", "AnthropicApiKey")
)

@Service(Service.Level.PROJECT) // Or Service.Level.PROJECT if API key/config is project-specific
class AnthropicService(
    private val project: Project,
    private val scope: CoroutineScope
) {

    init {
        println("AnthropicService instance created for project: ${project.name}")
    }

    // Lazy initialization: Create the client only when first needed
    val anthropicClient: AnthropicClient by lazy {
        createClient()
    }

    private fun createClient(): AnthropicClient {
        println("AnthropicService: Creating AnthropicClient instance...")
        val apiKey: String? = try {
            // --- Use the correct PasswordSafe API ---
            PasswordSafe.instance.getPassword(ANTHROPIC_CREDENTIAL_ATTRIBUTES)
        } catch (e: PasswordSafeException) {
            // Handle exceptions during retrieval (though less common than null result)
            System.err.println("AnthropicService [${project.name}] ERROR: Failed to retrieve credentials securely: ${e.message}")
            throw e
        }

        println("Anthropic Api key - ${apiKey?.length}")


        if (apiKey.isNullOrBlank()) {
            System.err.println("AnthropicService [${project.name}] ERROR: Anthropic API Key not found or configured for this project.")
            // Optionally show notification only once? Might be annoying.
            // Consider a status indicator in your plugin's UI instead.
            throw IllegalStateException("Key not present")
        }
        println("AnthropicService [${project.name}]: Found API Key, initializing client. $apiKey")

        // --- Create the actual client ---
        return try {
            // Replace with the actual constructor or factory method
            AnthropicOkHttpClient.builder().apiKey("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").build()
        } catch (e: Exception) {
            System.err.println("AnthropicService [${project.name}] ERROR: Failed to initialize AnthropicClient with retrieved key: ${e.message}")
            throw e // Return null if client creation fails
        }
    }

    fun setApiKey(apiKey: String) {
        val credentials = Credentials("Anthropic API Key for ${project.name}", apiKey) // Username can include project hint
        try {
            // --- Use the correct PasswordSafe API ---
            PasswordSafe.instance.set(ANTHROPIC_CREDENTIAL_ATTRIBUTES, credentials)
            println("AnthropicService [${project.name}]: API Key stored securely.")
            // TODO: If 'anthropicClient' is already initialized, you might need a mechanism
            // to signal that it should be recreated with the new key upon next access.
            // Simplest is often just letting the lazy delegate run again next time.
        } catch (e: PasswordSafeException) {
            System.err.println("AnthropicService [${project.name}] ERROR: Failed to store credentials securely: ${e.message}")
            // TODO: Show error to user?
        }
    }

    /**
     * Retrieves the stored API key securely and returns a mask
     * (asterisks matching the key length) or an empty string if no key is stored.
     */
    // TODO: Move this to IO thread
    fun getApiKeyMask(): String {
        val storedApiKey: String? = try {
            PasswordSafe.instance.getPassword(ANTHROPIC_CREDENTIAL_ATTRIBUTES)
        } catch (e: PasswordSafeException) {
            println("Error reading stored API key for masking: ${e.message}")
            null // Treat error as no key stored
        }
        // IMPORTANT: Do NOT log the storedApiKey variable itself here!
        return if (storedApiKey != null) {
            "*".repeat(storedApiKey.length)
        } else {
            "" // No key stored, show empty field
        }
    }

    companion object {
        fun getInstance(project: Project): AnthropicService = project.getService(AnthropicService::class.java)
    }
}
