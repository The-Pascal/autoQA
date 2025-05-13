package com.brahamchari.demoplugin.services

import com.brahamchari.MCPServer
import com.brahamchari.android.AndroidMCPServerImpl
import com.brahamchari.demoplugin.client.AndroidMCPClient
import com.brahamchari.demoplugin.client.MCPClient
import com.brahamchari.demoplugin.utils.ADBUtils
import com.brahamchari.demoplugin.utils.Utils
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

@Service(Service.Level.PROJECT)
class McpService(
    private val project: Project
) : CoroutineScope, Disposable {
    // --- Manage CoroutineScope internally ---
    // Create a Job that can be cancelled when the service is disposed
    private val serviceJob = SupervisorJob()
    // Define the context using the Job and appropriate dispatchers
    override val coroutineContext: CoroutineContext
        get() = serviceJob + Dispatchers.Default // Or IO, depending on primary work type

    private val settingsState = SettingService.getInstance(project).state

    // --- Configuration ---
    private val serverPort: Int = settingsState.mcpServerPort // Match server port
    private val serverHost: String = "127.0.0.1" // Connect specifically to loopback
    private val adbPath: String by lazy {
        ADBUtils.getAdbPath() ?: "adb"
    }

    // --- Get Dependencies Lazily ---
    // Get the project-specific AnthropicService lazily
    private val anthropicService: AnthropicService by lazy {
        println("McpService [${project.name}]: Lazily getting AnthropicService instance.")
        // AnthropicService.getInstance(project) // Use companion object if available
        project.getService(AnthropicService::class.java) // Standard way
    }

    private val _mcpServer = lazy {
        println("McpService [${project.name}]: Lazily creating AndroidMCPServerImpl.")
        AndroidMCPServerImpl(adbPath, serverPort, "0.0.0.0") // Pass project-specific port
    }
    val mcpServer by _mcpServer

    private val _mcpClient = lazy {
        println("McpService [${project.name}]: Lazily creating AndroidMCPClient.")
        AndroidMCPClient(
            host = serverHost,
            port = serverPort, // Use the same derived port
            parentScope = this, // Pass this service's scope,
            project = project
        )
    }
    val mcpClient: MCPClient by _mcpClient

    @Volatile
    var isServiceRunning = false
        private set
    private val isStartingOrStopping = AtomicBoolean(false)

    init {
        println("McpService: Initializing with WebSocket...")
    }

    suspend fun initializeAndStart(): Boolean {
        if (isServiceRunning) {
            println("McpService [${project.name}]: Already running.")
            return true
        }
        if (!isStartingOrStopping.compareAndSet(false, true)) {
            println("McpService [${project.name}]: Startup or shutdown already in progress.")
            return false
        }

        println("McpService [${project.name}]: Attempting to start server and client...")
        var success = false

        try {
            withContext(Dispatchers.IO) {
                val server = mcpServer // Trigger lazy init
                val client = mcpClient // Trigger lazy init

                println("McpService [${project.name}]: Starting server...")
                val serverStarted = server.startServer()
                if (!serverStarted) {
                    throw Exception("MCP server failed to start.")
                }
                println("McpService [${project.name}]: Server start successful.")

                println("McpService [${project.name}]: Connecting client...")
                try {
                    client.connect()
                    delay(500) // Minimal delay only if SDK requires it for isConnected check
                    if (!client.isConnected) {
                        throw Exception("MCP client failed to connect.")
                    }
                    println("McpService [${project.name}]: Client connect successful.")
                } catch (clientEx: Exception) {
                    throw Exception("MCP client connection failed: ${clientEx.message}", clientEx)
                }

                println("McpService [${project.name}]: Server and Client started successfully.")
                isServiceRunning = true
                success = true
            } // End withContext

            return true // Startup successful

        } catch (e: CancellationException) {
            System.err.println("McpService [${project.name}]: Startup cancelled.")
            // Let cancellation propagate, but ensure cleanup runs
            throw e // Re-throw cancellation
        } catch (e: Exception) {
            System.err.println("McpService [${project.name}]: Unexpected error during startup: ${e.message}")
            e.printStackTrace()
        } finally {
            if (!success) {
                System.err.println("McpService [${project.name}]: Startup sequence failed or cancelled, ensuring cleanup...")
                withContext(NonCancellable) {
                    stopServerAndClientInternal() // Call internal version
                }
            }
            // Release the lock ONLY after all operations (including potential cleanup) are done
            isStartingOrStopping.set(false)
            println("McpService [${project.name}]: Startup attempt finished (Success: $success).")
        }
        return false // Return false if any non-cancellation exception occurred
    }

    suspend fun stopServerAndClient() {
        if (!isServiceRunning && !isStartingOrStopping.get()) {
            println("McpService [${project.name}]: Not running or already stopping.")
            return
        }
        if (!isStartingOrStopping.compareAndSet(false, true)) {
            println("McpService [${project.name}]: Startup or shutdown already in progress.")
            return
        }
        stopServerAndClientInternal()
    }

    // Internal function for actual stopping logic
    private suspend fun stopServerAndClientInternal() {
        println("McpService [${project.name}]: Internal shutdown logic running...")
        isServiceRunning = false

        if (_mcpClient.isInitialized()) {
            try { mcpClient.disconnect() } catch (e: Exception) { System.err.println("Error disconnecting client: ${e.message}") }
        }
        if (_mcpServer.isInitialized()) {
            try { mcpServer.stopServer() } catch (e: Exception) { System.err.println("Error stopping server: ${e.message}") }
        }
        if (_mcpClient.isInitialized()) {
            try { mcpClient.shutdown() } catch (e: Exception) { System.err.println("Error shutting down client: ${e.message}") }
        }
        println("McpService [${project.name}]: Internal shutdown sequence complete.")
        isStartingOrStopping.set(false) // Release lock
    }

    override fun dispose() {
        println("McpService [${project.name}]: Disposing service...")
        if (isStartingOrStopping.get()) {
            println("McpService [${project.name}]: Warning: Disposing while startup/shutdown might be in progress.")
        }
        if (serviceJob.isActive) {
            serviceJob.cancel(CancellationException("McpService for project ${project.name} is being disposed."))
            println("McpService [${project.name}]: Coroutine scope cancelled.")
        }
        // runBlocking { stopServerAndClientInternal() } // Use with caution
    }


    companion object {
        fun getInstance(project: Project): McpService = project.getService(McpService::class.java)
    }
}
