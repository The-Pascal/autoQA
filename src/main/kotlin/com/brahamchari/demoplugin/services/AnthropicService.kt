package com.brahamchari.demoplugin.services

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.passwordSafe.PasswordSafeException

// Define attributes for storing the credential securely
val ANTHROPIC_CREDENTIAL_ATTRIBUTES = CredentialAttributes(
    generateServiceName("MyMCPPluginAnthropicService", "AnthropicApiKey")
)

@Service(Service.Level.PROJECT)
class AnthropicService(
    private val project: Project
) {

    init {
        println("AnthropicService instance created for project: ${project.name}. Thread: ${Thread.currentThread().name}")
    }

    private val lock = Any()
    private var _anthropicClient: AnthropicClient? = null

    /**
     * Returns the Anthropic client.
     * If the client is not already initialized, this method will attempt to initialize it
     * by fetching the API key from PasswordSafe. This means the first call to this method
     * (or a call after the client has been cleared) can be a BLOCKING OPERATION.
     *
     * It is recommended to ensure the client is initialized via `setApiKey` from a background
     * thread, or call this method from a background thread if initialization is expected.
     *
     * @return The initialized AnthropicClient, or null if initialization fails (e.g., no API key).
     */
    fun getAnthropicClient(): AnthropicClient? {
        synchronized(lock) {
            if (_anthropicClient == null) {
                println("AnthropicService [${project.name}]: Client is null, attempting to initialize on first get. Thread: ${Thread.currentThread().name}")
                // This internal helper will perform the blocking PasswordSafe read
                tryInitializeClientFromStorageBlocking()
            }
            return _anthropicClient
        }
    }

    /**
     * Internal helper to attempt client initialization from PasswordSafe.
     * This method performs blocking operations.
     */
    private fun tryInitializeClientFromStorageBlocking() {
        // This function is called within the synchronized(lock) block of getAnthropicClient
        val apiKey: String? = try {
            // WARNING: Blocking call!
            PasswordSafe.instance.getPassword(ANTHROPIC_CREDENTIAL_ATTRIBUTES)
        } catch (e: PasswordSafeException) {
            System.err.println("AnthropicService [${project.name}] ERROR: Failed to retrieve credentials during lazy init: ${e.message}")
            null
        }

        if (!apiKey.isNullOrBlank()) {
            println("AnthropicService [${project.name}]: API key found in PasswordSafe during lazy init, attempting to create client.")
            try {
                // createOrUpdateClient updates _anthropicClient internally
                createOrUpdateClient(apiKey)
            } catch (e: IllegalStateException) {
                // createOrUpdateClient already logs the error. _anthropicClient will be null.
                System.err.println("AnthropicService [${project.name}]: Client initialization failed during lazy init: ${e.message}")
            }
        } else {
            println("AnthropicService [${project.name}]: No API key found in PasswordSafe during lazy init, client remains null.")
            // _anthropicClient is already null and remains null
        }
    }


    /**
     * Creates or updates the Anthropic client instance using the provided API key.
     * This method updates the internal _anthropicClient.
     *
     * @param apiKey The API key to use for the client. Must not be blank.
     * @return The created or updated AnthropicClient instance.
     * @throws IllegalStateException if the client cannot be initialized (e.g., SDK error).
     */
    private fun createOrUpdateClient(apiKey: String): AnthropicClient {
        println("AnthropicService [${project.name}]: Creating/updating client with API Key (length: ${apiKey.length}). Thread: ${Thread.currentThread().name}")
        try {
            val newClient = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build()
            // This assignment must happen within the synchronized(lock) block,
            // but since createOrUpdateClient is called by methods that already hold the lock
            // (setApiKey and tryInitializeClientFromStorageBlocking, which is called by getAnthropicClient),
            // we assign it directly here.
            _anthropicClient = newClient
            return newClient
        } catch (e: Exception) {
            System.err.println("AnthropicService [${project.name}]: ERROR: Failed to initialize AnthropicClient with key: ${e.message}")
            _anthropicClient = null // Ensure client is null on failure
            throw IllegalStateException("Failed to initialize AnthropicClient", e)
        }
    }

    /**
     * Sets (stores) or clears the Anthropic API key in PasswordSafe.
     * If a non-empty key is provided and stored successfully, it also attempts to
     * initialize/update the internal Anthropic client.
     * If an empty key is provided, it clears the stored key and the internal client.
     *
     * IMPORTANT: This method involves PasswordSafe access and should be called
     * from a background thread by the consumer (e.g., MainSettings).
     *
     * @param apiKey The API key to store. If empty, the stored key will be cleared.
     */
    fun setApiKey(apiKey: String) {
        println("AnthropicService [${project.name}]: setApiKey called (key empty: ${apiKey.isEmpty()}). Thread: ${Thread.currentThread().name}")
        try {
            val credentialsToStore = if (apiKey.isNotEmpty()) Credentials(project.name, apiKey) else null
            PasswordSafe.instance.set(ANTHROPIC_CREDENTIAL_ATTRIBUTES, credentialsToStore)

            synchronized(lock) { // Ensure thread-safe update to _anthropicClient
                if (apiKey.isNotEmpty()) {
                    println("AnthropicService [${project.name}]: Anthropic API Key stored securely.")
                    createOrUpdateClient(apiKey) // This updates _anthropicClient
                } else {
                    println("AnthropicService [${project.name}]: Anthropic API Key cleared from secure storage.")
                    _anthropicClient = null // Clear the client instance
                }
            }
        } catch (e: PasswordSafeException) {
            System.err.println("AnthropicService [${project.name}]: ERROR: Failed to store/clear Anthropic API key securely: ${e.message}")
        }
    }

    /**
     * Retrieves a mask of the stored Anthropic API key (e.g., "********").
     * Returns an empty string if no key is stored or if an error occurs.
     *
     * IMPORTANT: This method involves PasswordSafe access and should be called
     * from a background thread by the consumer (e.g., MainSettings).
     */
    fun getApiKeyMask(): String {
        println("AnthropicService [${project.name}]: getApiKeyMask called. Thread: ${Thread.currentThread().name}")
        val storedApiKey: String? = try {
            PasswordSafe.instance.getPassword(ANTHROPIC_CREDENTIAL_ATTRIBUTES)
        } catch (e: PasswordSafeException) {
            System.err.println("AnthropicService [${project.name}]: Error reading stored API key for mask: ${e.message}")
            null
        }

        return if (!storedApiKey.isNullOrEmpty()) {
            "*".repeat(storedApiKey.length)
        } else {
            ""
        }
    }

    companion object {
        fun getInstance(project: Project): AnthropicService = project.getService(AnthropicService::class.java)
    }
}
