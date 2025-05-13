package com.brahamchari.demoplugin.services

// Replace with actual Gemini client import if different
// e.g., import com.google.ai.client.generativeai.GenerativeModel
import com.google.genai.Client // Placeholder, use your actual Gemini Client class

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.passwordSafe.PasswordSafeException

// CredentialAttributes remain the same
val GEMINI_CREDENTIAL_ATTRIBUTES = CredentialAttributes(
    generateServiceName("MyMCPPluginGeminiService", "GeminiApiKey")
)

@Service(Service.Level.PROJECT)
class GeminiService(
    private val project: Project
) {

    init {
        println("GeminiService instance created for project: ${project.name}. Thread: ${Thread.currentThread().name}")
    }

    private val lock = Any()
    private var _geminiClient: Client? = null // Use the actual Gemini Client type

    /**
     * Returns the Gemini client.
     * If the client is not already initialized, this method will attempt to initialize it
     * by fetching the API key from PasswordSafe. This means the first call to this method
     * (or a call after the client has been cleared) CAN BE A BLOCKING OPERATION.
     *
     * It is recommended to ensure the client is initialized via `setApiKey` from a background
     * thread, or call this method from a background thread if initialization is expected.
     *
     * @return The initialized GeminiClient, or null if initialization fails (e.g., no API key).
     */
    fun getGeminiClient(): Client? { // Use the actual Gemini Client type
        synchronized(lock) {
            if (_geminiClient == null) {
                println("GeminiService [${project.name}]: Client is null, attempting to initialize on first get. Thread: ${Thread.currentThread().name}")
                // This internal helper will perform the blocking PasswordSafe read
                tryInitializeClientFromStorageBlocking()
            }
            return _geminiClient
        }
    }

    /**
     * Internal helper to attempt client initialization from PasswordSafe.
     * This method performs blocking operations and is called within a synchronized block.
     */
    private fun tryInitializeClientFromStorageBlocking() {
        // This function is called within the synchronized(lock) block of getGeminiClient
        val apiKey: String? = try {
            // WARNING: Blocking call!
            PasswordSafe.instance.getPassword(GEMINI_CREDENTIAL_ATTRIBUTES)
        } catch (e: PasswordSafeException) {
            System.err.println("GeminiService [${project.name}] ERROR: Failed to retrieve credentials during lazy init: ${e.message}")
            null
        }

        if (!apiKey.isNullOrBlank()) {
            println("GeminiService [${project.name}]: API key found in PasswordSafe during lazy init, attempting to create client.")
            try {
                // createOrUpdateClient updates _geminiClient internally (assuming lock is held)
                createOrUpdateClient(apiKey)
            } catch (e: IllegalStateException) {
                // createOrUpdateClient already logs the error. _geminiClient will be null.
                System.err.println("GeminiService [${project.name}]: Client initialization failed during lazy init: ${e.message}")
            }
        } else {
            println("GeminiService [${project.name}]: No API key found in PasswordSafe during lazy init, client remains null.")
            // _geminiClient is already null and remains null
        }
    }


    /**
     * Creates or updates the Gemini client instance using the provided API key.
     * This method updates the internal _geminiClient.
     * IMPORTANT: This method assumes the caller is holding the `lock`.
     *
     * @param apiKey The API key to use for the client. Must not be blank.
     * @return The created or updated Gemini Client instance.
     * @throws IllegalStateException if the client cannot be initialized (e.g., SDK error).
     */
    private fun createOrUpdateClient(apiKey: String): Client { // Use the actual Gemini Client type
        println("GeminiService [${project.name}]: Creating/updating Gemini client with API Key (length: ${apiKey.length}). Thread: ${Thread.currentThread().name}")
        try {
            // Replace with actual Gemini client initialization logic
            val newClient = Client.builder()
                .apiKey(apiKey)
                .build()

            // Assign directly as the lock is assumed to be held by the caller
            _geminiClient = newClient
            println("GeminiService [${project.name}]: Gemini client successfully initialized/updated.")
            return newClient
        } catch (e: Exception) {
            System.err.println("GeminiService [${project.name}]: ERROR: Failed to initialize Gemini Client with key: ${e.message}. Exception: $e")
            e.printStackTrace() // Consider more structured logging
            _geminiClient = null // Ensure client is null on failure (lock is held by caller)
            throw IllegalStateException("Failed to initialize Gemini client. Cause: ${e.message}", e)
        }
    }

    /**
     * Sets (stores) or clears the Gemini API key in PasswordSafe.
     * If a non-empty key is provided and stored successfully, it also attempts to
     * initialize/update the internal Gemini client.
     * If an empty key is provided, it clears the stored key and the internal client.
     *
     * IMPORTANT: This method involves PasswordSafe access and should be called
     * from a background thread by the consumer.
     *
     * @param apiKey The API key to store. If empty or blank, the stored key will be cleared.
     */
    fun setApiKey(apiKey: String) {
        println("GeminiService [${project.name}]: setApiKey called (key empty: ${apiKey.isBlank()}). Thread: ${Thread.currentThread().name}")
        try {
            val credentialsToStore = if (apiKey.isNotBlank()) Credentials(project.name, apiKey) else null
            PasswordSafe.instance.set(GEMINI_CREDENTIAL_ATTRIBUTES, credentialsToStore)

            synchronized(lock) { // Ensure thread-safe update to _geminiClient
                if (apiKey.isNotBlank()) {
                    println("GeminiService [${project.name}]: Gemini API Key stored securely.")
                    // Attempt to create/update the client with the new key
                    createOrUpdateClient(apiKey) // This updates _geminiClient (lock is held)
                } else {
                    println("GeminiService [${project.name}]: Gemini API Key cleared from secure storage.")
                    _geminiClient = null // Clear the client instance (lock is held)
                }
            }
        } catch (e: PasswordSafeException) {
            System.err.println("GeminiService [${project.name}]: ERROR: Failed to store/clear Gemini API key securely: ${e.message}")
        } catch (e: IllegalStateException) {
            // This comes from createOrUpdateClient failing
            System.err.println("GeminiService [${project.name}]: ERROR: Client creation failed after setting API key: ${e.message}")
        }
    }

    /**
     * Retrieves a mask of the stored Gemini API key.
     * Returns an empty string if no key is stored or if an error occurs.
     *
     * IMPORTANT: This method involves PasswordSafe access and should be called
     * from a background thread by the consumer.
     */
    fun getApiKeyMask(): String {
        println("GeminiService [${project.name}]: getApiKeyMask called. Thread: ${Thread.currentThread().name}")
        val storedApiKey: String? = try {
            PasswordSafe.instance.getPassword(GEMINI_CREDENTIAL_ATTRIBUTES)
        } catch (e: PasswordSafeException) {
            System.err.println("GeminiService [${project.name}]: Error reading stored API key for mask: ${e.message}")
            null
        }

        return if (!storedApiKey.isNullOrBlank()) {
            "*".repeat(storedApiKey.length)
        } else {
            ""
        }
    }

    companion object {
        fun getInstance(project: Project): GeminiService = project.getService(GeminiService::class.java)
    }
}